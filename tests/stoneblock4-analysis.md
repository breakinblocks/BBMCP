# FTB StoneBlock 4 progression and systems analysis

Audit date: 2026-09-13  
Instance: `C:\Games\Minecraft\Instances\FTB Stoneblock 4`  
Pack manifest: `.build.json` — FTB Stoneblock 4 `1.21.0`, Minecraft `1.21.1`, NeoForge `21.1.248`  
Live runtime: 441 loaded mods; player healthy in `minecraft:overworld`; JEI-backed synchronized recipe manager available.

## Executive assessment

StoneBlock 4 has a strong authored spine: the player starts with constrained resource acquisition, moves through the Unearther and World Engine, and then fans out into tech, magic, combat, resource generation, Vaults, Echoes, and five World Engine tiers. The quest graph is flexible and visibly communicates the intended route.

The main design risks are not a lack of content but excessive system density and verification gaps. Many chapters are very large, several routes overlap heavily, and the pack mixes custom gates with hundreds of mod-native recipes that are not necessarily part of the intended progression. The current NeoMCP adapter also fails on valid custom recipe types, so automated graph conclusions must be treated as incomplete.

## Progression structure

The live quest configuration uses `progression_mode: "flexible"` and three chapter groups:

| Group | Meaning | Representative chapters |
|---|---|---|
| `4B65568899D62A0C` | Echoes of History | Getting Started; World Engine Tiers 1–5 |
| `337473E1389D8A71` | Forgotten Knowledge | Exploration & Combat; Magic; Logistics; Power; Resource Generation |
| `1B747BDF0A1FE37A` | Forgotten Technology | Mekanism; Oritech; Technology; Draconic Evolution |

The primary authored route is:

1. Garden-of-Stone onboarding: dirt brushing, smashing, charcoal, bonsai, early food, and basic tools.
2. Early infrastructure: the Unearther, villager recruitment/breeding, chronon generation, and the first World Craft.
3. World Engine Tier 1 — Foundation: archives, Quartermaster/Machinist/Magician NPC paths, source creation, basic casings, infusing metals, stress, and the first auto-built upgrades.
4. Tier 2 — Horizon: Enchanter, Fabricator, Wayfinder, deep magic, heat transfer, Chroniton Glass, and Solaris.
5. Tier 3 — Solaris: Catalyst, Infernal, Light Bender, Enderium, Fortron, spirits, flux, advanced machinery, and Quantum Tunnel.
6. Tier 4 — Oblivion: Wyrmwright, Ancient, Twilight, Lich, Yeti, Hydra, Leviathan, Draconic Core, Prometheum, Abyssal Sacrifice, Euphonium, Resonant Void, and final Twilight Vault.
7. Tier 5 — Hyperion: Chaos, Radiance, neutronium, particle acceleration, antimatter, black holes, awakened Draconium, singularities, Infinity, and the final guidance/creative route.

Live chapter sizes and dependency counts:

| Chapter | Quests | Dependency-bearing quests |
|---|---:|---:|
| Getting Started | 38 | 37 |
| World Engine T1–T5 | 30 / 22 / 39 / 31 / 32 | 29 / 21 / 38 / 30 / 31 |
| Generating Resources | 49 | 35 |
| General Tech | 67 | 66 |
| Logistics & Automation | 13 | 0 |

This is a good shape for player choice: the World Engine provides a readable spine while resource-generation and general-tech chapters provide parallel optimization routes. The weakness is cognitive load: the full pack contains 21 chapter files, and the major chapters contain dozens to hundreds of quest/task records. A player can easily lose the distinction between “required for the spine,” “recommended support,” and “optional mod showcase.”

## Recipe interdependence

NeoMCP confirmed a canonical client-synchronized recipe manager with JEI. Reliable examples:

- Iron processing accepts vanilla raw iron and the pack-specific `ftbmaterials:iron_cluster` through `c:raw_materials/iron`.
- Create’s Mechanical Press costs one shaft, one Andesite Casing, and one iron block.
- Create Andesite Alloy has both iron-nugget and zinc-nugget routes, in shaped crafting and Create mixing. This is a healthy alternate route, but it also means balancing one route can leave another route as a bypass.
- Netherite ingot has a pack-specific 3×3 conversion from nine `ftbmaterials:netherite_nugget` items.
- Avaritia has a deliberate reversible compression pair: nine Infinity Ingots to one Infinity Block, and one Infinity Block back to nine Infinity Ingots.
- Mekanism’s elite-control-circuit and World Engine recipes are present as custom machine/processing categories, but some serialized ingredient payloads are omitted by the adapter.

The recipe surface is extremely broad. Examples of usage counts from JEI:

| Item | Reported usage count | Interpretation |
|---|---:|---|
| Diamond | 385 | A central cross-mod currency/material; likely a major shared bottleneck |
| Ender Pearl | 241 | Heavily shared by storage, transport, teleportation, magic, and tech |
| Blaze Rod | 99 | Cross-links fuel, magic, generators, and upgrades |
| Nether Star | 55 | Mostly high-tier utility, automation, and combat infrastructure |
| Echo Shard | 28 | Strongly tied to Echo/late progression and several mod integrations |
| Wilden Spike / Wing | 24 / 23 | Meaningful Ars Nouveau progression dependencies |
| Runic Tablet | 38 | Useful relic/weapon integration, but primarily combat content |
| ComputerCraft Treasure Disk | 4 | Potentially underused unless the four uses are intentionally niche |
| Ars Nouveau Ritual Challenge | 0 | Looted/awarded item with no JEI use reported |

Potential underuse/dead-content candidates are `ars_nouveau:ritual_challenge`, horse armor appearing in Stone loot without reported recipe usage, and ComputerCraft’s treasure disk with only four reported uses. These are proxy signals, not player telemetry: actual usage could come from quests, tags, trades, loot functions, or non-JEI mechanics.

## Loops and compression

The explicit Avaritia block/ingot reversal is lossless and intentional. FTB compression families also deliberately round-trip materials such as charcoal, copper nuggets, netherite nuggets, ruby, sapphire, sulfur, fluorite, monazite, and coal-coke products.

The compressed Stone/Gravel/Dust families are more concerning from a data-quality perspective. Their normal 3×3 compression/decompression routes are expected, but the JEI synchronization exposes multiple hammer recipes with `minecraft:air` and count `0` results. These may be disabled placeholders, but they should not appear as valid craftable routes.

In the pre-fix run, NeoMCP’s automatic `get_recipe_tree` and `scan_for_loops` failed with `NullPointerException: Missing registered recipe type key` for valid items. That registry-key failure is fixed, but complete traversal still depends on JEI category coverage and custom-machine metadata; no claim of “no other loops” is justified from the viewer graph alone.

## Loot tables

The live runtime exposes 18,072 loot tables across 153 namespaces. The largest contributors are mostly decorative block families (`rechiseled`, `chisel`, `betterblockz`), so raw table count overstates progression complexity. FTB contributes 165 tables, including Stone, Stone Aqua, Nether 0, Vault, treasure, and lootbox families.

Observed Stone loot design:

- Common lootbox: early logs, saplings, bamboo, moss, food, torches, charcoal, nuggets, flint, and copper. It can roll uncommon loot at 10%, spawn-egg content at 5%, and has extremely low-probability Simply Swords pools.
- Rare lootbox: diamonds are weight 5 in a weight-56 pool (about 8.9% selection before quantity functions); Epic progression is gated at 10%; diamond equipment is behind a 50% pool.
- Epic lootbox: adds ender pearls, amethyst, redstone, lapis, slime, rare combat items, and a 10% legendary-items route. One inspected pool has rolls but zero entries, which is a concrete dead/suspicious pool.
- Stone treasure: diamond and emerald are low-single-digit percentage selections; dimensional shard gems, totems, QIO drives, Netherite templates, and Gate Pearls are rare jackpot items.
- Nether treasure: reinforces the intended Nether loop with quartz, wart, glowstone, gold, netherrack, soul sand, basalt, blaze rods, Netherite scrap, Gate Pearls, functional-storage upgrades, and mod-specific magic/tech materials.

The good part is that loot accelerates constrained early progression and gives exploration/combat a real economic role. The bad part is reward dilution: large pools contain many unrelated weapon variants and repeated ultra-rare Simply Swords pools. The player may receive a visually exciting item that has no bearing on the current gate, while genuinely important materials remain probability-gated.

## Concrete defects and risks

1. Fixed in the rebuilt jar: bulk `search_loot_tables` now skips malformed Iron’s Spellbooks tables and reports them explicitly; direct table lookup remains fail-fast.
2. Partially fixed in the rebuilt jar: missing recipe-type registry keys no longer abort catalog/tree construction, but `get_recipe_tree minecraft:iron_ingot` still stops when JEI cannot resolve the valid `create:splashing` category.
3. Several recipes report empty ingredients or `minecraft:air`/zero results through the adapter. They must be classified as unknown/disabled, never as free recipes.
4. Workstation counts are easy to misread: `get_workstation_recipes` returns JEI category association, not proof that every returned recipe consumes the named workstation item.
5. Quest runtime and archived fixture data are different. The old test fixture chapter `6F3CA3EA0F8276C0` (“From Wood to the End”) is not the active StoneBlock 4 chapter and must not be used for pack conclusions.
6. The pack’s authored quest spine is clear, but support chapters such as Logistics & Automation have no dependency links, making it easy to access powerful logistics tools before the intended World Engine moment.
7. The combination of 441 mods, many parallel resource generators, and high cross-mod usage counts creates bypass risk. Any balance change needs a recipe/quest/loot/tag impact audit.

## Recommendations

- Fix recipe serialization/graph traversal first, preserving hard failures for malformed records and returning explicit `unsupported_type` records for valid-but-unhandled serializers.
- Isolate or repair the malformed Iron’s Spellbooks loot table so reverse loot searches can complete.
- Add a static audit that flags zero-result recipes, empty-entry loot pools, recipes with missing ingredients, duplicate outputs with near-identical inputs, and reversible compression pairs.
- Mark quest chapters visually as `required spine`, `recommended infrastructure`, or `optional showcase`; keep the flexible model but make the choice cost legible.
- Review early access to Logistics & Automation and resource generators against World Engine gates. The best routes should be powerful without making the World Engine irrelevant.
- Revisit low/no-usage candidates only after checking quest tasks, custom machine recipes, trades, tags, and KubeJS event code.
- Reduce repeated low-probability weapon pools or consolidate them into a themed combat reward table so lootbox rolls communicate progression value better.

## Validation and changes made

- NeoMCP endpoint fixture report: 44 checks passed, 0 failed.
- Live read-only calls used: player info, mod list, registry queries, recipe capabilities, recipe search/get/usage/workstation views, loot listing/get, quest layouts, screenshots, and logs.
- A diagnostic KubeJS tool named `recipe_namespace_census` was added to [neomcp_injected.js](C:/Users/TheonlyTazz/Documents/Github/BreakinBlocks/NeoMCP/run/kubejs/server_scripts/neomcp_injected.js). It only enumerates registered recipe IDs/counts and does not alter recipes or world data.
- Reload was performed with the supported command `/kubejs reload server-scripts`; logs report `Loaded 190/190 KubeJS server scripts ... with 0 errors and 0 warnings`.
- No database access, migrations, seeders, SQL, tinker, or tests were run.

## Post-fix verification and deeper recipe audit

The rebuilt jar was verified against the restarted live client. The repaired loot search now completes for `minecraft:iron_ingot`: it reports 75 matching tables and three explicit skips. The skipped records are `irons_spellbooks:chests/citadel/citadel_vault`, `irons_spellbooks:entities/dead_king_ominous`, and `irons_spellbooks:test/scroll_gen_magic_missile`; all share the same invalid `irons_spellbooks:schools` holder. This is the desired bulk-search behavior: useful results remain available and the incompleteness is visible.

The recipe catalog now preserves recipes whose `RecipeType` has no built-in registry key. However, end-to-end JEI enrichment exposes a second, independent limitation: `get_recipe_tree minecraft:iron_ingot` fails with `JEI recipe type is unavailable: create:splashing`. The type is registered and its recipes are valid, but JEI has no category mapping for it in this runtime. A future adapter change should treat an unavailable JEI category like unavailable workstation metadata and preserve the graph with a diagnostic.

Useful graph evidence:

- `get_recipe_tree ftbmaterials:iron_plate --max-depth 2` completes, but visits 382 nodes even at shallow depth. The output shows the intended three-iron-ingot-plus-Immersive-Engineering-hammer plate route, while also exposing ProjectE conversion, Hostile Neural Networks, chicken-roost, furnace, Create, Malum, Productive Metalworks, and Immersive Engineering alternatives. This is strong evidence that the pack's authored progression is not the same thing as the complete reachable recipe graph.
- `scan_for_loops ftbmaterials:iron_dust --max-depth 5` finds seven loops, including the direct `iron_dust ↔ iron_tiny_dust` pair and several ProjectE/metal-compression paths. These are not all equally harmful: the dust round-trip is a likely recipe-design defect or intentional compression, whereas the ProjectE and storage conversions are system-level bypasses that must be gated by progression if they can reach early materials.
- `scan_for_loops minecraft:iron_ingot --max-depth 5` reaches the 5,000-node budget and reports 64 loops. The most important signal is not the raw count but the repeated families: ProjectE iron/gold conversion, Occultism gambler trades, storage-block compression, tool/armor smelting, and decorative-material recycling. The graph must therefore rank loops by access stage and value flow instead of treating every cycle as equivalent.
- Direct recipe inspection shows `ftb:create/crushing/ores/iron` converting an iron ore tag to two crushed raw iron, followed by `ftb:create/washing/crushed/iron` converting one crushed raw iron to nine iron nuggets. The parallel dust path is `ftb:create/crushing/ingots/iron` → `ftbmaterials:iron_dust` → FTB smelting/blasting back to an iron ingot. This gives the player multiple conversion economies before the higher-tier World Engine gates.
- Direct inspection of `ftb:circuit_fabricator/calculation_processor`, `logic_processor`, and `engineering_processor` returns empty ingredients and a `minecraft:air`/zero result because Custom Machinery recipes encode their real machine IO outside the vanilla recipe fields. These must be read from the machine recipe definitions, not interpreted as free or dead recipes.

### Progression lessons extracted

StoneBlock 4 is a strong example of authored progression because it combines a visible mandatory spine (World Engine tiers) with optional systems that provide alternate economic routes. The critical design principle is not merely “add many recipes”; it is to make every alternate route legible in terms of when it becomes available, what bottleneck it consumes, and whether it can bypass a spine gate.

For future pack audits, classify every recipe edge into four separate facts: acquisition source, processing method, gating condition, and output value. Acquisition source distinguishes generated, quest-rewarded, loot, exploration, combat, trade, and crafted items. Processing method distinguishes vanilla crafting, mod machines, multiblocks, rituals, fluids, and custom scripts. Gating condition records quest completion, dimension, structure, tool, power, source, fluid, or progression-stage requirements. Output value records quantity, chance, by-products, durability/components, and whether the result is reversible.

The most useful progression metric is the earliest reachable stage of an item, not its number of recipes. A recipe is a bypass risk when it reaches a spine-gated item through a cheaper stage, a reward/loot path with no meaningful gate, a reversible conversion, or a machine whose requirements are omitted from the viewer representation. A recipe is healthy redundancy when it becomes available at the same or later stage and trades a different resource, power source, or play style for comparable value.
