package com.breakinblocks.neomcp.mcp;

import com.breakinblocks.neomcp.config.NeoMcpConfig;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class McpHttpServer implements AutoCloseable {
    private static final JsonElement JSON_NULL = JsonNull.INSTANCE;
    private static final String JSON_CONTENT_TYPE = "application/json; charset=utf-8";
    private final Gson gson = new Gson();
    private final McpToolExecutor toolExecutor;
    private final McpDynamicToolRegistry dynamicToolRegistry;
    private final CopyOnWriteArrayList<SseClient> sseClients = new CopyOnWriteArrayList<>();
    private final AtomicInteger sseConnectionCount = new AtomicInteger();
    private static volatile McpHttpServer activeServer;
    private HttpServer server;
    private ExecutorService requestExecutor;
    private ExecutorService sseExecutor;

    public McpHttpServer(McpToolExecutor toolExecutor) {
        this.toolExecutor = Objects.requireNonNull(toolExecutor, "toolExecutor");
        this.dynamicToolRegistry = McpDynamicToolRegistry.INSTANCE;
    }

    public synchronized void start() throws IOException {
        if (server != null || requestExecutor != null) {
            throw new IllegalStateException("MCP server is already running");
        }
        int port = NeoMcpConfig.port();
        int requestThreadCount = NeoMcpConfig.requestThreadCount();
        int requestQueueCapacity = NeoMcpConfig.requestQueueCapacity();
        int sseConnectionLimit = NeoMcpConfig.sseConnectionLimit();
        int sseQueueCapacity = NeoMcpConfig.sseQueueCapacity();
        HttpServer newServer = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        ExecutorService newRequestExecutor = new ThreadPoolExecutor(
                requestThreadCount,
                requestThreadCount,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(requestQueueCapacity),
                namedDaemonThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy());
        ExecutorService newSseExecutor = new ThreadPoolExecutor(
                sseConnectionLimit,
                sseConnectionLimit,
                0L,
                TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(),
                namedDaemonThreadFactory("NeoMCP-sse-"),
                new ThreadPoolExecutor.AbortPolicy());
        try {
            newServer.createContext("/mcp", this::handleRequest);
            newServer.setExecutor(newRequestExecutor);
            sseExecutor = newSseExecutor;
            newServer.start();
            synchronized (McpHttpServer.class) {
                if (activeServer != null) {
                    newServer.stop(0);
                    newRequestExecutor.shutdownNow();
                    newSseExecutor.shutdownNow();
                    sseExecutor = null;
                    throw new IllegalStateException("Another NeoMCP HTTP server is already active");
                }
                activeServer = this;
            }
            server = newServer;
            requestExecutor = newRequestExecutor;
        } catch (RuntimeException exception) {
            sseExecutor = null;
            newServer.stop(0);
            newRequestExecutor.shutdownNow();
            newSseExecutor.shutdownNow();
            throw exception;
        }
    }

    @Override
    public synchronized void close() {
        HttpServer activeServer = server;
        ExecutorService activeRequestExecutor = requestExecutor;
        ExecutorService activeSseExecutor = sseExecutor;
        server = null;
        requestExecutor = null;
        sseExecutor = null;
        for (SseClient client : sseClients) {
            client.close();
        }
        sseClients.clear();
        sseConnectionCount.set(0);
        synchronized (McpHttpServer.class) {
            if (McpHttpServer.activeServer == this) {
                McpHttpServer.activeServer = null;
            }
        }
        if (activeServer != null) {
            activeServer.stop(0);
        }
        if (activeRequestExecutor != null) {
            activeRequestExecutor.shutdownNow();
        }
        if (activeSseExecutor != null) {
            activeSseExecutor.shutdownNow();
        }
    }

    public static void broadcastToolsListChanged() {
        McpHttpServer active = McpHttpServer.activeServer;
        if (active != null) {
            active.broadcastToolsListChangedInternal();
        }
    }

    private void broadcastToolsListChangedInternal() {
        JsonObject notification = new JsonObject();
        notification.addProperty("jsonrpc", "2.0");
        notification.addProperty("method", "notifications/tools/list_changed");
        notification.add("params", new JsonObject());
        for (SseClient client : sseClients) {
            try {
                client.send(notification);
            } catch (IOException exception) {
                removeSseClient(client);
                client.close();
            }
        }
    }

    private void handleRequest(HttpExchange exchange) throws IOException {
        boolean handedOff = false;
        try {
            if (!"/mcp".equals(exchange.getRequestURI().getPath())) {
                sendEmptyResponse(exchange, 404);
                return;
            }
            if (!isAllowedOrigin(exchange.getRequestHeaders().getFirst("Origin"))) {
                sendEmptyResponse(exchange, 403);
                return;
            }
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                handedOff = handleEventStream(exchange);
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "GET, POST");
                sendEmptyResponse(exchange, 405);
                return;
            }
            if (!isJsonContentType(exchange.getRequestHeaders().getFirst("Content-Type"))) {
                sendEmptyResponse(exchange, 415);
                return;
            }
            String requestBody;
            try (InputStream input = exchange.getRequestBody()) {
                requestBody = readRequestBody(input);
            } catch (RequestTooLargeException exception) {
                sendEmptyResponse(exchange, 413);
                return;
            }
            JsonRpcResult result = process(requestBody);
            byte[] response = result.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", JSON_CONTENT_TYPE);
            exchange.sendResponseHeaders(result.status(), response.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(response);
            }
        } finally {
            if (!handedOff) {
                exchange.close();
            }
        }
    }

    private boolean handleEventStream(HttpExchange exchange) throws IOException {
        if (!acceptsEventStream(exchange.getRequestHeaders().getFirst("Accept"))) {
            exchange.getResponseHeaders().set("Allow", "GET, POST");
            sendEmptyResponse(exchange, 406);
            return false;
        }
        if (!tryReserveSseConnection()) {
            sendEmptyResponse(exchange, 429);
            return false;
        }
        ExecutorService activeSseExecutor = sseExecutor;
        if (activeSseExecutor == null) {
            sseConnectionCount.decrementAndGet();
            throw new IllegalStateException("MCP event stream executor is not running");
        }
        SseClient client = null;
        boolean submitted = false;
        try {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.getResponseHeaders().set("Connection", "keep-alive");
            exchange.sendResponseHeaders(200, 0);
            client = new SseClient(exchange.getResponseBody(), NeoMcpConfig.sseQueueCapacity());
            SseClient acceptedClient = client;
            sseClients.add(acceptedClient);
            activeSseExecutor.execute(() -> runEventStream(exchange, acceptedClient));
            submitted = true;
            return true;
        } catch (RuntimeException | IOException exception) {
            if (client != null) {
                removeSseClient(client);
                client.close();
            } else {
                sseConnectionCount.decrementAndGet();
            }
            throw exception;
        } finally {
            if (!submitted && client != null) {
                exchange.close();
            }
        }
    }

    private void runEventStream(HttpExchange exchange, SseClient client) {
        try {
            client.sendComment();
            while (!client.isClosed()) {
                String message = client.awaitMessage(
                        NeoMcpConfig.sseHeartbeatSeconds(),
                        TimeUnit.SECONDS);
                if (message == null) {
                    client.sendHeartbeat();
                } else {
                    client.write(message);
                }
            }
        } catch (IOException exception) {
            // The peer disconnected or the bounded event queue rejected a notification.
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } finally {
            removeSseClient(client);
            client.close();
            exchange.close();
        }
    }

    private boolean tryReserveSseConnection() {
        while (true) {
            int current = sseConnectionCount.get();
            if (current >= NeoMcpConfig.sseConnectionLimit()) {
                return false;
            }
            if (sseConnectionCount.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    private void removeSseClient(SseClient client) {
        if (sseClients.remove(client)) {
            sseConnectionCount.decrementAndGet();
        }
    }

    private boolean acceptsEventStream(String acceptHeader) {
        if (acceptHeader == null) {
            return false;
        }
        for (String mediaRange : acceptHeader.split(",")) {
            String mediaType = mediaRange.trim();
            int parameterStart = mediaType.indexOf(';');
            if (parameterStart >= 0) {
                mediaType = mediaType.substring(0, parameterStart).trim();
            }
            if ("text/event-stream".equalsIgnoreCase(mediaType) || "*/*".equals(mediaType)) {
                return true;
            }
        }
        return false;
    }

    private void sendEmptyResponse(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
    }

    private String readRequestBody(InputStream input) throws IOException, RequestTooLargeException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int totalBytes = 0;
        int bytesRead;
        while ((bytesRead = input.read(buffer)) != -1) {
            if (bytesRead > NeoMcpConfig.maxRequestBytes() - totalBytes) {
                throw new RequestTooLargeException();
            }
            output.write(buffer, 0, bytesRead);
            totalBytes += bytesRead;
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    private boolean isJsonContentType(String contentType) {
        if (contentType == null) {
            return false;
        }
        int parameterStart = contentType.indexOf(';');
        String mediaType = parameterStart < 0 ? contentType : contentType.substring(0, parameterStart);
        return "application/json".equalsIgnoreCase(mediaType.trim());
    }

    private boolean isAllowedOrigin(String originHeader) {
        if (originHeader == null) {
            return true;
        }
        try {
            URI origin = URI.create(originHeader);
            String host = origin.getHost();
            return "http".equalsIgnoreCase(origin.getScheme())
                    && origin.getRawUserInfo() == null
                    && origin.getRawQuery() == null
                    && origin.getRawFragment() == null
                    && ("localhost".equalsIgnoreCase(host)
                    || "127.0.0.1".equals(host)
                    || "::1".equals(host)
                    || "[::1]".equals(host));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private JsonRpcResult process(String requestBody) {
        JsonElement request;
        try {
            request = com.google.gson.JsonParser.parseString(requestBody);
        } catch (JsonParseException | IllegalStateException exception) {
            return error(JSON_NULL, -32700, "Parse error: " + exception.getMessage(), 400);
        }
        if (!request.isJsonObject()) {
            return error(JSON_NULL, -32600, "Invalid Request: expected a JSON object", 400);
        }
        JsonObject object = request.getAsJsonObject();
        JsonElement id = object.get("id");
        if (!object.has("jsonrpc") || !object.get("jsonrpc").isJsonPrimitive()
                || !object.getAsJsonPrimitive("jsonrpc").isString()
                || !"2.0".equals(object.get("jsonrpc").getAsString())
                || !object.has("method") || !object.get("method").isJsonPrimitive()
                || !object.getAsJsonPrimitive("method").isString()) {
            return error(idOrNull(id), -32600, "Invalid Request", 400);
        }
        String method = object.get("method").getAsString();
        boolean notification = !object.has("id");
        try {
            JsonElement response = dispatch(method, object.get("params"));
            if (notification) {
                return new JsonRpcResult("", 202);
            }
            return success(id, response, 200);
        } catch (InvalidParamsException exception) {
            if (notification) {
                return new JsonRpcResult("", 202);
            }
            return error(idOrNull(id), -32602, exception.getMessage(), 200);
        } catch (UnknownMethodException exception) {
            if (notification) {
                return new JsonRpcResult("", 202);
            }
            return error(idOrNull(id), -32601, exception.getMessage(), 200);
        } catch (Exception exception) {
            if (notification) {
                return new JsonRpcResult("", 202);
            }
            return error(idOrNull(id), -32603, "Tool execution failed: " + exception.getMessage(), 200);
        }
    }

    private JsonElement dispatch(String method, JsonElement params) throws Exception {
        return switch (method) {
            case "initialize" -> initialize();
            case "notifications/initialized", "ping" -> new JsonObject();
            case "tools/list" -> toolsList();
            case "tools/call" -> toolsCall(params);
            default -> throw new UnknownMethodException("Method not found: " + method);
        };
    }

    private JsonObject initialize() {
        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", "2024-11-05");
        JsonObject capabilities = new JsonObject();
        JsonObject toolsCapabilities = new JsonObject();
        toolsCapabilities.addProperty("listChanged", true);
        capabilities.add("tools", toolsCapabilities);
        result.add("capabilities", capabilities);
        JsonObject serverInfo = new JsonObject();
        serverInfo.addProperty("name", "NeoMCP");
        serverInfo.addProperty("version", "0.1.0");
        result.add("serverInfo", serverInfo);
        return result;
    }

    private JsonObject toolsList() {
        JsonArray tools = new JsonArray();
        JsonObject command = tool("execute_command", "Execute a command on the connected Minecraft server.", "command", "string", true);
        if (NeoMcpConfig.allowCommandExecution()) {
            tools.add(command);
        }
        tools.add(tool("list_mods", "List every mod loaded in the current Minecraft client."));
        JsonObject player = new JsonObject();
        player.addProperty("name", "get_player_info");
        player.addProperty("description", "Return the local player's position, dimension, and health.");
        player.add("inputSchema", emptySchema());
        tools.add(player);
        tools.add(coordinateTool(
                "get_block_entity_data",
                "Return the block entity's full metadata and data components at a block position."));
        JsonObject item = tool("inspect_item_components", "Return the local player's main-hand item state and exact data components.");
        tools.add(item);
        tools.add(stringPropertiesTool(
                "query_registry",
                "List registered object IDs in a registry filtered by namespace.",
                "registry",
                "namespace"));
        tools.add(tool(
                "read_latest_logs",
                "Return the last " + NeoMcpConfig.maxLogLines()
                        + " lines of the active Minecraft logs/latest.log file."));
        if (NeoMcpConfig.allowKubejsScriptInjection()) {
            tools.add(tool(
                    "inject_kubejs_script",
                    "Write JavaScript to KubeJS server_scripts and dispatch /reload.",
                    "script",
                    "string",
                    true));
        }
        tools.add(numericPropertiesTool(
                "get_nearby_entities",
                "Return entities and state data within a radius of a coordinate.",
                "x",
                "y",
                "z",
                "radius"));
        tools.add(tool("list_loot_tables", "List all loot tables loaded by the active integrated server."));
        tools.add(tool(
                "get_loot_table",
                "Return the raw JSON definition of a loaded loot table.",
                "loot_table_id",
                "string",
                true));
        tools.add(tool(
                "search_loot_tables",
                "Find loaded loot tables whose definitions contain an item ID.",
                "item_id",
                "string",
                true));
        tools.add(questGuiTool());
        tools.add(ftbQuestIdTool(
                "get_chapter_layout",
                "Return the FTB Quests chapter quest nodes, coordinates, sizes, and dependency links."));
        tools.add(exportChapterCanvasTool());
        tools.add(tool("take_screenshot", "Capture the main framebuffer, including any active Screen UI overlay."));
        tools.add(tool("update_take_screenshot", "Capture the main framebuffer, including any active Screen UI overlay."));
        tools.add(lookAtTool());
        tools.add(jumpTool());
        tools.add(moveTool());
        tools.add(interactTool());
        tools.add(actionIdTool("get_action_status", "Return the current status of a client action."));
        tools.add(actionIdTool("cancel_action", "Cancel a running client action."));
        tools.add(tool("recipe_capabilities", "Report canonical recipe access and detected optional recipe viewers."));
        tools.add(findRecipesTool());
        tools.add(tool("get_recipe", "Return one exact recipe from the synchronized client recipe manager.",
                "recipe_id", "string", true));
        tools.add(viewRecipeTool());
        tools.add(recipeItemDepthTool(
                "get_recipe_tree",
                "Return a bounded hierarchical crafting tree for an item, including amounts and workstations."));
        tools.add(recipeItemTool(
                "get_item_usages",
                "Return recipes and JEI categories where an item is an ingredient or catalyst."));
        tools.add(recipeItemTool(
                "get_workstation_recipes",
                "Return recipes associated with a workstation or machine item catalyst.",
                "machine_id"));
        tools.add(recipeItemDepthTool(
                "scan_for_loops",
                "Scan a bounded recipe graph for circular item dependencies."));
        tools.add(recipeIdTool(
                "capture_recipe_card",
                "Render one JEI recipe card into an off-screen framebuffer and return a PNG image."));
        for (McpDynamicTool dynamicTool : dynamicToolRegistry.snapshot().values()) {
            JsonObject definition = new JsonObject();
            definition.addProperty("name", dynamicTool.name());
            definition.addProperty("description", dynamicTool.description());
            definition.add("inputSchema", dynamicTool.inputSchema().deepCopy());
            tools.add(definition);
        }
        JsonObject result = new JsonObject();
        result.add("tools", tools);
        return result;
    }

    private JsonElement toolsCall(JsonElement params) throws Exception {
        if (params == null || !params.isJsonObject()) {
            throw new InvalidParamsException("tools/call requires an object params value");
        }
        JsonObject call = params.getAsJsonObject();
        if (!call.has("name") || !call.get("name").isJsonPrimitive()
                || !call.getAsJsonPrimitive("name").isString()) {
            throw new InvalidParamsException("tools/call requires a string name");
        }
        String name = call.get("name").getAsString();
        if (call.has("arguments") && !call.get("arguments").isJsonObject()) {
            throw new InvalidParamsException("tools/call arguments must be an object");
        }
        JsonObject arguments = call.has("arguments") ? call.getAsJsonObject("arguments") : new JsonObject();
        return switch (name) {
            case "execute_command" -> executeCommand(arguments);
            case "list_mods" -> listMods(arguments);
            case "get_player_info" -> getPlayerInfo(arguments);
            case "get_block_entity_data" -> getBlockEntityData(arguments);
            case "inspect_item_components" -> inspectItemComponents(arguments);
            case "query_registry" -> queryRegistry(arguments);
            case "read_latest_logs" -> readLatestLogs(arguments);
            case "inject_kubejs_script" -> injectKubejsScript(arguments);
            case "get_nearby_entities" -> getNearbyEntities(arguments);
            case "list_loot_tables" -> listLootTables(arguments);
            case "get_loot_table" -> getLootTable(arguments);
            case "search_loot_tables" -> searchLootTables(arguments);
            case "open_quest_gui" -> openQuestGui(arguments);
            case "get_chapter_layout" -> getChapterLayout(arguments);
            case "export_chapter_canvas" -> exportChapterCanvas(arguments);
            case "take_screenshot", "update_take_screenshot" -> takeScreenshot(arguments);
            case "look_at" -> lookAt(arguments);
            case "jump" -> jump(arguments);
            case "move" -> move(arguments);
            case "interact" -> interact(arguments);
            case "get_action_status" -> getActionStatus(arguments);
            case "cancel_action" -> cancelAction(arguments);
            case "recipe_capabilities" -> recipeCapabilities(arguments);
            case "find_recipes" -> findRecipes(arguments);
            case "get_recipe" -> getRecipe(arguments);
            case "view_recipe" -> viewRecipe(arguments);
            case "get_recipe_tree" -> getRecipeTree(arguments);
            case "get_item_usages" -> getItemUsages(arguments);
            case "get_workstation_recipes" -> getWorkstationRecipes(arguments);
            case "scan_for_loops" -> scanForLoops(arguments);
            case "capture_recipe_card" -> captureRecipeCard(arguments);
            default -> callDynamicTool(name, arguments);
        };
    }

    private JsonObject callDynamicTool(String name, JsonObject arguments) throws InvalidParamsException {
        McpDynamicTool dynamicTool = dynamicToolRegistry.snapshot().get(name);
        if (dynamicTool == null) {
            throw new InvalidParamsException("Unknown tool: " + name);
        }
        try {
            JsonElement output = dynamicToolRegistry.call(name, arguments);
            JsonObject result = textToolResult(output.toString());
            if (output.isJsonObject()) {
                result.add("structuredContent", output);
            }
            return result;
        } catch (Exception exception) {
            return toolErrorResult("Dynamic tool '" + name + "' failed: " + errorMessage(exception));
        }
    }

    private JsonObject executeCommand(JsonObject arguments) throws Exception {
        if (!arguments.has("command") || !arguments.get("command").isJsonPrimitive()
                || !arguments.getAsJsonPrimitive("command").isString()) {
            throw new InvalidParamsException("execute_command requires a string command");
        }
        String command = arguments.get("command").getAsString();
        if (!NeoMcpConfig.allowCommandExecution()) {
            throw new InvalidParamsException("execute_command is disabled by NeoMCP configuration");
        }
        if (command.isBlank()) {
            throw new InvalidParamsException("execute_command requires a non-blank command");
        }
        if (command.startsWith("/")) {
            throw new InvalidParamsException("execute_command command must not start with '/'");
        }
        if (command.length() > NeoMcpConfig.maxCommandLength()) {
            throw new InvalidParamsException(
                    "execute_command command exceeds the configured maximum length of "
                            + NeoMcpConfig.maxCommandLength());
        }
        for (int index = 0; index < command.length(); index++) {
            if (!net.minecraft.util.StringUtil.isAllowedChatCharacter(command.charAt(index))) {
                throw new InvalidParamsException("execute_command command contains an invalid chat character");
            }
        }
        try {
            toolExecutor.executeCommand(command);
            return textToolResult("Command sent");
        } catch (Exception exception) {
            return toolErrorResult("Command execution failed: " + errorMessage(exception));
        }
    }

    private JsonObject getPlayerInfo(JsonObject arguments) throws Exception {
        if (!arguments.isEmpty()) {
            throw new InvalidParamsException("get_player_info does not accept arguments");
        }
        try {
            JsonObject playerInfo = toolExecutor.getPlayerInfo();
            JsonObject result = textToolResult(playerInfo.toString());
            result.add("structuredContent", playerInfo);
            return result;
        } catch (Exception exception) {
            return toolErrorResult("Player information unavailable: " + errorMessage(exception));
        }
    }

    private JsonObject listMods(JsonObject arguments) throws Exception {
        if (!arguments.isEmpty()) {
            throw new InvalidParamsException("list_mods does not accept arguments");
        }
        try {
            JsonObject mods = toolExecutor.listMods();
            JsonObject result = textToolResult(mods.toString());
            result.add("structuredContent", mods);
            return result;
        } catch (Exception exception) {
            return toolErrorResult("Loaded mod list unavailable: " + errorMessage(exception));
        }
    }

    private JsonObject getBlockEntityData(JsonObject arguments) throws Exception {
        requireOnlyArguments(arguments, "x", "y", "z");
        int x = requiredInteger(arguments, "x", "get_block_entity_data");
        int y = requiredInteger(arguments, "y", "get_block_entity_data");
        int z = requiredInteger(arguments, "z", "get_block_entity_data");
        try {
            JsonObject blockEntityData = toolExecutor.getBlockEntityData(x, y, z);
            JsonObject result = textToolResult(blockEntityData.toString());
            result.add("structuredContent", blockEntityData);
            return result;
        } catch (Exception exception) {
            return toolErrorResult("Block entity data unavailable: " + errorMessage(exception));
        }
    }

    private JsonObject inspectItemComponents(JsonObject arguments) throws Exception {
        if (!arguments.isEmpty()) {
            throw new InvalidParamsException("inspect_item_components does not accept arguments");
        }
        try {
            JsonObject itemData = toolExecutor.inspectItemComponents();
            JsonObject result = textToolResult(itemData.toString());
            result.add("structuredContent", itemData);
            return result;
        } catch (Exception exception) {
            return toolErrorResult("Held item information unavailable: " + errorMessage(exception));
        }
    }

    private JsonObject queryRegistry(JsonObject arguments) throws Exception {
        requireOnlyArguments(arguments, "registry", "namespace");
        String registry = requiredString(arguments, "registry", "query_registry");
        String namespace = requiredString(arguments, "namespace", "query_registry");
        if (!net.minecraft.resources.ResourceLocation.isValidNamespace(namespace)) {
            throw new InvalidParamsException("query_registry requires a valid namespace");
        }
        try {
            JsonObject registryData = toolExecutor.queryRegistry(registry, namespace);
            JsonObject result = textToolResult(registryData.toString());
            result.add("structuredContent", registryData);
            return result;
        } catch (Exception exception) {
            return toolErrorResult("Registry query failed: " + errorMessage(exception));
        }
    }

    private JsonObject readLatestLogs(JsonObject arguments) throws Exception {
        if (!arguments.isEmpty()) {
            throw new InvalidParamsException("read_latest_logs does not accept arguments");
        }
        try {
            JsonObject logs = toolExecutor.readLatestLogs();
            JsonObject result = textToolResult(logs.get("text").getAsString());
            result.add("structuredContent", logs);
            return result;
        } catch (Exception exception) {
            return toolErrorResult("Latest logs unavailable: " + errorMessage(exception));
        }
    }

    private JsonObject injectKubejsScript(JsonObject arguments) throws Exception {
        if (!NeoMcpConfig.allowKubejsScriptInjection()) {
            throw new InvalidParamsException(
                    "inject_kubejs_script is disabled by NeoMCP configuration");
        }
        requireOnlyArguments(arguments, "script");
        String script = requiredString(arguments, "script", "inject_kubejs_script");
        if (script.length() > NeoMcpConfig.maxKubejsScriptLength()) {
            throw new InvalidParamsException(
                    "inject_kubejs_script script exceeds the configured maximum length of "
                            + NeoMcpConfig.maxKubejsScriptLength());
        }
        try {
            JsonObject injection = toolExecutor.injectKubejsScript(script);
            JsonObject result = textToolResult(injection.toString());
            result.add("structuredContent", injection);
            return result;
        } catch (Exception exception) {
            return toolErrorResult("KubeJS script injection failed: " + errorMessage(exception));
        }
    }

    private JsonObject getNearbyEntities(JsonObject arguments) throws Exception {
        requireOnlyArguments(arguments, "x", "y", "z", "radius");
        double x = requiredNumber(arguments, "x", "get_nearby_entities");
        double y = requiredNumber(arguments, "y", "get_nearby_entities");
        double z = requiredNumber(arguments, "z", "get_nearby_entities");
        double radius = requiredNumber(arguments, "radius", "get_nearby_entities");
        if (radius < 0.0D || radius > NeoMcpConfig.maxNearbyEntityRadius()) {
            throw new InvalidParamsException(
                    "get_nearby_entities radius must be between 0 and "
                            + NeoMcpConfig.maxNearbyEntityRadius());
        }
        try {
            JsonObject nearby = toolExecutor.getNearbyEntities(x, y, z, radius);
            JsonObject result = textToolResult(nearby.toString());
            result.add("structuredContent", nearby);
            return result;
        } catch (Exception exception) {
            return toolErrorResult("Nearby entity query failed: " + errorMessage(exception));
        }
    }

    private JsonObject listLootTables(JsonObject arguments) throws Exception {
        if (!arguments.isEmpty()) {
            throw new InvalidParamsException("list_loot_tables does not accept arguments");
        }
        try {
            JsonObject lootTables = toolExecutor.listLootTables();
            JsonObject result = textToolResult(lootTables.toString());
            result.add("structuredContent", lootTables);
            return result;
        } catch (Exception exception) {
            return toolErrorResult("Loot table listing failed: " + errorMessage(exception));
        }
    }

    private JsonObject getLootTable(JsonObject arguments) throws Exception {
        requireOnlyArguments(arguments, "loot_table_id");
        String lootTableId = requiredString(arguments, "loot_table_id", "get_loot_table");
        try {
            JsonObject lootTable = toolExecutor.getLootTable(lootTableId);
            JsonElement rawJson = lootTable.get("table");
            if (rawJson == null || rawJson.isJsonNull()) {
                throw new IllegalStateException("Loot table response did not include raw JSON");
            }
            JsonObject result = textToolResult(rawJson.toString());
            result.add("structuredContent", lootTable);
            return result;
        } catch (Exception exception) {
            return toolErrorResult("Loot table lookup failed: " + errorMessage(exception));
        }
    }

    private JsonObject searchLootTables(JsonObject arguments) throws Exception {
        requireOnlyArguments(arguments, "item_id");
        String itemId = requiredString(arguments, "item_id", "search_loot_tables");
        try {
            JsonObject matches = toolExecutor.searchLootTables(itemId);
            JsonObject result = textToolResult(matches.toString());
            result.add("structuredContent", matches);
            return result;
        } catch (Exception exception) {
            return toolErrorResult("Loot table search failed: " + errorMessage(exception));
        }
    }

    private JsonObject openQuestGui(JsonObject arguments) throws Exception {
        requireOnlyArguments(arguments, "id", "object_type");
        long id = requiredFtbQuestId(arguments, "id", "open_quest_gui");
        String objectType = requiredString(arguments, "object_type", "open_quest_gui");
        if (!"chapter".equals(objectType) && !"quest".equals(objectType)) {
            throw new InvalidParamsException("open_quest_gui object_type must be 'chapter' or 'quest'");
        }
        try {
            JsonObject opened = toolExecutor.openQuestGui(id, objectType);
            JsonObject result = textToolResult(opened.toString());
            result.add("structuredContent", opened);
            return result;
        } catch (Exception exception) {
            return toolErrorResult("FTB Quests GUI could not be opened: " + errorMessage(exception));
        }
    }

    private JsonObject takeScreenshot(JsonObject arguments) throws Exception {
        if (!arguments.isEmpty()) {
            throw new InvalidParamsException("take_screenshot does not accept arguments");
        }
        try {
            JsonObject screenshot = toolExecutor.takeScreenshot();
            JsonObject result = textToolResult(screenshot.toString());
            result.add("structuredContent", screenshot);
            return result;
        } catch (Exception exception) {
            return toolErrorResult("Screenshot capture failed: " + errorMessage(exception));
        }
    }

    private JsonObject lookAt(JsonObject arguments) throws Exception {
        requireOnlyArguments(arguments, "x", "y", "z", "duration_ticks");
        double x = requiredNumber(arguments, "x", "look_at");
        double y = requiredNumber(arguments, "y", "look_at");
        double z = requiredNumber(arguments, "z", "look_at");
        int durationTicks = optionalInteger(arguments, "duration_ticks", "look_at", 1);
        requireActionTicks(durationTicks, "look_at");
        try {
            JsonObject action = toolExecutor.lookAt(x, y, z, durationTicks);
            return structuredToolResult(action);
        } catch (Exception exception) {
            return toolErrorResult("Look action failed: " + errorMessage(exception));
        }
    }

    private JsonObject jump(JsonObject arguments) throws Exception {
        if (!arguments.isEmpty()) {
            throw new InvalidParamsException("jump does not accept arguments");
        }
        try {
            return structuredToolResult(toolExecutor.jump());
        } catch (Exception exception) {
            return toolErrorResult("Jump action failed: " + errorMessage(exception));
        }
    }

    private JsonObject move(JsonObject arguments) throws Exception {
        requireOnlyArguments(arguments, "direction", "duration_ticks");
        String direction = requiredString(arguments, "direction", "move");
        int durationTicks = requiredInteger(arguments, "duration_ticks", "move");
        requireActionTicks(durationTicks, "move");
        if (!List.of("forward", "backward", "left", "right").contains(direction)) {
            throw new InvalidParamsException("move direction must be 'forward', 'backward', 'left', or 'right'");
        }
        try {
            return structuredToolResult(toolExecutor.move(direction, durationTicks));
        } catch (Exception exception) {
            return toolErrorResult("Movement action failed: " + errorMessage(exception));
        }
    }

    private JsonObject interact(JsonObject arguments) throws Exception {
        requireOnlyArguments(arguments, "target", "hand");
        String target = requiredString(arguments, "target", "interact");
        String hand = optionalString(arguments, "hand", "interact", "main_hand");
        if (!List.of("looked_at", "air").contains(target)) {
            throw new InvalidParamsException("interact target must be 'looked_at' or 'air'");
        }
        if (!List.of("main_hand", "off_hand").contains(hand)) {
            throw new InvalidParamsException("interact hand must be 'main_hand' or 'off_hand'");
        }
        try {
            return structuredToolResult(toolExecutor.interact(target, hand));
        } catch (Exception exception) {
            return toolErrorResult("Interaction failed: " + errorMessage(exception));
        }
    }

    private JsonObject getActionStatus(JsonObject arguments) throws Exception {
        requireOnlyArguments(arguments, "action_id");
        long actionId = requiredLong(arguments, "action_id", "get_action_status");
        try {
            return structuredToolResult(toolExecutor.getActionStatus(actionId));
        } catch (Exception exception) {
            return toolErrorResult("Action status unavailable: " + errorMessage(exception));
        }
    }

    private JsonObject cancelAction(JsonObject arguments) throws Exception {
        requireOnlyArguments(arguments, "action_id");
        long actionId = requiredLong(arguments, "action_id", "cancel_action");
        try {
            return structuredToolResult(toolExecutor.cancelAction(actionId));
        } catch (Exception exception) {
            return toolErrorResult("Action cancellation failed: " + errorMessage(exception));
        }
    }

    private JsonObject recipeCapabilities(JsonObject arguments) throws Exception {
        if (!arguments.isEmpty()) {
            throw new InvalidParamsException("recipe_capabilities does not accept arguments");
        }
        try {
            return structuredToolResult(toolExecutor.recipeCapabilities());
        } catch (Exception exception) {
            return toolErrorResult("Recipe capabilities unavailable: " + errorMessage(exception));
        }
    }

    private JsonObject findRecipes(JsonObject arguments) throws Exception {
        requireOnlyArguments(arguments, "query", "recipe_type", "limit");
        String query = optionalString(arguments, "query", "find_recipes", "");
        String recipeType = optionalString(arguments, "recipe_type", "find_recipes", "");
        int limit = optionalInteger(arguments, "limit", "find_recipes", NeoMcpConfig.maxRecipeResults());
        if (limit < 1 || limit > NeoMcpConfig.maxRecipeResults()) {
            throw new InvalidParamsException(
                    "find_recipes limit must be between 1 and " + NeoMcpConfig.maxRecipeResults());
        }
        try {
            return structuredToolResult(toolExecutor.findRecipes(query, recipeType, limit));
        } catch (Exception exception) {
            return toolErrorResult("Recipe search failed: " + errorMessage(exception));
        }
    }

    private JsonObject getRecipe(JsonObject arguments) throws Exception {
        requireOnlyArguments(arguments, "recipe_id");
        String recipeId = requiredString(arguments, "recipe_id", "get_recipe");
        try {
            return structuredToolResult(toolExecutor.getRecipe(recipeId));
        } catch (Exception exception) {
            return toolErrorResult("Recipe lookup failed: " + errorMessage(exception));
        }
    }

    private JsonObject viewRecipe(JsonObject arguments) throws Exception {
        requireOnlyArguments(arguments, "recipe_id", "viewer", "mode");
        String recipeId = requiredString(arguments, "recipe_id", "view_recipe");
        String viewer = optionalString(arguments, "viewer", "view_recipe", "auto");
        String mode = optionalString(arguments, "mode", "view_recipe", "recipe");
        if (!List.of("auto", "jei", "emi", "rei").contains(viewer)) {
            throw new InvalidParamsException("view_recipe viewer must be 'auto', 'jei', 'emi', or 'rei'");
        }
        if (!List.of("recipe", "uses").contains(mode)) {
            throw new InvalidParamsException("view_recipe mode must be 'recipe' or 'uses'");
        }
        try {
            return structuredToolResult(toolExecutor.viewRecipe(recipeId, viewer, mode));
        } catch (Exception exception) {
            return toolErrorResult("Recipe viewer could not open the recipe: " + errorMessage(exception));
        }
    }

    private JsonObject getRecipeTree(JsonObject arguments) throws Exception {
        requireOnlyArguments(arguments, "item_id", "max_depth");
        String itemId = requiredString(arguments, "item_id", "get_recipe_tree");
        int maxDepth = optionalInteger(arguments, "max_depth", "get_recipe_tree", 3);
        requireRecipeDepth(maxDepth, "get_recipe_tree");
        try {
            return structuredToolResult(toolExecutor.getRecipeTree(itemId, maxDepth));
        } catch (Exception exception) {
            return toolErrorResult("Recipe tree lookup failed: " + errorMessage(exception));
        }
    }

    private JsonObject getItemUsages(JsonObject arguments) throws Exception {
        requireOnlyArguments(arguments, "item_id");
        String itemId = requiredString(arguments, "item_id", "get_item_usages");
        try {
            return structuredToolResult(toolExecutor.getItemUsages(itemId));
        } catch (Exception exception) {
            return toolErrorResult("Item usage lookup failed: " + errorMessage(exception));
        }
    }

    private JsonObject getWorkstationRecipes(JsonObject arguments) throws Exception {
        requireOnlyArguments(arguments, "machine_id");
        String machineId = requiredString(arguments, "machine_id", "get_workstation_recipes");
        try {
            return structuredToolResult(toolExecutor.getWorkstationRecipes(machineId));
        } catch (Exception exception) {
            return toolErrorResult("Workstation recipe lookup failed: " + errorMessage(exception));
        }
    }

    private JsonObject scanForLoops(JsonObject arguments) throws Exception {
        requireOnlyArguments(arguments, "item_id", "max_depth");
        String itemId = requiredString(arguments, "item_id", "scan_for_loops");
        int maxDepth = optionalInteger(arguments, "max_depth", "scan_for_loops", 5);
        if (maxDepth < 1 || maxDepth > 5) {
            throw new InvalidParamsException("scan_for_loops max_depth must be between 1 and 5");
        }
        try {
            return structuredToolResult(toolExecutor.scanForLoops(itemId, maxDepth));
        } catch (Exception exception) {
            return toolErrorResult("Recipe loop scan failed: " + errorMessage(exception));
        }
    }

    private JsonObject captureRecipeCard(JsonObject arguments) throws Exception {
        requireOnlyArguments(arguments, "recipe_id");
        String recipeId = requiredString(arguments, "recipe_id", "capture_recipe_card");
        try {
            JsonObject capture = toolExecutor.captureRecipeCard(recipeId);
            JsonElement encodedImage = capture.get("png_base64");
            if (encodedImage == null || !encodedImage.isJsonPrimitive()
                    || !encodedImage.getAsJsonPrimitive().isString()
                    || encodedImage.getAsString().isBlank()) {
                throw new IllegalStateException("Recipe card capture did not return a PNG payload");
            }
            JsonObject metadata = capture.deepCopy();
            metadata.remove("png_base64");
            return imageToolResult(encodedImage.getAsString(), metadata);
        } catch (Exception exception) {
            return toolErrorResult("Recipe card capture failed: " + errorMessage(exception));
        }
    }

    private JsonObject structuredToolResult(JsonObject structuredContent) {
        JsonObject result = textToolResult(structuredContent.toString());
        result.add("structuredContent", structuredContent);
        return result;
    }

    private JsonObject getChapterLayout(JsonObject arguments) throws Exception {
        requireOnlyArguments(arguments, "chapter_id");
        long chapterId = requiredFtbQuestId(arguments, "chapter_id", "get_chapter_layout");
        try {
            JsonObject chapterLayout = toolExecutor.getChapterLayout(chapterId);
            JsonObject result = textToolResult(chapterLayout.toString());
            result.add("structuredContent", chapterLayout);
            return result;
        } catch (Exception exception) {
            return toolErrorResult("FTB Quests chapter layout unavailable: " + errorMessage(exception));
        }
    }

    private JsonObject exportChapterCanvas(JsonObject arguments) throws Exception {
        requireOnlyArguments(arguments, "chapter_id", "save_png");
        long chapterId = requiredFtbQuestId(arguments, "chapter_id", "export_chapter_canvas");
        boolean savePng = optionalBoolean(arguments, "save_png", "export_chapter_canvas");
        try {
            JsonObject export = toolExecutor.exportChapterCanvas(chapterId, savePng);
            JsonElement encodedImage = export.get("png_base64");
            if (encodedImage == null || !encodedImage.isJsonPrimitive()
                    || !encodedImage.getAsJsonPrimitive().isString()
                    || encodedImage.getAsString().isBlank()) {
                throw new IllegalStateException("FTB Quests canvas export did not return a PNG payload");
            }
            JsonObject metadata = export.deepCopy();
            metadata.remove("png_base64");
            return imageToolResult(encodedImage.getAsString(), metadata);
        } catch (Exception exception) {
            return toolErrorResult("FTB Quests chapter canvas export failed: " + errorMessage(exception));
        }
    }

    private JsonObject textToolResult(String text) {
        JsonArray content = new JsonArray();
        JsonObject contentItem = new JsonObject();
        contentItem.addProperty("type", "text");
        contentItem.addProperty("text", text);
        content.add(contentItem);
        JsonObject result = new JsonObject();
        result.add("content", content);
        result.addProperty("isError", false);
        return result;
    }

    private JsonObject imageToolResult(String base64Png, JsonObject metadata) {
        JsonArray content = new JsonArray();
        JsonObject contentItem = new JsonObject();
        contentItem.addProperty("type", "image");
        contentItem.addProperty("data", base64Png);
        contentItem.addProperty("mimeType", "image/png");
        content.add(contentItem);
        JsonObject result = new JsonObject();
        result.add("content", content);
        result.addProperty("isError", false);
        result.add("structuredContent", metadata);
        return result;
    }

    private JsonObject toolErrorResult(String text) {
        JsonObject result = textToolResult(text);
        result.addProperty("isError", true);
        return result;
    }

    private String errorMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private JsonObject tool(String name, String description, String property, String type, boolean required) {
        JsonObject result = tool(name, description);
        JsonObject schema = result.getAsJsonObject("inputSchema");
        JsonObject properties = new JsonObject();
        JsonObject value = new JsonObject();
        value.addProperty("type", type);
        properties.add(property, value);
        schema.add("properties", properties);
        if (required) {
            JsonArray requiredProperties = new JsonArray();
            requiredProperties.add(property);
            schema.add("required", requiredProperties);
        }
        return result;
    }

    private JsonObject tool(String name, String description) {
        JsonObject result = new JsonObject();
        result.addProperty("name", name);
        result.addProperty("description", description);
        result.add("inputSchema", emptySchema());
        return result;
    }

    private JsonObject coordinateTool(String name, String description) {
        JsonObject result = tool(name, description);
        JsonObject schema = result.getAsJsonObject("inputSchema");
        JsonObject properties = new JsonObject();
        properties.add("x", integerSchema());
        properties.add("y", integerSchema());
        properties.add("z", integerSchema());
        schema.add("properties", properties);
        JsonArray required = new JsonArray();
        required.add("x");
        required.add("y");
        required.add("z");
        schema.add("required", required);
        return result;
    }

    private JsonObject stringPropertiesTool(String name, String description, String firstProperty, String secondProperty) {
        JsonObject result = tool(name, description);
        JsonObject schema = result.getAsJsonObject("inputSchema");
        JsonObject properties = new JsonObject();
        properties.add(firstProperty, stringSchema());
        properties.add(secondProperty, stringSchema());
        schema.add("properties", properties);
        JsonArray required = new JsonArray();
        required.add(firstProperty);
        required.add(secondProperty);
        schema.add("required", required);
        return result;
    }

    private JsonObject stringSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "string");
        return schema;
    }

    private JsonObject numericPropertiesTool(
            String name,
            String description,
            String firstProperty,
            String secondProperty,
            String thirdProperty,
            String fourthProperty) {
        JsonObject result = tool(name, description);
        JsonObject schema = result.getAsJsonObject("inputSchema");
        JsonObject properties = new JsonObject();
        properties.add(firstProperty, numberSchema());
        properties.add(secondProperty, numberSchema());
        properties.add(thirdProperty, numberSchema());
        properties.add(fourthProperty, numberSchema());
        schema.add("properties", properties);
        JsonArray required = new JsonArray();
        required.add(firstProperty);
        required.add(secondProperty);
        required.add(thirdProperty);
        required.add(fourthProperty);
        schema.add("required", required);
        return result;
    }

    private JsonObject numberSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "number");
        return schema;
    }

    private JsonObject booleanSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "boolean");
        return schema;
    }

    private JsonObject questGuiTool() {
        JsonObject result = tool(
                "open_quest_gui",
                "Open an FTB Quests chapter or quest by an exact 16-character hexadecimal FTB Quest code string.");
        JsonObject schema = result.getAsJsonObject("inputSchema");
        JsonObject properties = new JsonObject();
        properties.add("id", ftbQuestIdSchema());
        JsonObject objectType = stringSchema();
        JsonArray enumValues = new JsonArray();
        enumValues.add("chapter");
        enumValues.add("quest");
        objectType.add("enum", enumValues);
        properties.add("object_type", objectType);
        schema.add("properties", properties);
        JsonArray required = new JsonArray();
        required.add("id");
        required.add("object_type");
        schema.add("required", required);
        return result;
    }

    private JsonObject ftbQuestIdTool(String name, String description) {
        JsonObject result = tool(name, description);
        JsonObject schema = result.getAsJsonObject("inputSchema");
        JsonObject properties = new JsonObject();
        properties.add("chapter_id", ftbQuestIdSchema());
        schema.add("properties", properties);
        JsonArray required = new JsonArray();
        required.add("chapter_id");
        schema.add("required", required);
        return result;
    }

    private JsonObject exportChapterCanvasTool() {
        JsonObject result = ftbQuestIdTool(
                "export_chapter_canvas",
                "Render an entire FTB Quests chapter canvas as a single PNG image, optionally saving it to screenshots.");
        JsonObject properties = result.getAsJsonObject("inputSchema").getAsJsonObject("properties");
        properties.add("save_png", booleanSchema());
        return result;
    }

    private JsonObject lookAtTool() {
        JsonObject result = tool(
                "look_at",
                "Turn the local player toward a world position, optionally using linear interpolation over client ticks.");
        JsonObject schema = result.getAsJsonObject("inputSchema");
        JsonObject properties = new JsonObject();
        properties.add("x", numberSchema());
        properties.add("y", numberSchema());
        properties.add("z", numberSchema());
        properties.add("duration_ticks", integerSchema());
        schema.add("properties", properties);
        JsonArray required = new JsonArray();
        required.add("x");
        required.add("y");
        required.add("z");
        schema.add("required", required);
        return result;
    }

    private JsonObject jumpTool() {
        return tool("jump", "Perform one bounded local-player jump from the client thread.");
    }

    private JsonObject moveTool() {
        JsonObject result = tool("move", "Hold one primitive movement key for a bounded number of client ticks.");
        JsonObject schema = result.getAsJsonObject("inputSchema");
        JsonObject properties = new JsonObject();
        JsonObject direction = stringSchema();
        JsonArray directions = new JsonArray();
        directions.add("forward");
        directions.add("backward");
        directions.add("left");
        directions.add("right");
        direction.add("enum", directions);
        properties.add("direction", direction);
        properties.add("duration_ticks", integerSchema());
        schema.add("properties", properties);
        JsonArray required = new JsonArray();
        required.add("direction");
        required.add("duration_ticks");
        schema.add("required", required);
        return result;
    }

    private JsonObject interactTool() {
        JsonObject result = tool(
                "interact",
                "Use the selected hand on the current crosshair target or use the item in the air.");
        JsonObject schema = result.getAsJsonObject("inputSchema");
        JsonObject properties = new JsonObject();
        JsonObject target = stringSchema();
        JsonArray targets = new JsonArray();
        targets.add("looked_at");
        targets.add("air");
        target.add("enum", targets);
        properties.add("target", target);
        JsonObject hand = stringSchema();
        JsonArray hands = new JsonArray();
        hands.add("main_hand");
        hands.add("off_hand");
        hand.add("enum", hands);
        properties.add("hand", hand);
        schema.add("properties", properties);
        JsonArray required = new JsonArray();
        required.add("target");
        schema.add("required", required);
        return result;
    }

    private JsonObject actionIdTool(String name, String description) {
        return tool(name, description, "action_id", "integer", true);
    }

    private JsonObject findRecipesTool() {
        JsonObject result = tool(
                "find_recipes",
                "Search the synchronized client recipe manager by recipe ID, item ID, ingredient, or group.");
        JsonObject schema = result.getAsJsonObject("inputSchema");
        JsonObject properties = new JsonObject();
        properties.add("query", stringSchema());
        properties.add("recipe_type", stringSchema());
        properties.add("limit", integerSchema());
        schema.add("properties", properties);
        return result;
    }

    private JsonObject viewRecipeTool() {
        JsonObject result = tool(
                "view_recipe",
                "Open a recipe or item-usage view in an installed optional recipe viewer.");
        JsonObject schema = result.getAsJsonObject("inputSchema");
        JsonObject properties = new JsonObject();
        properties.add("recipe_id", stringSchema());
        JsonObject viewer = stringSchema();
        JsonArray viewers = new JsonArray();
        viewers.add("auto");
        viewers.add("jei");
        viewers.add("emi");
        viewers.add("rei");
        viewer.add("enum", viewers);
        properties.add("viewer", viewer);
        JsonObject mode = stringSchema();
        JsonArray modes = new JsonArray();
        modes.add("recipe");
        modes.add("uses");
        mode.add("enum", modes);
        properties.add("mode", mode);
        schema.add("properties", properties);
        JsonArray required = new JsonArray();
        required.add("recipe_id");
        schema.add("required", required);
        return result;
    }

    private JsonObject recipeItemTool(String name, String description) {
        return recipeItemTool(name, description, "item_id");
    }

    private JsonObject recipeItemTool(String name, String description, String propertyName) {
        return tool(name, description, propertyName, "string", true);
    }

    private JsonObject recipeItemDepthTool(String name, String description) {
        JsonObject result = recipeItemTool(name, description);
        JsonObject properties = result.getAsJsonObject("inputSchema").getAsJsonObject("properties");
        properties.add("max_depth", integerSchema());
        return result;
    }

    private JsonObject recipeIdTool(String name, String description) {
        return tool(name, description, "recipe_id", "string", true);
    }

    private JsonObject ftbQuestIdSchema() {
        JsonObject schema = stringSchema();
        schema.addProperty("pattern", "^[0-9A-Fa-f]{16}$");
        schema.addProperty("minLength", 16);
        schema.addProperty("maxLength", 16);
        return schema;
    }

    private JsonObject integerSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "integer");
        return schema;
    }

    private void requireRecipeDepth(int depth, String toolName) throws InvalidParamsException {
        if (depth < 0 || depth > NeoMcpConfig.maxRecipeTreeDepth()) {
            throw new InvalidParamsException(
                    toolName + " max_depth must be between 0 and " + NeoMcpConfig.maxRecipeTreeDepth());
        }
    }

    private void requireOnlyArguments(JsonObject arguments, String... allowedNames) throws InvalidParamsException {
        for (String name : arguments.keySet()) {
            boolean allowed = false;
            for (String allowedName : allowedNames) {
                if (allowedName.equals(name)) {
                    allowed = true;
                    break;
                }
            }
            if (!allowed) {
                throw new InvalidParamsException("Unexpected argument: " + name);
            }
        }
    }

    private int requiredInteger(JsonObject arguments, String name) throws InvalidParamsException {
        return requiredInteger(arguments, name, "request");
    }

    private int requiredInteger(JsonObject arguments, String name, String toolName)
            throws InvalidParamsException {
        JsonElement value = arguments.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new InvalidParamsException(toolName + " requires an integer " + name);
        }
        try {
            return new BigDecimal(value.getAsString()).toBigIntegerExact().intValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new InvalidParamsException(toolName + " requires an integer " + name);
        }
    }

    private int optionalInteger(JsonObject arguments, String name, String toolName, int defaultValue)
            throws InvalidParamsException {
        return arguments.has(name) ? requiredInteger(arguments, name, toolName) : defaultValue;
    }

    private long requiredLong(JsonObject arguments, String name, String toolName) throws InvalidParamsException {
        JsonElement value = arguments.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new InvalidParamsException(toolName + " requires an integer " + name);
        }
        try {
            return new BigDecimal(value.getAsString()).toBigIntegerExact().longValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new InvalidParamsException(toolName + " requires an integer " + name);
        }
    }

    private String requiredString(JsonObject arguments, String name, String toolName) throws InvalidParamsException {
        JsonElement value = arguments.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                || value.getAsString().isBlank()) {
            throw new InvalidParamsException(toolName + " requires a non-blank string " + name);
        }
        return value.getAsString();
    }

    private String optionalString(JsonObject arguments, String name, String toolName, String defaultValue)
            throws InvalidParamsException {
        return arguments.has(name) ? requiredString(arguments, name, toolName) : defaultValue;
    }

    private void requireActionTicks(int ticks, String toolName) throws InvalidParamsException {
        if (ticks < 1 || ticks > NeoMcpConfig.maxActionTicks()) {
            throw new InvalidParamsException(
                    toolName + " duration_ticks must be between 1 and " + NeoMcpConfig.maxActionTicks());
        }
    }

    private long requiredFtbQuestId(JsonObject arguments, String name, String toolName)
            throws InvalidParamsException {
        String value = requiredString(arguments, name, toolName);
        if (!value.matches("[0-9A-Fa-f]{16}")) {
            throw new InvalidParamsException(
                    toolName + " requires " + name + " to be an exact 16-character hexadecimal FTB Quest code string");
        }
        try {
            return Long.parseUnsignedLong(value, 16);
        } catch (NumberFormatException exception) {
            throw new InvalidParamsException(toolName + " received an invalid FTB Quest code string for " + name);
        }
    }

    private double requiredNumber(JsonObject arguments, String name, String toolName) throws InvalidParamsException {
        JsonElement value = arguments.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new InvalidParamsException(toolName + " requires a number " + name);
        }
        double result;
        try {
            result = value.getAsDouble();
        } catch (NumberFormatException exception) {
            throw new InvalidParamsException(toolName + " requires a finite number " + name);
        }
        if (!Double.isFinite(result)) {
            throw new InvalidParamsException(toolName + " requires a finite number " + name);
        }
        return result;
    }

    private boolean optionalBoolean(JsonObject arguments, String name, String toolName)
            throws InvalidParamsException {
        JsonElement value = arguments.get(name);
        if (value == null) {
            return false;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw new InvalidParamsException(toolName + " requires a boolean " + name);
        }
        return value.getAsBoolean();
    }

    private JsonObject emptySchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.addProperty("additionalProperties", false);
        return schema;
    }

    private ThreadFactory namedDaemonThreadFactory() {
        return namedDaemonThreadFactory("NeoMCP-http-");
    }

    private ThreadFactory namedDaemonThreadFactory(String namePrefix) {
        AtomicInteger threadNumber = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, namePrefix + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static final class SseClient {
        private final OutputStream output;
        private final ArrayBlockingQueue<String> messages;
        private final AtomicBoolean closed = new AtomicBoolean();

        private SseClient(OutputStream output, int queueCapacity) {
            this.output = output;
            this.messages = new ArrayBlockingQueue<>(queueCapacity);
        }

        private void sendComment() throws IOException {
            write(": neomcp connected\n\n");
        }

        private void send(JsonObject message) throws IOException {
            if (closed.get()) {
                throw new IOException("MCP event stream is closed");
            }
            if (!messages.offer("data: " + message + "\n\n")) {
                throw new IOException("MCP event stream queue is full");
            }
        }

        private String awaitMessage(long timeout, TimeUnit unit) throws InterruptedException {
            return messages.poll(timeout, unit);
        }

        private void sendHeartbeat() throws IOException {
            write(": keep-alive\n\n");
        }

        private void write(String message) throws IOException {
            output.write(message.getBytes(StandardCharsets.UTF_8));
            output.flush();
        }

        private boolean isClosed() {
            return closed.get();
        }

        private void close() {
            if (closed.compareAndSet(false, true)) {
                messages.clear();
                try {
                    output.close();
                } catch (IOException ignored) {
                    // The stream is already being closed.
                }
            }
        }
    }

    private static final class RequestTooLargeException extends Exception {
    }

    private JsonRpcResult success(JsonElement id, JsonElement result, int status) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id);
        response.add("result", result);
        return new JsonRpcResult(gson.toJson(response), status);
    }

    private JsonRpcResult error(JsonElement id, int code, String message, int status) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id);
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message == null ? "Unknown error" : message);
        response.add("error", error);
        return new JsonRpcResult(gson.toJson(response), status);
    }

    private JsonElement idOrNull(JsonElement id) {
        return id == null ? JSON_NULL : id;
    }

    private record JsonRpcResult(String body, int status) {
    }

    private static final class InvalidParamsException extends Exception {
        private InvalidParamsException(String message) { super(message); }
    }

    private static final class UnknownMethodException extends Exception {
        private UnknownMethodException(String message) { super(message); }
    }

}
