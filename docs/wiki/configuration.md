# Configuration

NeoMCP registers a NeoForge client configuration. It is generated at:

```text
<instance>/config/neomcp-client.toml
```

For this repository's development run, `<instance>` is `run`. Values are
created after the client has started once. Restart the client after changing
the file; this is especially important for the listener port and executor
settings.

## Settings

| Key | Default | Purpose |
| --- | ---: | --- |
| `enableServer` | `true` | Start the HTTP server during client setup. |
| `port` | `8080` | Loopback TCP port for `/mcp`; valid range `1`-`65535`. |
| `requestThreadCount` | `8` | Concurrent HTTP request workers. |
| `requestQueueCapacity` | `16` | Requests waiting for an HTTP worker. |
| `maxRequestBytes` | `1048576` | Maximum JSON request body size. |
| `sseConnectionLimit` | `8` | Maximum MCP event-stream connections. |
| `sseQueueCapacity` | `32` | Pending notifications per event stream. |
| `sseHeartbeatSeconds` | `15` | Keep-alive interval for idle event streams. |
| `actionTimeoutSeconds` | `5` | Time allowed for a queued client, server, or KubeJS action. |
| `maxNearbyEntityRadius` | `512.0` | Maximum radius accepted by `get_nearby_entities`. |
| `maxLogLines` | `100` | Number of final lines returned by `read_latest_logs`. |
| `maxCommandLength` | `32768` | Maximum `execute_command` string length. |
| `maxKubejsScriptLength` | `262144` | Maximum injected KubeJS script length. |
| `allowCommandExecution` | `true` | Publish and allow `execute_command`. |
| `allowKubejsScriptInjection` | `true` | Publish and allow `inject_kubejs_script`. |

NeoForge clamps invalid values to the declared ranges. The Java-side bounds
are also enforced before a request reaches Minecraft.

## Security switches

`enableServer = false` prevents the endpoint from starting. The two
`allow...` switches remove the corresponding privileged tool from
`tools/list` and reject calls to it:

```toml
allowCommandExecution = false
allowKubejsScriptInjection = false
```

The server always binds to `127.0.0.1`; there is intentionally no configurable
remote bind address. Loopback is a boundary against network peers, not an
authentication mechanism: another local process can still call the endpoint.

## Deliberately fixed paths and limits

The following paths are intentionally not user-configurable:

- `logs/latest.log` is resolved below the active game directory.
- injected code is always `kubejs/server_scripts/neomcp_injected.js`.
- screenshots and optional FTB canvas exports are stored below the active
  game directory's `screenshots` folder.

Keeping these paths fixed prevents an MCP request from selecting arbitrary
filesystem locations. FTB canvas rendering also retains hard pixel and
dimension caps to avoid allocating an unsafe off-screen image.
