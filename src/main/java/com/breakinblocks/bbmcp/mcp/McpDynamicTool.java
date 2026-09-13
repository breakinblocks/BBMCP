package com.breakinblocks.bbmcp.mcp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Objects;

public final class McpDynamicTool {
    private final String name;
    private final String description;
    private final JsonObject inputSchema;
    private final Callback callback;

    public McpDynamicTool(String name, String description, JsonObject inputSchema, Callback callback) {
        this.name = requireNonBlank(name, "name");
        this.description = requireNonBlank(description, "description");
        this.inputSchema = Objects.requireNonNull(inputSchema, "inputSchema").deepCopy();
        this.callback = Objects.requireNonNull(callback, "callback");
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public JsonObject inputSchema() {
        return inputSchema.deepCopy();
    }

    public Callback callback() {
        return callback;
    }

    private static String requireNonBlank(String value, String parameterName) {
        Objects.requireNonNull(value, parameterName);
        if (value.isBlank()) {
            throw new IllegalArgumentException(parameterName + " must not be blank");
        }
        return value;
    }

    @FunctionalInterface
    public interface Callback {
        JsonElement execute(JsonObject arguments) throws Exception;
    }
}
