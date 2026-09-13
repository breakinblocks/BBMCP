# Troubleshooting

## The endpoint does not connect

Confirm that Minecraft is running and that NeoMCP reached client setup. Then
check the configured port in `config/neomcp-client.toml` and test it from the
same environment as the agent.

On Windows:

```powershell
Test-NetConnection localhost -Port 8080
```

If the port is occupied, change `port`, update the agent URL, and restart
Minecraft. NeoMCP binds only to `127.0.0.1`; it does not accept connections
from another machine.

## The agent sees no tools

Reconnect or refresh the MCP server after changing the client configuration.
Call `tools/list` directly and confirm that the URL includes `/mcp`, not just
the port root. `list_mods` should be available even when the client is at the
title screen.

If `execute_command` or `inject_kubejs_script` is missing, check
`allowCommandExecution` and `allowKubejsScriptInjection`. These settings are
privilege switches and remove the tools from discovery when disabled.

## World-dependent tools fail

Enter a single-player world before using player, entity, command, screenshot,
loot-table, or FTB Quests tools. Loot tables additionally require the active
integrated server's resources to be loaded.

The development client is configured to quick-play `New World`; if that save
does not exist, create it or change the quick-play argument in `build.gradle`.

## FTB Quests tools fail

Call `list_mods` and confirm the exact `ftbquests` mod ID is present. FTB
Quests IDs are unsigned 64-bit values. Send them as exact hexadecimal strings,
for example `"6F3CA3EA0F8276C0"`, never as JSON numbers.

For canvas exports, use `save_png: true` when a file is needed. The result is
written under the active instance's `screenshots` directory. A blank canvas is
usually a chapter-data or rendering-state problem; compare the structured
result from `get_chapter_layout` with the exported image and inspect the log.

## KubeJS tools fail

Confirm that `kubejs` appears in `list_mods`. The injected script is written to
`kubejs/server_scripts/neomcp_injected.js`, and `/reload` errors are visible in
`logs/latest.log` and KubeJS's logs. A registration callback must return a
JSON-compatible value and must guard optional `serverPlayer` and `minecraft`
context values.

After a successful reload, connected MCP clients receive
`notifications/tools/list_changed`; clients that do not subscribe to the SSE
stream can simply call `tools/list` again.

## Timeout or thread errors

Minecraft operations are scheduled on the client or integrated-server thread.
If the game is frozen, a screen is blocking the operation, or a reload is
taking longer than `actionTimeoutSeconds`, NeoMCP reports a timeout rather than
replaying the action. Inspect the final lines from `read_latest_logs` and
increase the timeout only when the operation is known to be safe.

For crashes, preserve the complete crash report and `logs/latest.log` before
restarting. The repository's crash triage workflow should be used to identify
the first implicated mod rather than assuming that the final stack-frame mod
caused the failure.
