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
        JsonObject result = new JsonObject();
        result.addProperty("name", name);
        result.addProperty("description", description);
        JsonObject schema = emptySchema();
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
        result.add("inputSchema", schema);
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
