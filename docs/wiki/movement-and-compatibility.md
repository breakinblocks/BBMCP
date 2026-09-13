# Movement and compatibility roadmap

## What movement can do today?

NeoMCP does not currently simulate physical keyboard or mouse movement. An
agent can still move a development player with `execute_command`, for example
by sending `tp @s 100 70 -20` without the leading slash. This is useful for
test setup, but it is teleportation rather than navigation: it does not test
collision, pathfinding, doors, jumps, hazards, or player input.

There are several possible levels of movement support:

| Approach | Strength | Limitation |
| --- | --- | --- |
| Server commands such as `tp` | Exact and simple for test setup. | Bypasses the route and normal player controls. |
| Raw key/mouse simulation | Exercises client input. | Fragile, timing-sensitive, and unaware of obstacles. |
| High-level actions (`look_at`, `jump`, `interact`) | Useful building blocks for QA. | Needs a client-tick state machine and careful stop conditions. |
| Pathfinding (`navigate_to`) | Can travel to a target while respecting terrain. | Requires a pathfinder, chunk/claim policy, and asynchronous job control. |
| Optional pathfinder integration | Reuses a mature navigation implementation. | Must remain optional and version-compatible with the pack. |

## Recommended navigation design

The safest useful API is an asynchronous navigation job rather than a blocking
HTTP request:

```text
navigate_to(target) ──► job id
       │
       ├── get_navigation_status(job id)
       └── cancel_navigation(job id)
```

An implementation should run from client ticks and stop when the player dies,
disconnects, opens a blocking screen, changes dimension, reaches the target,
or encounters a pathing failure. It should report the target dimension,
current position, distance remaining, current action, and failure reason. It
must never block the HTTP worker while waiting for a route or movement.

The first navigation compatibility target should be a compatible Baritone
installation when one is present. NeoMCP should detect it through `list_mods`
and load an optional adapter, rather than adding Baritone as a required
dependency. A built-in fallback pathfinder can be considered later if the
target modpacks do not share a stable navigation library.

## Compatibility priorities

NeoMCP should prefer small capability adapters over hard-coding every mod in
the base tool executor. `list_mods` is the discovery primitive that lets an
agent choose the adapters actually available in a pack.

### Highest-value candidates

1. **Baritone** — pathfinding and movement jobs. This is the most direct way to
   support “navigate to this block/entity” without writing a second pathfinder.
2. **FTB Teams and FTB Chunks** — team identity, claims, and protected areas.
   Navigation and interaction tools should be claim-aware where those mods
   are installed.
3. **JEI, EMI, or REI** — item, recipe, and usage lookup. An adapter should be
   selected for the recipe viewer present instead of depending on all three.
4. **Jade or WTHIT** — server-authoritative block/entity details when the pack
   uses a display provider that contains information not available from the
   base inspection tools.
5. **KubeJS** — already supported as the extensibility layer for pack-specific
   mechanics and custom tools.

FTB Quests remains the strongest QA-specific integration because it provides a
structured progression graph and a visual canvas. FTB Library is useful as a
shared dependency in the FTB ecosystem, but it does not by itself need a
large user-facing tool surface.

### Add only when a workflow needs it

Create, Applied Energistics 2, Refined Storage, Sophisticated Backpacks, and
similar content mods are valuable targets for focused adapters such as
machine state, network contents, or storage inspection. They should be added
after a concrete test workflow is identified; broad compatibility code would
increase version coupling without improving every development session.

## Suggested future tool families

- `get_capabilities`: report optional integrations and their supported actions.
- `navigate_to`, `get_navigation_status`, and `cancel_navigation`.
- `look_at`, `interact`, and `attack` as explicit, bounded client actions.
- `find_blocks` and `find_entities` with bounded searches.
- `get_recipe` / `get_item_usage` through the installed recipe-viewer adapter.
- claim-aware `can_interact` checks before movement or block actions.

Every action tool should have explicit world/client preconditions and a
bounded timeout. Read-only inspection and capability discovery should remain
usable even when no world is loaded.
