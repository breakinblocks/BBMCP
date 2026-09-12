package com.breakinblocks.neomcp.mcp;

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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class McpHttpServer implements AutoCloseable {
    private static final int MAX_REQUEST_BYTES = 1024 * 1024;
    private static final int PORT = 8080;
    private static final int REQUEST_THREAD_COUNT = 4;
    private static final int REQUEST_QUEUE_CAPACITY = 16;
    private static final double MAX_NEARBY_ENTITY_RADIUS = 512.0D;
    private static final JsonElement JSON_NULL = JsonNull.INSTANCE;
    private static final String JSON_CONTENT_TYPE = "application/json; charset=utf-8";
    private final Gson gson = new Gson();
    private final McpToolExecutor toolExecutor;
    private HttpServer server;
    private ExecutorService requestExecutor;

    public McpHttpServer(McpToolExecutor toolExecutor) {
        this.toolExecutor = Objects.requireNonNull(toolExecutor, "toolExecutor");
    }

    public synchronized void start() throws IOException {
        if (server != null || requestExecutor != null) {
            throw new IllegalStateException("MCP server is already running");
        }
        HttpServer newServer = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
        ExecutorService newRequestExecutor = new ThreadPoolExecutor(
                REQUEST_THREAD_COUNT,
                REQUEST_THREAD_COUNT,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(REQUEST_QUEUE_CAPACITY),
                namedDaemonThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy());
        try {
            newServer.createContext("/mcp", this::handleRequest);
            newServer.setExecutor(newRequestExecutor);
            newServer.start();
            server = newServer;
            requestExecutor = newRequestExecutor;
        } catch (RuntimeException exception) {
            newServer.stop(0);
            newRequestExecutor.shutdownNow();
            throw exception;
        }
    }

    @Override
    public synchronized void close() {
        HttpServer activeServer = server;
        ExecutorService activeRequestExecutor = requestExecutor;
        server = null;
        requestExecutor = null;
        if (activeServer != null) {
            activeServer.stop(0);
        }
        if (activeRequestExecutor != null) {
            activeRequestExecutor.shutdownNow();
        }
    }

    private void handleRequest(HttpExchange exchange) throws IOException {
        try {
            if (!"/mcp".equals(exchange.getRequestURI().getPath())) {
                sendEmptyResponse(exchange, 404);
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "POST");
                sendEmptyResponse(exchange, 405);
                return;
            }
            if (!isJsonContentType(exchange.getRequestHeaders().getFirst("Content-Type"))) {
                sendEmptyResponse(exchange, 415);
                return;
            }
            if (!isAllowedOrigin(exchange.getRequestHeaders().getFirst("Origin"))) {
                sendEmptyResponse(exchange, 403);
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
            exchange.close();
        }
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
            if (bytesRead > MAX_REQUEST_BYTES - totalBytes) {
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
        capabilities.add("tools", new JsonObject());
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
        tools.add(command);
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
        tools.add(tool("read_latest_logs", "Return the last 100 lines of the active Minecraft logs/latest.log file."));
        tools.add(tool("inject_kubejs_script", "Write JavaScript to KubeJS server_scripts and dispatch /reload.", "script", "string", true));
        tools.add(numericPropertiesTool(
                "get_nearby_entities",
                "Return entities and state data within a radius of a coordinate.",
                "x",
                "y",
                "z",
                "radius"));
        tools.add(questGuiTool());
        tools.add(ftbQuestIdTool(
                "get_chapter_layout",
                "Return the FTB Quests chapter quest nodes, coordinates, sizes, and dependency links."));
        tools.add(tool("take_screenshot", "Capture the main framebuffer, including any active Screen UI overlay."));
        tools.add(tool("update_take_screenshot", "Capture the main framebuffer, including any active Screen UI overlay."));
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
            case "get_player_info" -> getPlayerInfo(arguments);
            case "get_block_entity_data" -> getBlockEntityData(arguments);
            case "inspect_item_components" -> inspectItemComponents(arguments);
            case "query_registry" -> queryRegistry(arguments);
            case "read_latest_logs" -> readLatestLogs(arguments);
            case "inject_kubejs_script" -> injectKubejsScript(arguments);
            case "get_nearby_entities" -> getNearbyEntities(arguments);
            case "open_quest_gui" -> openQuestGui(arguments);
            case "get_chapter_layout" -> getChapterLayout(arguments);
            case "take_screenshot", "update_take_screenshot" -> takeScreenshot(arguments);
            default -> throw new InvalidParamsException("Unknown tool: " + name);
        };
    }

    private JsonObject executeCommand(JsonObject arguments) throws Exception {
        if (!arguments.has("command") || !arguments.get("command").isJsonPrimitive()
                || !arguments.getAsJsonPrimitive("command").isString()) {
            throw new InvalidParamsException("execute_command requires a string command");
        }
        String command = arguments.get("command").getAsString();
        if (command.isBlank()) {
            throw new InvalidParamsException("execute_command requires a non-blank command");
        }
        if (command.startsWith("/")) {
            throw new InvalidParamsException("execute_command command must not start with '/'");
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

    private JsonObject getBlockEntityData(JsonObject arguments) throws Exception {
        requireOnlyArguments(arguments, "x", "y", "z");
        int x = requiredInteger(arguments, "x");
        int y = requiredInteger(arguments, "y");
        int z = requiredInteger(arguments, "z");
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
        requireOnlyArguments(arguments, "script");
        String script = requiredString(arguments, "script", "inject_kubejs_script");
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
        if (radius < 0.0D || radius > MAX_NEARBY_ENTITY_RADIUS) {
            throw new InvalidParamsException(
                    "get_nearby_entities radius must be between 0 and " + MAX_NEARBY_ENTITY_RADIUS);
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
        JsonElement value = arguments.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new InvalidParamsException("get_block_entity_data requires an integer " + name);
        }
        try {
            return new BigDecimal(value.getAsString()).toBigIntegerExact().intValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new InvalidParamsException("get_block_entity_data requires an integer " + name);
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

    private JsonObject emptySchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.addProperty("additionalProperties", false);
        return schema;
    }

    private ThreadFactory namedDaemonThreadFactory() {
        AtomicInteger threadNumber = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "NeoMCP-http-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
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
