# NeoMCP

NeoMCP is an internal development tool for NeoForge 1.21.1. It embeds a
localhost HTTP server in the Minecraft client JVM and exposes game-state
inspection, command execution, screenshots, and FTB Quests tooling through
MCP JSON-RPC.

This project is intended for local development and automation. It is not an
internet-facing server and currently has no authentication layer.

The maintained technical documentation is in the [NeoMCP wiki](docs/wiki/README.md).
The [CurseForge description](docs/curseforge-description.md) contains a
publish-ready overview, installation steps, and a short usage guide.

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
- JEI is optional. The recipe catalog works from the synchronized vanilla
  recipe manager; JEI adds viewer categories, catalysts, GUI opening, and
  recipe-card rendering.

FTB Quests is included as a development runtime dependency from the FTB Maven
repository. The mod remains loadable without FTB Quests in other environments.
KubeJS 2101.7.1-build.181 is also included only for the development runtime;
the published NeoMCP dependency is optional.

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

When the client is running, NeoMCP listens on the default port:

```text
http://localhost:8080/mcp
```

The port and request/resource limits are configurable in
`config/neomcp-client.toml`; see [Configuration](docs/wiki/configuration.md).
The server always binds to loopback (`127.0.0.1`) and accepts `POST` requests
with the `application/json` content type. It implements MCP JSON-RPC 2.0 with
protocol version `2024-11-05`.

## MCP client configuration

Register the server as an HTTP MCP server named `neomcp`:

```text
http://localhost:8080/mcp
```

The repository records the completed configuration audit in
[docs/phase0-global-mcp-configuration.md](docs/phase0-global-mcp-configuration.md),
and repeatable setup instructions are in
[Agent installation](docs/wiki/installation.md).
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
| `list_mods` | none | Sorted IDs, display names, and versions for every mod loaded in the client JVM. |
| `get_player_info` | none | Local player X, Y, Z, dimension, and health. |
| `get_block_entity_data` | `{ "x": int, "y": int, "z": int }` | Block entity type, full NBT/SNBT metadata, and data components. |
| `inspect_item_components` | none | Main-hand item ID, stack state, exact data components, and component patch. |
| `query_registry` | `{ "registry": string, "namespace": string }` | Registered object IDs in the requested registry and namespace. |
| `read_latest_logs` | none | The configured number of final lines from `logs/latest.log` (100 by default). |
| `get_nearby_entities` | `{ "x": number, "y": number, "z": number, "radius": number }` | Entity state and serialized data in the requested area. Radius is limited to 512 blocks by default. |

### Datapack and loot tables

| Tool | Arguments | Result |
| --- | --- | --- |
| `list_loot_tables` | none | All loot-table ResourceLocations loaded by the active integrated server. |
| `get_loot_table` | `{ "loot_table_id": string }` | The exact serialized JSON definition of one loaded loot table. |
| `search_loot_tables` | `{ "item_id": string }` | Loaded loot-table IDs whose definitions contain the requested item ID. |

Loot tables are server-side data. These tools require an active local
single-player/integrated server and return an explicit error in menus or
multiplayer. They read the 1.21.1 reloadable loot registry and use the
registry-aware `LootTable.DIRECT_CODEC` for JSON serialization.

### KubeJS

| Tool | Arguments | Result |
| --- | --- | --- |
| `inject_kubejs_script` | `{ "script": string }` | Writes `kubejs/server_scripts/neomcp_injected.js` and dispatches `/reload`. Requires KubeJS to be loaded. |

When KubeJS is loaded, NeoMCP registers the `NeoMcpEvents.register` server
event. Register pack-specific MCP tools during the event:

```js
NeoMcpEvents.register(event => {
  event.registerTool(
    'current_dimension',
    'Return the active server dimension.',
    {
      type: 'object'
    },
     context => {
       return { dimension: String(context.level.dimension) }
     }
  )
})
```

The callback receives a context containing the current `server`, `level`, and
JSON `arguments`. It also exposes `serverPlayer` for the local integrated-server
player and `minecraft` for the local client instance when NeoMCP is running in
an integrated client. The callback runs on the Minecraft server thread. Use
`context.isServerPlayerAvailable()` and `context.isMinecraftAvailable()` before
accessing those optional client-side values; `serverPlayer` is unavailable on a
dedicated server or when the local player is not connected, while `minecraft`
is unavailable on a dedicated server. Their getters throw when unavailable.
`serverPlayer` is safe to use from the callback's server thread. `minecraft` is
a live client handle and
client-thread-confined methods must not be called directly from that callback.
The dimension is derived from `context.level.dimension`.

KubeJS tools are rebuilt on every server-script load, including `/reload`; the
active HTTP server advertises `tools.listChanged` and sends
`notifications/tools/list_changed` to connected `text/event-stream` clients.

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

### Client actions

| Tool | Arguments | Result |
| --- | --- | --- |
| `look_at` | `{ "x": number, "y": number, "z": number, "duration_ticks": int? }` | Turns the local player toward a position with shortest-path yaw wrapping and linear yaw/pitch interpolation. |
| `jump` | none | Performs one client-side jump. |
| `move` | `{ "direction": "forward" \| "backward" \| "left" \| "right", "duration_ticks": int }` | Holds one vanilla movement key for a bounded duration. |
| `interact` | `{ "target": "looked_at" \| "air", "hand": "main_hand" \| "off_hand"? }` | Uses a hand on the current crosshair target or in air. |
| `get_action_status` | `{ "action_id": int }` | Reports an action's state and elapsed ticks. |
| `cancel_action` | `{ "action_id": int }` | Cancels the active action and releases movement input. |

`look_at` and `move` are asynchronous client-tick actions. They return an
action ID and are bounded by `maxActionTicks`; starting another action
cancels the previous one. These are primitive inputs only: NeoMCP does not
provide collision-aware navigation, pathfinding, mouse automation, or
Baritone support. See [Movement and compatibility](docs/wiki/movement-and-compatibility.md).

### Recipes and viewers

| Tool | Arguments | Result |
| --- | --- | --- |
| `recipe_capabilities` | none | Reports synchronized recipe access and detected JEI/EMI/REI viewers. |
| `find_recipes` | `{ "query": string?, "recipe_type": string?, "limit": int? }` | Searches the client recipe manager. |
| `get_recipe` | `{ "recipe_id": string }` | Returns one exact synchronized recipe. |
| `view_recipe` | `{ "recipe_id": string, "viewer": "auto" \| "jei" \| "emi" \| "rei"?, "mode": "recipe" \| "uses"? }` | Opens a recipe in an implemented optional viewer adapter. |
| `get_recipe_tree` | `{ "item_id": string, "max_depth": int? }` | Returns a bounded reverse crafting tree with ingredient slots, result amounts, and workstation metadata. |
| `get_item_usages` | `{ "item_id": string }` | Returns recipes/categories where the item is an ingredient or catalyst. |
| `get_workstation_recipes` | `{ "machine_id": string }` | Returns JEI recipes associated with a machine/workstation catalyst. |
| `scan_for_loops` | `{ "item_id": string, "max_depth": int? }` | Finds potential circular recipe dependencies, capped by `maxRecipeLoopDepth`. |
| `capture_recipe_card` | `{ "recipe_id": string, "save_png": boolean? }` | Returns one JEI recipe card as an off-screen `image/png` MCP content block; optionally saves a generated PNG under `screenshots/`. |
| `dump_recipes` | `{ "mod_namespace": string?, "recipe_type": string?, "save_json": boolean? }` | Dumps synchronized recipes, optionally filtered and saved under `dumps/recipes/`. |
| `analyze_recipe_complexity` | `{ "item_id": string, "save_json": boolean? }` | Calculates bounded namespace diversity and sequential processing depth. |
| `check_recipe_cycles` | `{ "item_id": string, "save_json": boolean? }` | Detects bounded recipe cycles reachable from an item. |
| `find_underutilized_items` | `{ "mod_namespace": string, "save_json": boolean? }` | Finds producible items in a namespace with zero canonical recipe consumers. |

The canonical recipe tools work without a viewer. JEI 19.x is the first
viewer adapter and is compile-only in this project; install JEI in the
runtime instance to enable GUI/catalyst/card features. EMI and REI are
detected for future drop-in adapters but are not implemented yet. Recipe
tools are read-only and do not transfer items or craft on the player's
behalf. See [Recipe viewers and recipe graph](docs/wiki/recipes.md).

## Architecture

- `NeoMcpClient` owns the client-thread bridge and game-state operations.
- `McpHttpServer` provides loopback HTTP transport and MCP JSON-RPC dispatch.
- `McpDynamicToolRegistry` publishes KubeJS tools atomically between reloads.
- `McpToolExecutor` defines the extensible tool boundary.
- `ClientActionController` owns bounded client-tick look, movement, jump, and
  interaction actions.
- `com.breakinblocks.neomcp.recipe` contains the viewer-neutral recipe catalog,
  graph operations, optional JEI adapter, and future viewer boundary.
- `com.breakinblocks.neomcp.kubejs` contains the optional KubeJS plugin, event,
  and Rhino callback bridge.
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

The development client must be in a world for player, entity, action, recipe,
FTB Quests, and command tools to succeed. The automatic `New World` quick-play
configuration is provided for this purpose. Use `execute_command` with a
server command such as `tp @s 100 70 -20` for deterministic teleport setup;
primitive movement is not a pathfinder.

Do not expose the configured port beyond the local machine. The server is an internal
developer endpoint and is intentionally not designed for hostile network
traffic.
