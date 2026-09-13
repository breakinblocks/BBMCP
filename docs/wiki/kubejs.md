# KubeJS bridge

KubeJS is an optional dependency. When it is loaded, BBMCP exposes the
`BbmcpEvents.register` server event and a wrapper for registering dynamic MCP
tools from JavaScript.

## Registering a tool

Put a registration in a KubeJS server script:

```js
BbmcpEvents.register(event => {
  event.registerTool(
    'current_dimension',
    'Return the active server dimension.',
    {
      type: 'object',
      additionalProperties: false
    },
    context => {
      return {
        dimension: String(context.level.dimension)
      }
    }
  )
})
```

`registerTool` accepts four values:

1. a unique tool `name`;
2. a human-readable `description`;
3. a JSON Schema object describing the arguments;
4. a JavaScript callback.

The callback must return a JSON-compatible value. Returning no value, an
unsupported Java object, or an invalid schema fails registration/reload early
so a broken tool cannot be published as if it were healthy.

## Callback context

The callback receives one `BbmcpToolContext` object. Rhino exposes its Java
bean getters as the usual JavaScript properties.

| Property | Type | Availability |
| --- | --- | --- |
| `server` | `MinecraftServer` | Always present while the integrated/server reload is active. |
| `level` | `ServerLevel` | Always present; this is the server overworld used as the stable context level. |
| `arguments` | JSON object | Always present; a defensive copy of the MCP call arguments. |
| `serverPlayer` | `ServerPlayer` | Optional; the local integrated-client player when connected. |
| `minecraft` | client handle | Optional; present only in an integrated client. |

Use the availability methods before reading optional values:

```js
BbmcpEvents.register(event => {
  event.registerTool('player_uuid', 'Return the local server player UUID.', {
    type: 'object'
  }, context => {
    if (!context.isServerPlayerAvailable()) {
      return { available: false, reason: 'No integrated client player' }
    }
    return {
      available: true,
      uuid: String(context.serverPlayer.uuid)
    }
  })
})
```

`serverPlayer` is not a client `LocalPlayer`. It is the server-side player
resolved from the integrated server and is safe to use from the callback's
server thread. The dimension can be derived from `level.dimension`; it is not
duplicated in the context.

`minecraft` is a live client object and is included only for integrated-client
workflows. It is not available on a dedicated server, and client-thread-
confined methods must not be called directly from the server-thread callback.
Use a built-in client tool or a future explicit client-thread bridge instead.
The getters throw when a value is unavailable; the availability methods are
the required guard.

## Reload lifecycle

BBMCP hooks KubeJS's server script load lifecycle. On each server-script
reload, including `/reload`, it:

1. opens a new registration generation;
2. clears the previously published dynamic tool set from publication;
3. fires `BbmcpEvents.register`;
4. publishes the new generation atomically if registration succeeds;
5. broadcasts `notifications/tools/list_changed` to connected MCP event
   streams.

If registration fails, the new generation is aborted and the error is raised
instead of silently publishing a partial tool set. A tool from an expired
generation cannot execute after a later reload.

## Script injection

When KubeJS is present, `inject_kubejs_script` writes the supplied JavaScript
to `kubejs/server_scripts/bbmcp_injected.js` and dispatches `/reload`. This is
intended for development iteration, not production content deployment. Keep
the script small enough for `maxKubejsScriptLength`, and inspect
`read_latest_logs` after a reload if registration fails.

## Dedicated-server safety

BBMCP's HTTP server is a client-side service. The KubeJS plugin itself is
side-safe and can be loaded with a dedicated server, but a dedicated server
does not have a `Minecraft` client or `LocalPlayer`. Code must not assume that
`minecraft` or `serverPlayer` exists just because KubeJS is loaded.
