# NeoMCP Wiki

NeoMCP is a client-side development bridge for NeoForge 1.21.1. It starts a
loopback HTTP server inside the Minecraft client JVM and exposes the running
game through MCP JSON-RPC. The intended users are modpack developers, test
automation, and coding agents that need live access to a development instance.

## Start here

- [Installation and MCP client setup](installation.md)
- [Configuration](configuration.md)
- [Architecture and request lifecycle](architecture.md)
- [Tool reference](tools.md)
- [Recipe viewers and recipe graph](recipes.md)
- [KubeJS bridge](kubejs.md)
- [Movement and compatibility](movement-and-compatibility.md)
- [Troubleshooting](troubleshooting.md)

The [Phase 0 configuration audit](../phase0-global-mcp-configuration.md)
records the global client registrations used during development.

## Quick start

1. Install NeoMCP in a NeoForge 1.21.1 client.
2. Start Minecraft and enter a world. The development `runClient` task
   quick-plays the `New World` save.
3. Register an HTTP MCP server named `neomcp` at
   `http://localhost:8080/mcp` in the coding agent.
4. Ask the agent to call `list_mods` and `get_player_info` to verify the
   connection.

The port is configurable, but the bind address is deliberately fixed to
`127.0.0.1`. NeoMCP has no authentication layer; any process that can reach
the local port can call the enabled tools.

## Capability boundaries

The base server runs in the client JVM. Client-only observations such as the
local player, active screen, framebuffer, and FTB Quests UI therefore require
an active client. Server-side data tools additionally require an active
integrated server. The KubeJS bridge is safe to load on a dedicated server,
but client-specific context values are absent there.

FTB Quests and KubeJS are optional. NeoMCP checks their loaded-mod state before
using their APIs, so the base mod remains usable in a vanilla or differently
modded development instance.
