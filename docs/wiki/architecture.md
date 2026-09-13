# Architecture and request lifecycle

## Components

```text
MCP client
    │ HTTP POST / SSE GET
    ▼
McpHttpServer
    │ JSON-RPC dispatch and tool validation
    ├── McpToolExecutor ──► BbmcpClient ──► Minecraft client thread
    ├── integrated-server actions ─────────► Minecraft server thread
    ├── ClientActionController ────────────► bounded input/look actions
    ├── RecipeViewerAdapterRegistry ───────► vanilla catalog or JEI adapter
    └── McpDynamicToolRegistry ◄──────────── KubeJS reload event
```

`BbmcpClient` is the client-side entry point. During client setup it installs
the thread bridges, captures a small atomic snapshot for the KubeJS bridge on
client ticks, and starts `McpHttpServer`. During shutdown it closes the HTTP
listener and clears the optional client context.

`McpHttpServer` owns the embedded JDK `HttpServer`. It binds to
`127.0.0.1:<port>` and exposes `/mcp`. HTTP worker threads never read or
mutate Minecraft objects directly. They validate JSON, then enqueue work on
the appropriate game thread and wait for the configured timeout.

`McpToolExecutor` is the boundary between protocol code and Minecraft APIs.
This keeps JSON-RPC handling independent from client, integrated-server,
FTB Quests, and KubeJS implementation details.

## MCP transport

BBMCP currently supports the MCP JSON-RPC methods needed by the tool clients:

- `initialize`
- `notifications/initialized`
- `ping`
- `tools/list`
- `tools/call`

The server reports protocol version `2024-11-05` and advertises:

```json
{
  "capabilities": {
    "tools": {
      "listChanged": true
    }
  }
}
```

`POST /mcp` carries JSON-RPC requests and returns JSON responses. `GET /mcp`
opens an event stream for server notifications. When KubeJS tools are
reloaded, connected event streams receive:

```json
{
  "jsonrpc": "2.0",
  "method": "notifications/tools/list_changed",
  "params": {}
}
```

The client should call `tools/list` again after receiving that notification.

## Thread model

There are three relevant execution contexts:

1. HTTP request workers parse requests and perform no direct game mutation.
2. The Minecraft client thread owns `Minecraft`, `LocalPlayer`, screens,
   framebuffer capture, and client registries.
3. The integrated-server thread owns `MinecraftServer`, `ServerLevel`, server
   players, reloadable server resources, and KubeJS callbacks.

Calls that cross a thread use a bounded wait. A timeout after an action has
started reports that the outcome is unknown; the action is not silently
replayed. This matters for commands, reloads, screenshots, and KubeJS code.

## Optional integrations

Optional APIs are isolated behind explicit loaded-mod checks:

- FTB Quests code is in `FtbQuestsIntegration` and is only reached when
  `ftbquests` is loaded.
- KubeJS registration is discovered through `kubejs.plugins.txt` and is only
  active when KubeJS is present.
- Recipe data always starts from the client-synchronised vanilla
  `RecipeManager`. The optional JEI 19.x plugin is loaded through
  `RecipeViewerAdapterRegistry` only after JEI reports a live runtime; its
  direct API classes are kept out of the ordinary no-JEI path.
- EMI and REI are detected for capability reporting, but their adapters are
  not implemented yet. Requests for those viewers fail explicitly.
- Loot-table tools require an active integrated server, even though the HTTP
  listener itself lives on the client.

This lets the base client start in a vanilla-like environment while still
making missing prerequisites visible to the agent.

## Error behavior

Malformed JSON-RPC and invalid tool arguments are rejected before game-thread
work is scheduled. Tool execution failures are returned as MCP tool errors
with the underlying message. The server does not invent empty game state when
the client is in a menu, disconnected, or missing an integrated server.
