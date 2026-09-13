# Tool reference

Tool results contain normal MCP text content and, where useful, a
`structuredContent` JSON object. Screenshot and chapter-canvas tools can also
return an MCP `image` content block.

## Core inspection and development tools

| Tool | Arguments | Availability |
| --- | --- | --- |
| `list_mods` | none | Client JVM; works at the title screen. |
| `get_player_info` | none | Active local player in a world. |
| `execute_command` | `{ "command": string }` | Connected client/server; omit `/`. |
| `get_block_entity_data` | `{ "x": int, "y": int, "z": int }` | Loaded block entity in the active client level. |
| `inspect_item_components` | none | Active local player; reads the main-hand stack. |
| `query_registry` | `{ "registry": string, "namespace": string }` | Active client level registry access. |
| `read_latest_logs` | none | Active game directory; returns the configured final lines. |
| `get_nearby_entities` | `{ "x": number, "y": number, "z": number, "radius": number }` | Active client level; radius is capped by config. |

`list_mods` returns deterministic, ID-sorted entries:

```json
{
  "count": 2,
  "mods": [
    { "id": "minecraft", "name": "Minecraft", "version": "1.21.1" },
    { "id": "neomcp", "name": "NeoMCP", "version": "0.1.0" }
  ]
}
```

Use it before an optional integration call. It reports loaded metadata; it
does not prove that a particular world or server resource is ready.

## Datapack and loot tables

| Tool | Arguments | Availability |
| --- | --- | --- |
| `list_loot_tables` | none | Active integrated server. |
| `get_loot_table` | `{ "loot_table_id": string }` | Active integrated server and loaded table. |
| `search_loot_tables` | `{ "item_id": string }` | Active integrated server. |

These tools read the integrated server's reloadable registry, not a jar or a
datapack directory directly. They therefore reflect the active world's loaded
resources and require a local single-player world.

## KubeJS

| Tool | Arguments | Availability |
| --- | --- | --- |
| `inject_kubejs_script` | `{ "script": string }` | KubeJS loaded and connected to a world. |

The script is written to the fixed file
`kubejs/server_scripts/neomcp_injected.js`, then the client sends `/reload`.
The tool is privileged and can be disabled in configuration.

## FTB Quests

All FTB Quests tools first check that `ftbquests` is loaded. FTB IDs are
unsigned 64-bit values and must be sent as exact hexadecimal strings. Do not
send them as JSON numbers because JavaScript/JSON number handling can round
large IDs.

| Tool | Arguments | Result |
| --- | --- | --- |
| `open_quest_gui` | `{ "id": string, "object_type": "chapter" \| "quest" }` | Opens the requested chapter or quest. |
| `get_chapter_layout` | `{ "chapter_id": string }` | Quest nodes, grid positions, sizes, and dependency links. |
| `export_chapter_canvas` | `{ "chapter_id": string, "save_png": boolean? }` | Full chapter image without sidebar/search/inventory UI. |

`save_png: true` stores the export below `screenshots/` using a generated file
name. The image renderer uses bounded off-screen allocation and reports an
error instead of allocating an unsafe canvas.

## Screenshots

| Tool | Arguments | Result |
| --- | --- | --- |
| `take_screenshot` | none | Main framebuffer saved under `screenshots/`. |
| `update_take_screenshot` | none | Alias with the same UI-inclusive behavior. |

When a `Screen` is active, the main framebuffer capture includes the rendered
UI overlay. The chapter canvas export is separate: it renders only the FTB
Quests chapter canvas and intentionally excludes normal surrounding UI.

## Client actions

| Tool | Arguments | Availability |
| --- | --- | --- |
| `look_at` | `{ "x": number, "y": number, "z": number, "duration_ticks": int? }` | Active local player; client-tick action. |
| `jump` | none | Active local player. |
| `move` | `{ "direction": "forward" \| "backward" \| "left" \| "right", "duration_ticks": int }` | Active local player; bounded key hold. |
| `interact` | `{ "target": "looked_at" \| "air", "hand": "main_hand" \| "off_hand"? }` | Active local player and client game mode. |
| `get_action_status` | `{ "action_id": int }` | Previously accepted action. |
| `cancel_action` | `{ "action_id": int }` | Currently running action. |

`look_at` and `move` return an action ID and advance on client ticks. The
maximum duration is `maxActionTicks`. There is no collision-aware navigation,
pathfinding, mouse automation, or Baritone integration.

## Recipes and viewers

| Tool | Arguments | Availability |
| --- | --- | --- |
| `recipe_capabilities` | none | Client JVM; reports canonical recipe and optional viewer state. |
| `find_recipes` | `{ "query": string?, "recipe_type": string?, "limit": int? }` | Connected client recipe manager. |
| `get_recipe` | `{ "recipe_id": string }` | Connected client and exact synchronized recipe. |
| `view_recipe` | `{ "recipe_id": string, "viewer": string?, "mode": "recipe" \| "uses"? }` | JEI 19.x runtime for the current implementation. |
| `get_recipe_tree` | `{ "item_id": string, "max_depth": int? }` | Connected client; JEI adds workstation data when active. |
| `get_item_usages` | `{ "item_id": string }` | Connected client; JEI adds category/catalyst matches when active. |
| `get_workstation_recipes` | `{ "machine_id": string }` | JEI 19.x runtime and a catalyst item. |
| `scan_for_loops` | `{ "item_id": string, "max_depth": int? }` | Connected client; depth is capped by `maxRecipeLoopDepth`. |
| `capture_recipe_card` | `{ "recipe_id": string, "save_png": boolean? }` | JEI 19.x runtime and render thread; optionally saves a generated PNG under `screenshots/`. |
| `dump_recipes` | `{ "mod_namespace": string?, "recipe_type": string?, "save_json": boolean? }` | Dumps synchronized recipe records, optionally filtered and persisted under `dumps/recipes/`. |
| `analyze_recipe_complexity` | `{ "item_id": string, "save_json": boolean? }` | Bounded namespace-diversity and sequential-depth metrics. |
| `check_recipe_cycles` | `{ "item_id": string, "save_json": boolean? }` | Bounded cycle detection from an item root. |
| `find_underutilized_items` | `{ "mod_namespace": string, "save_json": boolean? }` | Producible namespace items with zero canonical consumers. |

Canonical recipe data comes from the synchronized vanilla `RecipeManager`.
The JEI adapter uses `IRecipeManager` lookups, focus roles, catalyst
lookups, `IRecipesGui`, and `IRecipeLayoutDrawable`. `capture_recipe_card`
renders the card into an off-screen framebuffer and returns `image/png`
content; it does not capture the surrounding JEI sidebar. EMI and REI are
reported when detected, but their adapters are not implemented yet. See
[Recipe viewers and recipe graph](recipes.md).

Recipe analysis tools return JSON in `structuredContent`. Set `save_json: true`
to persist a copy below the active instance's `dumps/` directory. Responses then
include `saved_to_file`, `file_path`, `file_format`, and `file_size_bytes`.
Generated filenames are UUID-based. `dump_recipes` uses `dumps/recipes/`; the
three analysis tools use `dumps/analysis/`.

Set `save_png: true` when a durable image is needed for a report. NeoMCP
generates a `neomcp_recipe_<uuid>.png` filename under the active instance's
`screenshots/` directory and returns `saved_to_screenshots` plus
`screenshot_path`. Callers cannot provide an arbitrary output path.

## Dynamic KubeJS tools

Tools registered by KubeJS appear beside the built-in tools in `tools/list`.
They use the schema supplied by the script and execute on the server thread.
See [KubeJS bridge](kubejs.md) for registration and context details.
