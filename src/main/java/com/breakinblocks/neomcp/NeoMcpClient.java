package com.breakinblocks.neomcp;

import com.breakinblocks.neomcp.mcp.McpHttpServer;
import com.breakinblocks.neomcp.mcp.McpToolExecutor;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

import com.breakinblocks.neomcp.mcp.McpNbtJson;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@EventBusSubscriber(modid = NeoMcp.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class NeoMcpClient {
    private static final long CLIENT_ACTION_TIMEOUT_SECONDS = 5;
    private static McpHttpServer server;

    private NeoMcpClient() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(NeoMcpClient::startServer);
    }

    private static void startServer() {
        if (server != null) {
            throw new IllegalStateException("NeoMCP HTTP server is already running");
        }
        McpHttpServer newServer = new McpHttpServer(new MinecraftToolExecutor());
        try {
            newServer.start();
            server = newServer;
        } catch (java.io.IOException | RuntimeException exception) {
            newServer.close();
            throw new IllegalStateException("Unable to start NeoMCP HTTP server on localhost:8080", exception);
        }
    }

    static void stopServer() {
        if (server != null) {
            server.close();
            server = null;
        }
    }

    private static final class MinecraftToolExecutor implements McpToolExecutor {
        @Override
        public void executeCommand(String command) throws Exception {
            runOnClientThread(() -> {
                Minecraft minecraft = Minecraft.getInstance();
                ClientPacketListener connection = minecraft.getConnection();
                if (connection == null) {
                    throw new IllegalStateException("Minecraft client is not connected");
                }
                connection.sendCommand(command);
            });
        }

        @Override
        public JsonObject getPlayerInfo() throws Exception {
            return callOnClientThread(() -> {
                LocalPlayer player = Minecraft.getInstance().player;
                if (player == null) {
                    throw new IllegalStateException("Minecraft local player is unavailable");
                }
                JsonObject info = new JsonObject();
                info.addProperty("x", player.getX());
                info.addProperty("y", player.getY());
                info.addProperty("z", player.getZ());
                info.addProperty("dimension", player.level().dimension().location().toString());
                info.addProperty("health", player.getHealth());
                return info;
            });
        }

        @Override
        public JsonObject getBlockEntityData(int x, int y, int z) throws Exception {
            return callOnClientThread(() -> {
                Minecraft minecraft = Minecraft.getInstance();
                Level level = minecraft.level;
                if (level == null) {
                    throw new IllegalStateException("Minecraft client level is unavailable");
                }
                BlockPos position = new BlockPos(x, y, z);
                BlockEntity blockEntity = level.getBlockEntity(position);
                if (blockEntity == null) {
                    throw new IllegalStateException("No block entity exists at " + position);
                }

                CompoundTag fullMetadata = blockEntity.saveWithFullMetadata(level.registryAccess());
                DataComponentMap components = blockEntity.components();
                Tag componentData = McpNbtJson.encodeDataComponents(components, level.registryAccess());
                JsonObject result = new JsonObject();
                JsonObject coordinates = new JsonObject();
                coordinates.addProperty("x", x);
                coordinates.addProperty("y", y);
                coordinates.addProperty("z", z);
                result.add("position", coordinates);
                result.addProperty("type", blockEntityTypeId(blockEntity.getType()));
                result.add("nbt", McpNbtJson.toJson(fullMetadata));
                result.addProperty("snbt", fullMetadata.toString());
                result.add("data_components", McpNbtJson.toJson(componentData));
                result.addProperty("data_components_snbt", componentData.toString());
                return result;
            });
        }

        private String blockEntityTypeId(BlockEntityType<?> type) {
            net.minecraft.resources.ResourceLocation key = BlockEntityType.getKey(type);
            if (key == null) {
                throw new IllegalStateException("Block entity type is not registered: " + type);
            }
            return key.toString();
        }

        private void runOnClientThread(ThrowingAction action) throws Exception {
            callOnClientThread(() -> {
                action.run();
                return Boolean.TRUE;
            });
        }

        private <T> T callOnClientThread(ClientAction<T> action) throws Exception {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.isSameThread()) {
                return action.call();
            }
            AtomicReference<ClientActionState> state = new AtomicReference<>(ClientActionState.QUEUED);
            CompletableFuture<T> result = new CompletableFuture<>();
            minecraft.execute(() -> {
                if (!state.compareAndSet(ClientActionState.QUEUED, ClientActionState.RUNNING)) {
                    return;
                }
                try {
                    result.complete(action.call());
                } catch (Exception exception) {
                    result.completeExceptionally(exception);
                } finally {
                    state.set(ClientActionState.COMPLETED);
                }
            });
            try {
                return result.get(CLIENT_ACTION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                state.compareAndSet(ClientActionState.QUEUED, ClientActionState.CANCELLED);
                Thread.currentThread().interrupt();
                throw exception;
            } catch (TimeoutException exception) {
                if (state.compareAndSet(ClientActionState.QUEUED, ClientActionState.CANCELLED)) {
                    throw new TimeoutException("Minecraft client action timed out before it started");
                }
                throw new TimeoutException("Minecraft client action timed out after it started; outcome is unknown");
            }
        }
    }

    private enum ClientActionState {
        QUEUED,
        RUNNING,
        COMPLETED,
        CANCELLED
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }

    @FunctionalInterface
    private interface ClientAction<T> {
        T call() throws Exception;
    }
}
