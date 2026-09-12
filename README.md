# NeoMCP

NeoMCP is an internal development tool for NeoForge 1.21.1. It embeds a
localhost HTTP server in the Minecraft client JVM and exposes game-state
inspection, command execution, screenshots, and FTB Quests tooling through
MCP JSON-RPC.

This project is intended for local development and automation. It is not an
internet-facing server and currently has no authentication layer.

## Project metadata

- Mod name: `NeoMCP`
- Mod ID: `neomcp`
- Package: `com.breakinblocks.neomcp`
- Author: Tazz
- Minecraft: `1.21.1`
- NeoForge: `21.1.173` or newer in the `21.1.x` line
- Java: `21`
- License: All Rights Reserved

## Requirements

- Java 21
- A local Minecraft/NeoForge development environment
- FTB Quests is optional. FTB-specific tools fail clearly when it is not
  loaded.
- KubeJS is optional. `inject_kubejs_script` fails clearly when KubeJS is not
  loaded.

FTB Quests is included as a development runtime dependency from the FTB Maven
repository. The mod remains loadable without FTB Quests in other environments.

## Build and run

From the project root in PowerShell:

```powershell
.\gradlew.bat build
.\gradlew.bat runClient
```

The configured `runClient` task uses Minecraft quick play to join the
single-player save named `New World`, making the client ready for in-world
tools during development. Change the quick-play arguments in `build.gradle`
when a different test save is required.

When the client is running, NeoMCP listens on:

```text
http://localhost:8080/mcp
```

The server binds to loopback (`127.0.0.1`) and accepts `POST` requests with
the `application/json` content type. It implements MCP JSON-RPC 2.0 with
protocol version `2024-11-05`.

## MCP client configuration

Register the server as an HTTP MCP server named `neomcp`:

```text
http://localhost:8080/mcp
```

The repository records the completed configuration audit in
[docs/phase0-global-mcp-configuration.md](docs/phase0-global-mcp-configuration.md).
The actual client configuration files are user-level files and are not stored
in Git.

## Calling the server

Initialize the connection:

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "initialize",
  "params": {}
}
```

List available tools:

```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "method": "tools/list",
  "params": {}
}
```

Call a tool:

```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "method": "tools/call",
  "params": {
    "name": "get_player_info",
    "arguments": {}
  }
}
```

For example, with PowerShell:

```powershell
$body = '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"get_player_info","arguments":{}}}'
Invoke-RestMethod -Uri http://localhost:8080/mcp -Method Post `
  -ContentType 'application/json' -Body $body
```

## Tool catalog

### Core and state inspection

| Tool | Arguments | Result |
| --- | --- | --- |
| `execute_command` | `{ "command": string }` | Sends a command through the connected Minecraft client/server connection. Do not include the leading `/`. |
| `get_player_info` | none | Local player X, Y, Z, dimension, and health. |
| `get_block_entity_data` | `{ "x": int, "y": int, "z": int }` | Block entity type, full NBT/SNBT metadata, and data components. |
| `inspect_item_components` | none | Main-hand item ID, stack state, exact data components, and component patch. |
| `query_registry` | `{ "registry": string, "namespace": string }` | Registered object IDs in the requested registry and namespace. |
| `read_latest_logs` | none | The last 100 lines of `logs/latest.log`. |
| `get_nearby_entities` | `{ "x": number, "y": number, "z": number, "radius": number }` | Entity state and serialized data in the requested area. Radius is limited to 512 blocks. |

### KubeJS

| Tool | Arguments | Result |
| --- | --- | --- |
| `inject_kubejs_script` | `{ "script": string }` | Writes `kubejs/server_scripts/neomcp_injected.js` and dispatches `/reload`. Requires KubeJS to be loaded. |

### FTB Quests

| Tool | Arguments | Result |
| --- | --- | --- |
| `open_quest_gui` | `{ "id": string, "object_type": "chapter" \| "quest" }` | Opens the requested FTB Quests chapter or quest. |
| `get_chapter_layout` | `{ "chapter_id": string }` | Chapter quest nodes, grid positions, sizes, and dependency links. |
| `export_chapter_canvas` | `{ "chapter_id": string, "save_png": boolean? }` | Returns one MCP `image` content block containing a full chapter canvas. Optionally saves a PNG under `screenshots/`. |

FTB Quests IDs are 64-bit values. Always send them as exact 16-character
hexadecimal strings, never JSON numbers, so an AI client cannot round them.
For example:

```json
{
  "name": "export_chapter_canvas",
  "arguments": {
    "chapter_id": "6F3CA3EA0F8276C0",
    "save_png": true
  }
}
```

The canvas export includes the chapter background, quest nodes, quest images,
and dependency lines. It excludes the normal FTB Quests sidebar, search bar,
and player inventory UI. With `save_png: true`, the file is written to a path
similar to:

```text
run/screenshots/neomcp_chapter_<chapter-id>_<uuid>.png
```

All FTB Quests tools check that `ftbquests` is loaded before accessing its
client API.

### Screenshots

| Tool | Arguments | Result |
| --- | --- | --- |
| `take_screenshot` | none | Captures the main framebuffer to `screenshots/` and reports the path. An active `Screen` UI overlay is included. |
| `update_take_screenshot` | none | Alias of `take_screenshot` with the same UI-inclusive behavior. |

## Architecture

- `NeoMcpClient` owns the client-thread bridge and game-state operations.
- `McpHttpServer` provides loopback HTTP transport and MCP JSON-RPC dispatch.
- `McpToolExecutor` defines the extensible tool boundary.
- `FtbQuestsIntegration` isolates optional FTB Quests GUI, layout, and canvas
  rendering code.
- `McpNbtJson` converts Minecraft NBT and data components into JSON-safe
  structures.

Requests arrive on a small daemon executor and game/client operations are
scheduled on the Minecraft client thread. The server starts during client
setup and is closed during client shutdown.

## Development notes

Useful validation commands:

```powershell
.\gradlew.bat compileJava
.\gradlew.bat build
```

The development client must be in a world for player, entity, FTB Quests, and
command tools to succeed. The automatic `New World` quick-play configuration
is provided for this purpose.

Do not expose port 8080 beyond the local machine. The server is an internal
developer endpoint and is intentionally not designed for hostile network
traffic.
