# BBMCP

BBMCP is an internal development tool for Minecraft 1.21.1 on NeoForge. It
embeds a local HTTP Model Context Protocol (MCP) server inside the Minecraft
client JVM, allowing AI coding agents and automation tools to inspect and
interact with a running game.

Agents can query the local player's position and health, inspect blocks, block
entities, item data components, nearby entities, registries, loaded mods, loot
tables, and the latest game logs. They can also execute Minecraft commands,
capture screenshots, inspect FTB Quests chapters, export complete quest
canvases, and register custom MCP tools through KubeJS scripts.

FTB Quests and KubeJS are optional integrations. BBMCP detects whether they
are loaded and only enables their tools when the corresponding mod is
available. KubeJS tools are rebuilt automatically after server-script reloads,
so modpack developers can expose custom pack mechanics to their AI workflow.

## Installation

1. Install NeoForge for Minecraft 1.21.1.
2. Download the BBMCP `.jar` and place it in the instance's `mods` folder.
3. Launch the NeoForge client and load or create a single-player world.
4. Configure your MCP-capable coding agent to use:

   ```text
   http://localhost:8080/mcp
   ```

The HTTP server is enabled by default and binds only to `localhost`. The
generated configuration file is `config/bbmcp-client.toml`; use it to change
the port, request limits, timeouts, or privileged tool switches. Restart the
Minecraft client after changing the port or server settings.

## Usage

Register `bbmcp` as an HTTP MCP server in Codex, Claude Code, GitHub Copilot,
Antigravity/Agy, or another MCP-compatible client. Once Minecraft is running,
the agent can discover the available tools with MCP `tools/list` and invoke
them with `tools/call`.

Useful starting tools include:

- `list_mods` to see which compatibility integrations are available.
- `get_player_info` to confirm that the client is connected to a world.
- `read_latest_logs` to inspect recent startup or runtime errors.
- `get_player_info`, `get_nearby_entities`, and `get_block_entity_data` for
  game-state inspection.
- `execute_command` for controlled development commands. Commands are sent
  without the leading `/`.
- FTB Quests tools for chapter layouts, quest GUIs, screenshots, and full
  canvas exports when FTB Quests is installed.

The endpoint is intentionally local and has no authentication layer. Do not
forward the port, expose it to a network, or enable it in an environment where
untrusted local processes can access the machine. Command execution and
KubeJS script injection can change the world or execute pack logic, so disable
`allowCommandExecution` or `allowKubejsScriptInjection` when those capabilities
are not needed.

For complete agent configuration examples, tool schemas, KubeJS bridge details,
and compatibility guidance, see the [BBMCP wiki](wiki/README.md).
