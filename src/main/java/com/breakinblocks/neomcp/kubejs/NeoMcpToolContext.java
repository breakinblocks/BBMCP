package com.breakinblocks.neomcp.kubejs;

import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.Objects;

public final class NeoMcpToolContext {
    private final MinecraftServer server;
    private final ServerLevel level;
    private final JsonObject arguments;

    public NeoMcpToolContext(MinecraftServer server, ServerLevel level, JsonObject arguments) {
        this.server = Objects.requireNonNull(server, "server");
        this.level = Objects.requireNonNull(level, "level");
        this.arguments = Objects.requireNonNull(arguments, "arguments").deepCopy();
    }

    public MinecraftServer getServer() {
        return server;
    }

    public ServerLevel getLevel() {
        return level;
    }

    public JsonObject getArguments() {
        return arguments.deepCopy();
    }
}
