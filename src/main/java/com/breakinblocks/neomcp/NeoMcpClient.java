package com.breakinblocks.neomcp;

import com.breakinblocks.neomcp.mcp.McpHttpServer;
import com.breakinblocks.neomcp.mcp.McpNbtJson;
import com.breakinblocks.neomcp.mcp.McpToolExecutor;
import com.breakinblocks.neomcp.ftbquests.FtbQuestsIntegration;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.ModList;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

import java.io.BufferedReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.UUID;

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
        private static final String KUBEJS_SCRIPT_FILE = "neomcp_injected.js";

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

        @Override
        public JsonObject inspectItemComponents() throws Exception {
            return callOnClientThread(() -> {
                Minecraft minecraft = Minecraft.getInstance();
                LocalPlayer player = minecraft.player;
                if (player == null) {
                    throw new IllegalStateException("Minecraft local player is unavailable");
                }
                Level level = minecraft.level;
                if (level == null) {
                    throw new IllegalStateException("Minecraft client level is unavailable");
                }

                ItemStack itemStack = player.getMainHandItem();
                ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(itemStack.getItem());
                if (itemId == null) {
                    throw new IllegalStateException("Held item is not registered: " + itemStack.getItem());
                }
                Tag stackData = itemStack.saveOptional(level.registryAccess());
                DataComponentMap components = itemStack.getComponents();
                DataComponentPatch patch = itemStack.getComponentsPatch();
                Tag componentData = McpNbtJson.encodeDataComponents(components, level.registryAccess());
                Tag patchData = McpNbtJson.encodeDataComponentPatch(patch, level.registryAccess());
                JsonObject result = new JsonObject();
                result.addProperty("item", itemId.toString());
                result.addProperty("count", itemStack.getCount());
                result.addProperty("max_count", itemStack.getMaxStackSize());
                result.addProperty("empty", itemStack.isEmpty());
                result.addProperty("name", itemStack.getHoverName().getString());
                result.add("stack", McpNbtJson.toJson(stackData));
                result.addProperty("stack_snbt", stackData.toString());
                result.add("data_components", McpNbtJson.toJson(componentData));
                result.addProperty("data_components_snbt", componentData.toString());
                result.add("data_components_patch", McpNbtJson.toJson(patchData));
                result.addProperty("data_components_patch_snbt", patchData.toString());
                return result;
            });
        }

        @Override
        public JsonObject queryRegistry(String registryName, String namespace) throws Exception {
            return callOnClientThread(() -> {
                Minecraft minecraft = Minecraft.getInstance();
                Level level = minecraft.level;
                if (level == null) {
                    throw new IllegalStateException("Minecraft client level is unavailable");
                }
                ResourceLocation registryId = ResourceLocation.tryParse(registryName);
                if (registryId == null) {
                    throw new IllegalArgumentException("Invalid registry name: " + registryName);
                }
                Registry<?> registry = level.registryAccess().registries()
                        .filter(entry -> entry.key().location().equals(registryId))
                        .map(RegistryAccess.RegistryEntry::value)
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Registry is unavailable in the current level: " + registryId));
                List<ResourceLocation> objectIds = registry.keySet().stream()
                        .filter(id -> namespace.equals(id.getNamespace()))
                        .sorted(Comparator.comparing(ResourceLocation::toString))
                        .toList();
                JsonArray objects = new JsonArray();
                for (ResourceLocation objectId : objectIds) {
                    objects.add(objectId.toString());
                }
                JsonObject result = new JsonObject();
                result.addProperty("registry", registryId.toString());
                result.addProperty("namespace", namespace);
                result.addProperty("count", objectIds.size());
                result.add("objects", objects);
                return result;
            });
        }

        @Override
        public JsonObject readLatestLogs() throws Exception {
            Path logPath = Minecraft.getInstance().gameDirectory.toPath()
                    .resolve("logs")
                    .resolve("latest.log")
                    .normalize();
            if (!Files.isRegularFile(logPath)) {
                throw new IllegalStateException("Latest log file is unavailable: " + logPath);
            }

            ArrayDeque<String> lastLines = new ArrayDeque<>(100);
            try (BufferedReader reader = Files.newBufferedReader(logPath, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (lastLines.size() == 100) {
                        lastLines.removeFirst();
                    }
                    lastLines.addLast(line);
                }
            }

            JsonArray lines = new JsonArray();
            for (String line : lastLines) {
                lines.add(line);
            }
            JsonObject result = new JsonObject();
            result.addProperty("path", logPath.toString());
            result.addProperty("line_count", lines.size());
            result.add("lines", lines);
            result.addProperty("text", String.join("\n", lastLines));
            return result;
        }

        @Override
        public JsonObject injectKubejsScript(String script) throws Exception {
            if (!ModList.get().isLoaded("kubejs")) {
                throw new IllegalStateException("KubeJS is not loaded in this client");
            }
            if (script == null || script.isBlank()) {
                throw new IllegalArgumentException("KubeJS script must be non-blank");
            }

            Path gameDirectory = Minecraft.getInstance().gameDirectory.toPath()
                    .toAbsolutePath()
                    .normalize();
            Path scriptDirectory = gameDirectory.resolve("kubejs")
                    .resolve("server_scripts")
                    .normalize();
            Path scriptPath = scriptDirectory.resolve(KUBEJS_SCRIPT_FILE).normalize();
            if (!scriptDirectory.equals(scriptPath.getParent())) {
                throw new IllegalStateException("KubeJS script path escaped its target directory");
            }
            Files.createDirectories(scriptDirectory);
            Files.writeString(
                    scriptPath,
                    script,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);

            runOnClientThread(() -> {
                ClientPacketListener connection = Minecraft.getInstance().getConnection();
                if (connection == null) {
                    throw new IllegalStateException("Minecraft client is not connected");
                }
                connection.sendCommand("reload");
            });

            JsonObject result = new JsonObject();
            result.addProperty("script_path", scriptPath.toString());
            result.addProperty("reload_command", "/reload");
            result.addProperty("reload_dispatched", true);
            return result;
        }

        @Override
        public JsonObject getNearbyEntities(double x, double y, double z, double radius) throws Exception {
            return callOnClientThread(() -> {
                Minecraft minecraft = Minecraft.getInstance();
                Level level = minecraft.level;
                if (level == null) {
                    throw new IllegalStateException("Minecraft client level is unavailable");
                }
                AABB area = new AABB(
                        x - radius,
                        y - radius,
                        z - radius,
                        x + radius,
                        y + radius,
                        z + radius);
                List<Entity> entities = new ArrayList<>(level.getEntities(
                        (Entity) null,
                        area,
                        entity -> true));
                entities.sort(Comparator.comparing(entity -> entity.getUUID().toString()));

                JsonArray entityData = new JsonArray();
                for (Entity entity : entities) {
                    ResourceLocation typeId = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
                    if (typeId == null) {
                        throw new IllegalStateException("Entity type is not registered: " + entity.getType());
                    }
                    BlockPos blockPosition = entity.blockPosition();
                    CompoundTag state = entity.saveWithoutId(new CompoundTag());
                    JsonObject data = new JsonObject();
                    data.addProperty("type", typeId.toString());
                    data.addProperty("uuid", entity.getUUID().toString());
                    data.addProperty("x", entity.getX());
                    data.addProperty("y", entity.getY());
                    data.addProperty("z", entity.getZ());
                    data.addProperty("block_x", blockPosition.getX());
                    data.addProperty("block_y", blockPosition.getY());
                    data.addProperty("block_z", blockPosition.getZ());
                    data.addProperty("yaw", entity.getYRot());
                    data.addProperty("pitch", entity.getXRot());
                    data.addProperty("on_ground", entity.onGround());
                    data.addProperty("alive", entity.isAlive());
                    data.addProperty("removed", entity.isRemoved());
                    if (entity.hasCustomName()) {
                        data.addProperty("custom_name", entity.getCustomName().getString());
                    }
                    if (entity instanceof LivingEntity livingEntity) {
                        data.addProperty("health", livingEntity.getHealth());
                        data.addProperty("max_health", livingEntity.getMaxHealth());
                        data.addProperty("dead_or_dying", livingEntity.isDeadOrDying());
                    }
                    data.add("state", McpNbtJson.toJson(state));
                    data.addProperty("state_snbt", state.toString());
                    entityData.add(data);
                }

                JsonObject result = new JsonObject();
                result.addProperty("dimension", level.dimension().location().toString());
                result.addProperty("center_x", x);
                result.addProperty("center_y", y);
                result.addProperty("center_z", z);
                result.addProperty("radius", radius);
                result.addProperty("count", entityData.size());
                result.add("entities", entityData);
                return result;
            });
        }

        @Override
        public JsonObject openQuestGui(long id, String objectType) throws Exception {
            return callOnClientThread(() -> {
                if (!ModList.get().isLoaded("ftbquests")) {
                    throw new IllegalStateException("FTB Quests is not loaded in this client");
                }
                return FtbQuestsIntegration.openQuestGui(id, objectType);
            });
        }

        @Override
        public JsonObject getChapterLayout(long chapterId) throws Exception {
            return callOnClientThread(() -> {
                if (!ModList.get().isLoaded("ftbquests")) {
                    throw new IllegalStateException("FTB Quests is not loaded in this client");
                }
                return FtbQuestsIntegration.getChapterLayout(chapterId);
            });
        }

        @Override
        public JsonObject exportChapterCanvas(long chapterId, boolean savePng) throws Exception {
            return callOnClientThread(() -> {
                if (!ModList.get().isLoaded("ftbquests")) {
                    throw new IllegalStateException("FTB Quests is not loaded in this client");
                }
                return FtbQuestsIntegration.exportChapterCanvas(chapterId, savePng);
            });
        }

        @Override
        public JsonObject takeScreenshot() throws Exception {
            return callOnClientThread(() -> {
                Minecraft minecraft = Minecraft.getInstance();
                if (!RenderSystem.isOnRenderThread()) {
                    throw new IllegalStateException("Screenshot capture is not running on the render thread");
                }
                RenderTarget renderTarget = minecraft.getMainRenderTarget();
                if (renderTarget.width <= 0 || renderTarget.height <= 0) {
                    throw new IllegalStateException("Minecraft main framebuffer has no drawable dimensions");
                }

                Path screenshotDirectory = minecraft.gameDirectory.toPath()
                        .toAbsolutePath()
                        .normalize()
                        .resolve("screenshots")
                        .normalize();
                Files.createDirectories(screenshotDirectory);
                String fileName = "neomcp_" + UUID.randomUUID() + ".png";
                Path screenshotPath = screenshotDirectory.resolve(fileName).normalize();
                if (!screenshotDirectory.equals(screenshotPath.getParent())) {
                    throw new IllegalStateException("Screenshot path escaped its target directory");
                }
                if (Files.exists(screenshotPath)) {
                    throw new IllegalStateException("Screenshot output path already exists: " + screenshotPath);
                }

                boolean screenActive = minecraft.screen != null;
                NativeImage image = Screenshot.takeScreenshot(renderTarget);
                try {
                    image.writeToFile(screenshotPath);
                } finally {
                    image.close();
                }
                if (!Files.isRegularFile(screenshotPath) || Files.size(screenshotPath) == 0L) {
                    throw new IllegalStateException("Screenshot file was not written: " + screenshotPath);
                }

                JsonObject result = new JsonObject();
                result.addProperty("path", screenshotPath.toString());
                result.addProperty("screen_active", screenActive);
                result.addProperty("ui_included", screenActive);
                result.addProperty("width", renderTarget.width);
                result.addProperty("height", renderTarget.height);
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
