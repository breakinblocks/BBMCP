# Configuration

NeoMCP registers a NeoForge client-side configuration. It is generated at:

```text
<instance>/config/neomcp-client.toml
```

For this repository's development run, `<instance>` is `run`. Values are
created after the client has started once. This configuration is client-side:
it applies to the NeoMCP HTTP service running in that client instance, not to a
dedicated server. Restart the client after changing the file; this is
especially important for the listener port and executor settings.

## Settings

| Key | Default | Purpose |
| --- | ---: | --- |
| `enableServer` | `true` | Start the HTTP server during client setup. |
| `port` | `8080` | Loopback TCP port for `/mcp`; valid range `1`-`65535`. |
| `requestThreadCount` | `8` | Concurrent HTTP request workers; valid `1`-`64`. |
| `requestQueueCapacity` | `16` | Requests waiting for an HTTP worker; valid `1`-`1024`. |
| `maxRequestBytes` | `1048576` | Maximum JSON request body size; valid `4096`-`16777216`. |
| `sseConnectionLimit` | `8` | Maximum MCP event-stream connections; valid `1`-`128`. |
| `sseQueueCapacity` | `32` | Pending notifications per event stream; valid `1`-`1024`. |
| `sseHeartbeatSeconds` | `15` | Keep-alive interval for idle event streams; valid `1`-`300`. |
| `actionTimeoutSeconds` | `5` | Time allowed for a queued client, server, or KubeJS action; valid `1`-`120`. |
| `maxActionTicks` | `200` | Maximum duration accepted by primitive `look_at` and `move` actions; valid `1`-`1200`. |
| `maxRecipeResults` | `1000` | Maximum recipes returned by `find_recipes`; valid `1`-`10000`. |
| `maxRecipeInlineBytes` | `2000000` | Maximum UTF-8 bytes returned inline by advanced recipe dumps; valid `64000`-`16000000`. |
| `maxRecipeTreeDepth` | `5` | Maximum `get_recipe_tree` depth; valid `0`-`32`. |
| `maxRecipeLoopDepth` | `5` | Maximum `scan_for_loops` depth; valid `1`-`32`. |
| `maxRecipeGraphNodes` | `5000` | Maximum recipe graph expansion work units per tree or loop scan; valid `100`-`100000`. |
| `recipeCardWidth` | `320` | Width in pixels of the off-screen `capture_recipe_card` framebuffer; valid `64`-`2048`. |
| `recipeCardHeight` | `180` | Height in pixels of the off-screen `capture_recipe_card` framebuffer; valid `64`-`2048`. |
| `maxNearbyEntityRadius` | `512.0` | Maximum radius accepted by `get_nearby_entities`; valid `0.0`-`4096.0`. |
| `maxLogLines` | `100` | Number of final lines returned by `read_latest_logs`; valid `1`-`10000`. |
| `maxCommandLength` | `32768` | Maximum `execute_command` string length; valid `1`-`262144`. |
| `maxKubejsScriptLength` | `262144` | Maximum injected KubeJS script length; valid `1`-`1048576`. |
| `allowCommandExecution` | `true` | Publish and allow `execute_command`. |
| `allowKubejsScriptInjection` | `true` | Publish and allow `inject_kubejs_script`. |

NeoForge validates values against the declared ranges. The Java-side request
bounds are also enforced before a request reaches Minecraft; invalid tool
arguments are rejected explicitly.

## Recipe limits

Recipe depth limits are independent: `maxRecipeTreeDepth` controls
`get_recipe_tree`, while `maxRecipeLoopDepth` controls `scan_for_loops`.
`get_recipe_tree` defaults an omitted `max_depth` to `maxRecipeTreeDepth`, and
`scan_for_loops` defaults it to `maxRecipeLoopDepth`. Graph expansion is also
bounded by `maxRecipeGraphNodes`; these limits do not alter synchronized recipe
data.

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
