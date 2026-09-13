package com.breakinblocks.bbmcp.kubejs;

import com.breakinblocks.bbmcp.config.BbmcpConfig;
import com.breakinblocks.bbmcp.mcp.McpDynamicToolRegistry;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.latvian.mods.kubejs.server.ServerKubeEvent;
import dev.latvian.mods.kubejs.script.KubeJSContextFactory;
import dev.latvian.mods.kubejs.util.JsonSerializable;
import dev.latvian.mods.kubejs.util.JsonUtils;
import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.Function;
import dev.latvian.mods.rhino.Scriptable;
import dev.latvian.mods.rhino.Undefined;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class BbmcpToolRegistrationEvent extends ServerKubeEvent {
    private static final Semaphore CALLBACK_PERMIT = new Semaphore(1, true);
    private final ServerLevel level;
    private final KubeJSContextFactory contextFactory;
    private final McpDynamicToolRegistry registry;
    private final long generation;
    private Throwable registrationFailure;

    public BbmcpToolRegistrationEvent(
            MinecraftServer server,
            ServerLevel level,
            KubeJSContextFactory contextFactory,
            McpDynamicToolRegistry registry,
            long generation) {
        super(Objects.requireNonNull(server, "server"));
        this.level = Objects.requireNonNull(level, "level");
        this.contextFactory = Objects.requireNonNull(contextFactory, "contextFactory");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.generation = generation;
    }

    public ServerLevel getLevel() {
        return level;
    }

    public void registerTool(String name, String description, Object schema, Function callback) {
        Objects.requireNonNull(callback, "callback");
        Scriptable scope = callback.getParentScope();
        if (scope == null) {
            throw new IllegalArgumentException("callback parent scope must not be null");
        }

        Context context = contextFactory.enter();
        JsonElement converted = JsonUtils.of(context, schema);
        if (converted == null || !converted.isJsonObject()) {
            throw new IllegalArgumentException("schema must be a JSON object");
        }
        JsonObject inputSchema = converted.getAsJsonObject().deepCopy();

        MinecraftServer server = getServer();
        registry.register(name, description, inputSchema, arguments -> executeOnServerThread(
                server, callback, scope, arguments));
    }

    void recordFailure(Throwable throwable) {
        if (registrationFailure == null) {
            registrationFailure = Objects.requireNonNull(throwable, "throwable");
        }
    }

    void throwIfFailed() {
        if (registrationFailure != null) {
            throw new IllegalStateException("BBMCP KubeJS tool registration failed", registrationFailure);
        }
    }

    private JsonElement executeOnServerThread(
            MinecraftServer server,
            Function callback,
            Scriptable scope,
            JsonObject arguments) throws Exception {
        ensureInvocationIsCurrent(server);
        if (server.isSameThread()) {
            return invoke(server, callback, scope, arguments);
        }
        if (!CALLBACK_PERMIT.tryAcquire()) {
            throw new IllegalStateException("Another KubeJS tool callback is already in progress");
        }

        AtomicReference<CallbackExecutionState> state =
                new AtomicReference<>(CallbackExecutionState.QUEUED);
        AtomicBoolean permitReleased = new AtomicBoolean();
        CompletableFuture<JsonElement> future = new CompletableFuture<>();
        try {
            server.execute(() -> {
                try {
                    if (!state.compareAndSet(CallbackExecutionState.QUEUED, CallbackExecutionState.RUNNING)) {
                            return;
                        }
                        try {
                        JsonElement value = invoke(server, callback, scope, arguments);
                        state.set(CallbackExecutionState.COMPLETED);
                        releasePermit(permitReleased);
                        future.complete(value);
                    } catch (Throwable throwable) {
                        state.set(CallbackExecutionState.COMPLETED);
                        releasePermit(permitReleased);
                        future.completeExceptionally(throwable);
                    }
                } finally {
                    releasePermit(permitReleased);
                }
            });
        } catch (RuntimeException exception) {
            releasePermit(permitReleased);
            throw exception;
        }
        try {
            return future.get(BbmcpConfig.actionTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException exception) {
            if (state.compareAndSet(CallbackExecutionState.QUEUED, CallbackExecutionState.CANCELLED)) {
                releasePermit(permitReleased);
                throw new TimeoutException("Timed out before the KubeJS tool callback started");
            }
            throw new TimeoutException(
                    "Timed out after the KubeJS tool callback started; its outcome is unknown");
        } catch (InterruptedException exception) {
            boolean cancelled = state.compareAndSet(
                    CallbackExecutionState.QUEUED,
                    CallbackExecutionState.CANCELLED);
            if (cancelled) {
                releasePermit(permitReleased);
            }
            Thread.currentThread().interrupt();
            if (cancelled) {
                throw new IllegalStateException(
                        "Interrupted before the KubeJS tool callback started", exception);
            }
            throw new IllegalStateException(
                    "Interrupted after the KubeJS tool callback started; its outcome is unknown", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Exception checkedException) {
                throw checkedException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("KubeJS tool callback failed", cause);
        }
    }

    private JsonElement invoke(
            MinecraftServer server,
            Function callback,
            Scriptable scope,
            JsonObject arguments) throws Exception {
        ensureInvocationIsCurrent(server);
        ServerLevel level = server.overworld();
        if (level == null) {
            throw new IllegalStateException("The active server has no overworld");
        }
        BbmcpClientBridge.ClientValues clientValues = BbmcpClientBridge.capture(server);
        Context context = contextFactory.enter();
        Object result = context.callSync(
                callback,
                scope,
                scope,
                new Object[]{new BbmcpToolContext(
                        server,
                        level,
                        clientValues.serverPlayer(),
                        clientValues.minecraft(),
                        arguments)});
        if (result == null || Undefined.isUndefined(result)) {
            throw new IllegalStateException("KubeJS tool callback returned no value");
        }
        if (!isJsonCompatible(result)) {
            throw new IllegalStateException(
                    "KubeJS tool callback returned an unsupported value: "
                            + result.getClass().getName());
        }
        JsonElement converted = JsonUtils.of(context, result);
        if (converted == null) {
            throw new IllegalStateException("KubeJS tool callback returned null");
        }
        return converted.deepCopy();
    }

    private void ensureInvocationIsCurrent(MinecraftServer server) {
        if (!registry.isPublishedGeneration(generation)) {
            throw new IllegalStateException("KubeJS tool belongs to an expired script reload");
        }
        if (ServerLifecycleHooks.getCurrentServer() != server) {
            throw new IllegalStateException("KubeJS tool belongs to an inactive server");
        }
    }

    private boolean isJsonCompatible(Object value) {
        return value instanceof JsonElement
                || value instanceof JsonSerializable
                || value instanceof CharSequence
                || value instanceof Boolean
                || value instanceof Number
                || value instanceof Character
                || value instanceof Map<?, ?>
                || value instanceof Collection<?>;
    }

    private enum CallbackExecutionState {
        QUEUED,
        RUNNING,
        COMPLETED,
        CANCELLED
    }

    private void releasePermit(AtomicBoolean released) {
        if (released.compareAndSet(false, true)) {
            CALLBACK_PERMIT.release();
        }
    }
}
