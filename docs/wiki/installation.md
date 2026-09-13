# Installation and MCP client setup

## Minecraft installation

NeoMCP targets Minecraft `1.21.1`, NeoForge `21.1.x`, and Java 21.

For a packaged installation:

1. Install a NeoForge 1.21.1 client instance.
2. Put the NeoMCP jar in that instance's `mods` directory.
3. Optionally install FTB Quests, KubeJS, and JEI to enable their
   integrations. JEI is required for viewer GUI, catalyst, and recipe-card
   operations; the canonical recipe tools do not require a viewer.
4. Launch the client and enter a single-player world.

For this repository's development instance:

```powershell
.\gradlew.bat build
.\gradlew.bat runClient
```

The development `runClient` configuration quick-plays the save named
`New World`. Change the `--quickPlaySingleplayer` argument in `build.gradle`
if another test save should be used.

NeoMCP starts after client setup and, by default, serves MCP at:

```text
http://localhost:8080/mcp
```

Verify the endpoint from the same environment as the coding agent:

```powershell
$body = '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}'
Invoke-RestMethod -Uri http://localhost:8080/mcp -Method Post `
  -ContentType 'application/json' -Body $body
```

## Agent registration

The server name should be `neomcp`. Replace `8080` everywhere if the port is
changed in `config/neomcp-client.toml`.

### Codex

Add this entry to the user TOML file at
`%USERPROFILE%\.codex\config.toml` on Windows:

```toml
[mcp_servers.neomcp]
url = "http://localhost:8080/mcp"
```

Restart Codex or reload its MCP configuration after editing the file.

### Claude Code

The user-scope command is:

```text
claude mcp add --transport http --scope user neomcp http://localhost:8080/mcp
```

On Windows, the user configuration is normally stored in
`%USERPROFILE%\.claude.json`. A native WSL installation uses
`/home/<user>/.claude.json` instead.

### GitHub Copilot CLI

Use the Copilot CLI command:

```text
copilot mcp add --transport http neomcp http://localhost:8080/mcp
```

The Windows user configuration is normally
`%USERPROFILE%\.copilot\mcp-config.json`. The equivalent entry is:

```json
{
  "mcpServers": {
    "neomcp": {
      "type": "http",
      "url": "http://localhost:8080/mcp",
      "tools": ["*"]
    }
  }
}
```

Merge the `neomcp` property into an existing `mcpServers` object instead of
replacing other servers.

### GitHub Copilot in VS Code

Add the server to the user MCP file at
`%APPDATA%\Code\User\mcp.json`:

```json
{
  "servers": {
    "neomcp": {
      "type": "http",
      "url": "http://localhost:8080/mcp"
    }
  }
}
```

The exact VS Code file can differ for an alternate product profile. Use the
MCP configuration file opened by the editor's MCP command when in doubt.

### Agy / Antigravity

The installed Agy configuration format uses
`%USERPROFILE%\.gemini\config\mcp_config.json`:

```json
{
  "mcpServers": {
    "neomcp": {
      "disabled": false,
      "serverUrl": "http://localhost:8080/mcp"
    }
  }
}
```

This `serverUrl` form is specific to the Agy/Antigravity configuration found
on the development machine. Other Gemini-based clients may use a different
file and the standard `url` or `httpUrl` property; follow that client's own
schema.

## Windows and WSL

The Minecraft process and the agent must be able to reach the same loopback
endpoint. Common user configuration locations are:

| Client | Windows | Native WSL/Linux |
| --- | --- | --- |
| Codex | `%USERPROFILE%\.codex\config.toml` | `~/.codex/config.toml` if a native Linux install is used |
| Claude Code | `%USERPROFILE%\.claude.json` | `/home/<user>/.claude.json` |
| Copilot CLI | `%USERPROFILE%\.copilot\mcp-config.json` | `~/.copilot/mcp-config.json` if installed natively |
| Agy | `%USERPROFILE%\.gemini\config\mcp_config.json` | Depends on the Linux installation |

Some WSL commands resolve to the Windows executable under `/mnt/c`, in which
case the Windows configuration is the active one. Do not create a second
configuration until the command's origin has been checked.

Test connectivity from the agent's environment. If Minecraft runs on Windows
and a native WSL process cannot reach `localhost`, use the Windows-host
connectivity instructions for that WSL networking mode; do not change NeoMCP
to bind a public interface. A remote bind would require authentication that
NeoMCP does not provide.

## Optional integrations

FTB Quests and KubeJS are optional dependencies. Their absence is expected:

- Without FTB Quests, the FTB GUI, layout, and canvas tools are omitted or
  return a clear unavailable error.
- Without KubeJS, script injection and dynamic KubeJS tools are unavailable.
- Without JEI, synchronized vanilla recipe search, exact recipe data, recipe
  trees, usages, and loop scans remain available; JEI GUI/catalyst/card tools
  are unavailable. EMI and REI are currently discovery-only.
- `list_mods` reports the exact loaded mod set so an agent can discover which
  compatibility paths are relevant before calling an optional tool.
