package com.breakinblocks.bbmcp.kubejs;

import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Objects;

/**
 * Server-thread context supplied to a dynamically registered KubeJS tool.
 * Client-side values are optional so this type remains safe to load on a
 * dedicated server.
 */
public final class BbmcpToolContext {
    private final MinecraftServer server;
    private final ServerLevel level;
    private final ServerPlayer serverPlayer;
    private final Object minecraft;
    private final JsonObject arguments;

    public BbmcpToolContext(
            MinecraftServer server,
            ServerLevel level,
            ServerPlayer serverPlayer,
            Object minecraft,
            JsonObject arguments) {
        this.server = Objects.requireNonNull(server, "server");
        this.level = Objects.requireNonNull(level, "level");
        this.serverPlayer = serverPlayer;
        this.minecraft = minecraft;
        this.arguments = Objects.requireNonNull(arguments, "arguments").deepCopy();
    }

    public MinecraftServer getServer() {
        return server;
    }

    public ServerLevel getLevel() {
        return level;
    }

    public ServerPlayer getServerPlayer() {
        if (serverPlayer == null) {
            throw new IllegalStateException(
                    "The local server player is unavailable; this tool requires an active integrated client player");
        }
        return serverPlayer;
    }

    /**
     * Returns the live local client instance when this tool is running in an
     * integrated client. Client-thread-confined methods must not be called from
     * the server-thread KubeJS callback.
     */
    public Object getMinecraft() {
        if (minecraft == null) {
            throw new IllegalStateException(
                    "The Minecraft client instance is unavailable on a dedicated server");
        }
        return minecraft;
    }

    public boolean isServerPlayerAvailable() {
        return serverPlayer != null;
    }

    public boolean isMinecraftAvailable() {
        return minecraft != null;
    }

    public JsonObject getArguments() {
        return arguments.deepCopy();
    }
}
