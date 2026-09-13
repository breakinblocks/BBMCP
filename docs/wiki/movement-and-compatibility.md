# Movement and compatibility

## Primitive client actions

NeoMCP provides bounded client-tick actions for test automation. These are
input primitives, not a navigation system:

| Tool | Arguments | Behavior |
| --- | --- | --- |
| `look_at` | `{ "x": number, "y": number, "z": number, "duration_ticks": int? }` | Turns the local player toward a world position. Yaw and pitch are linearly interpolated over the requested ticks. |
| `jump` | none | Invokes one local-player jump on the client thread. |
| `move` | `{ "direction": "forward" \| "backward" \| "left" \| "right", "duration_ticks": int }` | Holds one vanilla movement key for a bounded number of client ticks. |
| `interact` | `{ "target": "looked_at" \| "air", "hand": "main_hand" \| "off_hand"? }` | Uses the selected hand on the current crosshair target or in the air. |
| `get_action_status` | `{ "action_id": int }` | Reads the status and elapsed ticks of an accepted asynchronous action. |
| `cancel_action` | `{ "action_id": int }` | Cancels the currently running action and releases any held movement key. |

`look_at` and `move` return an action ID immediately. The controller owns the
action state machine and advances it on client ticks. Starting another
action cancels the previous one. A movement action also stops if the player
disappears or a screen opens. All durations are bounded by `maxActionTicks`.

`look_at` uses the shortest wrapped yaw path, so a turn across the -180/180
boundary does not rotate almost a full circle. Pitch is clamped to the valid
player range. Coordinates must be finite and the action requires a connected
local player.

## What is intentionally not included

There is no `navigate_to`, collision-aware pathfinding, obstacle avoidance,
mouse automation, attack automation, or Baritone integration. Primitive
movement cannot understand doors, claims, hazards, or a target's route. Use
`execute_command` for deterministic setup such as teleporting a test player.

This boundary is deliberate: a future navigation adapter would need its own
versioned pathfinding API, chunk-loading policy, claim policy, cancellation
semantics, and safety limits. Baritone support is not planned for the current
NeoMCP scope.

## Recipe viewer compatibility

Recipe access follows the same adapter boundary. The canonical source is the
client-synchronised vanilla `RecipeManager`, so recipe search, exact recipe
serialization, bounded recipe trees, and loop scans work without a viewer.
JEI 19.x is an optional client adapter for category/catalyst lookups, opening
recipe screens, and off-screen recipe-card rendering. EMI and REI are detected
by `recipe_capabilities` so they can receive adapters later, but are not
implemented in this release.

See [Recipe viewers and recipe graph](recipes.md) for schemas and the exact
optional dependency behavior.

## Other compatibility priorities

`list_mods` is the discovery primitive for pack-specific compatibility. FTB
Quests and KubeJS are already isolated integrations. Further adapters should
be added only for a concrete workflow—for example Jade/WTHIT for richer
inspection or Create/Applied Energistics 2/Refined Storage for focused machine
or network state—not as required dependencies of the base mod.
