package com.breakinblocks.neomcp.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class NeoMcpConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue ENABLE_SERVER = BUILDER
            .comment("Start the NeoMCP HTTP server when the client starts")
            .define("enableServer", true);

    public static final ModConfigSpec.IntValue PORT = BUILDER
            .comment("Loopback HTTP port used by NeoMCP")
            .defineInRange("port", 8080, 1, 65535);

    public static final ModConfigSpec.IntValue REQUEST_THREAD_COUNT = BUILDER
            .comment("Number of concurrent HTTP request worker threads")
            .defineInRange("requestThreadCount", 8, 1, 64);

    public static final ModConfigSpec.IntValue REQUEST_QUEUE_CAPACITY = BUILDER
            .comment("Maximum number of HTTP requests waiting for a worker")
            .defineInRange("requestQueueCapacity", 16, 1, 1024);

    public static final ModConfigSpec.IntValue MAX_REQUEST_BYTES = BUILDER
            .comment("Maximum UTF-8 request body size in bytes")
            .defineInRange("maxRequestBytes", 1024 * 1024, 4 * 1024, 16 * 1024 * 1024);

    public static final ModConfigSpec.IntValue SSE_CONNECTION_LIMIT = BUILDER
            .comment("Maximum number of simultaneous MCP event-stream connections")
            .defineInRange("sseConnectionLimit", 8, 1, 128);

    public static final ModConfigSpec.IntValue SSE_QUEUE_CAPACITY = BUILDER
            .comment("Maximum pending notifications per MCP event-stream connection")
            .defineInRange("sseQueueCapacity", 32, 1, 1024);

    public static final ModConfigSpec.IntValue SSE_HEARTBEAT_SECONDS = BUILDER
            .comment("Seconds between keep-alive comments on an idle event stream")
            .defineInRange("sseHeartbeatSeconds", 15, 1, 300);

    public static final ModConfigSpec.IntValue ACTION_TIMEOUT_SECONDS = BUILDER
            .comment("Maximum seconds to wait for a client, server, or KubeJS action")
            .defineInRange("actionTimeoutSeconds", 5, 1, 120);

    public static final ModConfigSpec.DoubleValue MAX_NEARBY_ENTITY_RADIUS = BUILDER
            .comment("Maximum radius accepted by get_nearby_entities")
            .defineInRange("maxNearbyEntityRadius", 512.0D, 0.0D, 4096.0D);

    public static final ModConfigSpec.IntValue MAX_LOG_LINES = BUILDER
            .comment("Maximum number of lines returned by read_latest_logs")
            .defineInRange("maxLogLines", 100, 1, 10000);

    public static final ModConfigSpec.IntValue MAX_COMMAND_LENGTH = BUILDER
            .comment("Maximum command string length accepted by execute_command")
            .defineInRange("maxCommandLength", 32768, 1, 262144);

    public static final ModConfigSpec.IntValue MAX_KUBEJS_SCRIPT_LENGTH = BUILDER
            .comment("Maximum script length accepted by inject_kubejs_script")
            .defineInRange("maxKubejsScriptLength", 262144, 1, 1048576);

    public static final ModConfigSpec.BooleanValue ALLOW_COMMAND_EXECUTION = BUILDER
            .comment("Allow the execute_command tool to send commands")
            .define("allowCommandExecution", true);

    public static final ModConfigSpec.BooleanValue ALLOW_KUBEJS_SCRIPT_INJECTION = BUILDER
            .comment("Allow the inject_kubejs_script tool to write scripts and dispatch reload")
            .define("allowKubejsScriptInjection", true);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private NeoMcpConfig() {
    }

    public static boolean isServerEnabled() {
        return ENABLE_SERVER.get();
    }

    public static int port() {
        return PORT.get();
    }

    public static int requestThreadCount() {
        return REQUEST_THREAD_COUNT.get();
    }

    public static int requestQueueCapacity() {
        return REQUEST_QUEUE_CAPACITY.get();
    }

    public static int maxRequestBytes() {
        return MAX_REQUEST_BYTES.get();
    }

    public static int sseConnectionLimit() {
        return SSE_CONNECTION_LIMIT.get();
    }

    public static int sseQueueCapacity() {
        return SSE_QUEUE_CAPACITY.get();
    }

    public static long sseHeartbeatSeconds() {
        return SSE_HEARTBEAT_SECONDS.get();
    }

    public static long actionTimeoutSeconds() {
        return ACTION_TIMEOUT_SECONDS.get();
    }

    public static double maxNearbyEntityRadius() {
        return MAX_NEARBY_ENTITY_RADIUS.get();
    }

    public static int maxLogLines() {
        return MAX_LOG_LINES.get();
    }

    public static int maxCommandLength() {
        return MAX_COMMAND_LENGTH.get();
    }

    public static int maxKubejsScriptLength() {
        return MAX_KUBEJS_SCRIPT_LENGTH.get();
    }

    public static boolean allowCommandExecution() {
        return ALLOW_COMMAND_EXECUTION.get();
    }

    public static boolean allowKubejsScriptInjection() {
        return ALLOW_KUBEJS_SCRIPT_INJECTION.get();
    }
}
