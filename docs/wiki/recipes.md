# Recipe viewers and recipe graph

NeoMCP separates recipe data from recipe-viewer UI APIs. The canonical data
source is the recipe manager synchronized to the client connection. An
optional viewer adapter adds category, catalyst, screen-opening, and render
capabilities without making JEI, EMI, or REI a required runtime dependency.

## Capability discovery

Call `recipe_capabilities` before using a viewer-specific operation:

```json
{
  "name": "recipe_capabilities",
  "arguments": {}
}
```

The result reports `canonical_recipe_manager`, the detected viewer mod IDs,
and separate adapter flags. JEI is usable only after its runtime has finished
initializing. EMI and REI may be listed as detected, but their adapter flags
are currently `false`; requesting either one fails explicitly.

Recipe tools require a connected client because they read the server-sent
recipe manager. They are not available from the title screen or from a
dedicated-server process.

## Canonical recipe tools

| Tool | Arguments | Result |
| --- | --- | --- |
| `find_recipes` | `{ "query": string?, "recipe_type": string?, "limit": int? }` | Bounded search by recipe ID, result/ingredient item ID, or group. |
| `get_recipe` | `{ "recipe_id": string }` | Exact synchronized recipe ID, type, serializer, result, and ingredient views. |
| `get_recipe_tree` | `{ "item_id": string, "max_depth": int? }` | Reverse output graph. Each node includes recipe inputs, result amounts, ingredient choices, slot amounts, and workstation metadata when JEI is active. |
| `get_item_usages` | `{ "item_id": string }` | Recipes in which the item is an ingredient or catalyst, plus viewer categories when available. |
| `get_workstation_recipes` | `{ "machine_id": string }` | Recipes in categories for which JEI recognizes the item as a catalyst. |
| `scan_for_loops` | `{ "item_id": string, "max_depth": int? }` | Potential circular item/recipe chains. The request is capped at depth 5. |

The graph is deliberately bounded. `get_recipe_tree` expands every item
choice returned by a vanilla `Ingredient`; `amount` is the number of that
ingredient slot and the recipe result contains the output stack count. Custom
ingredient types, fluids, and mod-specific processing semantics are exposed
only when the active viewer provides them; the canonical graph does not
pretend that an unrecognized ingredient is an item.

`scan_for_loops` reports graph cycles, not proof of an exploit. A loop may be
intentional or may require energy, fluids, catalysts, or other conditions that
the generic recipe graph cannot evaluate.

## JEI 19.x adapter

The primary optional implementation targets JEI `19.25.0.322` for Minecraft
1.21.1. The integration has two pieces:

1. `NeoMcpJeiPlugin` is the `@JeiPlugin`/`IModPlugin` entry point. JEI supplies
   the live `IJeiRuntime` through `onRuntimeAvailable`.
2. `JeiPluginAdapter` uses that runtime's `IRecipeManager` for
   `createRecipeLookup`, `createRecipeCategoryLookup`, catalyst lookups,
   focus roles, `IRecipesGui`, and `IRecipeLayoutDrawable` rendering.

The adapter is loaded by name only after JEI is reported as loaded and its
runtime is present. This prevents a missing JEI jar from breaking a normal
NeoMCP client or a dedicated server. `IRecipeViewerAdapter` contains no JEI
types, so an EMI or REI implementation can be added without changing MCP
dispatch or the canonical catalog.

For a development environment, the API is compile-only:

```groovy
repositories {
    maven { url = uri('https://maven.blamejared.com') }
}

dependencies {
    compileOnly 'mezz.jei:jei-1.21.1-neoforge-api:19.25.0.322'
}
```

Install the matching JEI NeoForge mod in the actual development or pack
instance when using `view_recipe`, workstation lookups, or card capture.

## Opening and capturing a recipe card

`view_recipe` opens a synchronized recipe in JEI, while
`capture_recipe_card` renders the same recipe into a temporary off-screen
`TextureTarget`. It creates `GuiGraphics`, asks JEI for an
`IRecipeLayoutDrawable`, draws the recipe and overlays into the target, reads
the target with `Screenshot.takeScreenshot`, and returns standard MCP image
content (`mimeType: image/png`). The main window framebuffer is not used for
the card image, and the surrounding JEI sidebar/search/inventory UI is not
part of the result.

Example:

```json
{
  "name": "capture_recipe_card",
  "arguments": {
    "recipe_id": "minecraft:iron_ingot_from_smelting"
  }
}
```

The render target dimensions are controlled by `recipeCardWidth` and
`recipeCardHeight`. Allocation, projection, viewport, image, and temporary
file cleanup all happen on the client render thread. If the target cannot be
created or JEI cannot build a layout, the tool returns an error rather than
silently returning a blank or partial image.

Recipe viewer support is read-only in this phase. It does not transfer items,
click slots, or execute a recipe in a workstation.
