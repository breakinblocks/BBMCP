package com.breakinblocks.neomcp.mcp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

public final class McpDynamicToolRegistry {
    public static final McpDynamicToolRegistry INSTANCE = new McpDynamicToolRegistry();

    private static final Set<String> RESERVED_NAMES = Set.of(
            "execute_command",
            "get_player_info",
            "get_block_entity_data",
            "inspect_item_components",
            "query_registry",
            "read_latest_logs",
            "inject_kubejs_script",
            "get_nearby_entities",
            "list_loot_tables",
            "get_loot_table",
            "search_loot_tables",
            "open_quest_gui",
            "get_chapter_layout",
            "export_chapter_canvas",
            "take_screenshot",
            "update_take_screenshot",
            "look_at",
            "jump",
            "move",
            "interact",
            "get_action_status",
            "cancel_action",
            "recipe_capabilities",
            "find_recipes",
            "get_recipe",
            "view_recipe",
            "get_recipe_tree",
            "get_item_usages",
            "get_workstation_recipes",
            "scan_for_loops",
            "capture_recipe_card");

    private final AtomicReference<Map<String, McpDynamicTool>> published =
            new AtomicReference<>(Map.of());
    private Map<String, McpDynamicTool> staging;
    private long generation;
    private long publishedGeneration;

    private McpDynamicToolRegistry() {
    }

    public synchronized long beginReload() {
        if (staging != null) {
            throw new IllegalStateException("A dynamic tool reload is already in progress");
        }
        generation++;
        staging = new LinkedHashMap<>();
        published.set(Map.of());
        publishedGeneration = generation;
        return generation;
    }

    public synchronized void register(
            String name,
            String description,
            JsonObject inputSchema,
            McpDynamicTool.Callback callback) {
        if (staging == null) {
            throw new IllegalStateException("No dynamic tool reload is in progress");
        }
        requireNonBlank(name, "name");
        requireNonBlank(description, "description");
        Objects.requireNonNull(inputSchema, "inputSchema");
        if (!inputSchema.isJsonObject()) {
            throw new IllegalArgumentException("inputSchema must be a JSON object");
        }
        Objects.requireNonNull(callback, "callback");
        if (RESERVED_NAMES.contains(name)) {
            throw new IllegalArgumentException("Tool name is reserved: " + name);
        }
        if (staging.containsKey(name)) {
            throw new IllegalArgumentException("Duplicate dynamic tool name: " + name);
        }
        staging.put(name, new McpDynamicTool(name, description, inputSchema, callback));
    }

    public synchronized void publishReload() {
        if (staging == null) {
            throw new IllegalStateException("No dynamic tool reload is in progress");
        }
        Map<String, McpDynamicTool> snapshot = new LinkedHashMap<>(staging);
        published.set(Collections.unmodifiableMap(snapshot));
        publishedGeneration = generation;
        staging = null;
    }

    public synchronized void abortReload() {
        if (staging == null) {
            throw new IllegalStateException("No dynamic tool reload is in progress");
        }
        generation++;
        staging = null;
        publishedGeneration = generation;
        published.set(Map.of());
    }

    public synchronized void clear() {
        generation++;
        staging = null;
        publishedGeneration = generation;
        published.set(Map.of());
    }

    public synchronized boolean isPublishedGeneration(long expectedGeneration) {
        return publishedGeneration == expectedGeneration;
    }

    public Map<String, McpDynamicTool> snapshot() {
        return published.get();
    }

    public JsonElement call(String name, JsonObject arguments) throws Exception {
        McpDynamicTool tool = published.get().get(name);
        if (tool == null) {
            throw new IllegalArgumentException("Unknown dynamic tool: " + name);
        }
        JsonElement result = tool.callback().execute(arguments);
        if (result == null) {
            throw new IllegalStateException("Dynamic tool callback returned null: " + name);
        }
        return result;
    }

    private static String requireNonBlank(String value, String parameterName) {
        Objects.requireNonNull(value, parameterName);
        if (value.isBlank()) {
            throw new IllegalArgumentException(parameterName + " must not be blank");
        }
        return value;
    }
}
