package com.breakinblocks.neomcp.kubejs;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Objects;

/**
 * Side-safe boundary for values that only exist in the local Minecraft client.
 *
 * <p>The bridge deliberately stores the client instance as {@link Object}; this
 * common class must remain loadable on a dedicated server where
 * {@code net.minecraft.client.Minecraft} does not exist.</p>
 */
public final class NeoMcpClientBridge {
    private static volatile CaptureProvider provider;

    private NeoMcpClientBridge() {
    }

    public static synchronized void install(CaptureProvider newProvider) {
        Objects.requireNonNull(newProvider, "newProvider");
        if (provider != null) {
            throw new IllegalStateException("NeoMCP client bridge is already installed");
        }
        provider = newProvider;
    }

    public static synchronized void clear() {
        provider = null;
    }

    public static ClientValues capture(MinecraftServer server) throws Exception {
        Objects.requireNonNull(server, "server");
        CaptureProvider currentProvider = provider;
        if (currentProvider == null) {
            return new ClientValues(null, null);
        }
        return Objects.requireNonNull(
                currentProvider.capture(server),
                "client bridge capture returned null");
    }

    @FunctionalInterface
    public interface CaptureProvider {
        ClientValues capture(MinecraftServer server) throws Exception;
    }

    public record ClientValues(ServerPlayer serverPlayer, Object minecraft) {
    }
}
