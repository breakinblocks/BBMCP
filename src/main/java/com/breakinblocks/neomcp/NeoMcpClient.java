package com.breakinblocks.neomcp;

import com.breakinblocks.neomcp.actions.ActionResult;
import com.breakinblocks.neomcp.actions.ActionStatus;
import com.breakinblocks.neomcp.actions.ClientActionController;
import com.breakinblocks.neomcp.mcp.McpHttpServer;
import com.breakinblocks.neomcp.mcp.McpNbtJson;
import com.breakinblocks.neomcp.mcp.McpToolExecutor;
import com.breakinblocks.neomcp.config.NeoMcpConfig;
import com.breakinblocks.neomcp.ftbquests.FtbQuestsIntegration;
import com.breakinblocks.neomcp.kubejs.NeoMcpClientBridge;
import com.breakinblocks.neomcp.recipe.ClientRecipeCatalog;
import com.breakinblocks.neomcp.recipe.ClientRecipeViewerSupport;
import com.breakinblocks.neomcp.recipe.IRecipeViewerAdapter;
import com.breakinblocks.neomcp.recipe.RecipeCatalog;
import com.breakinblocks.neomcp.recipe.RecipeAnalysis;
import com.breakinblocks.neomcp.recipe.RecipeViewerAdapterRegistry;
import com.breakinblocks.neomcp.util.JsonResultWriter;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.serialization.JsonOps;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.ModList;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforgespi.language.IModInfo;
import net.neoforged.neoforge.client.ClientHooks;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Base64;
import java.util.Locale;
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
    private static McpHttpServer server;
    private static MinecraftToolExecutor toolExecutor;
    private static volatile ClientCapture clientCapture;

    private NeoMcpClient() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        NeoForge.EVENT_BUS.addListener(NeoMcpClient::onClientTick);
        event.enqueueWork(NeoMcpClient::startServer);
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer localPlayer = minecraft.player;
        clientCapture = new ClientCapture(
                minecraft,
                minecraft.getSingleplayerServer(),
                localPlayer == null ? null : localPlayer.getUUID());
    }

    private static void startServer() {
        if (!NeoMcpConfig.isServerEnabled()) {
            return;
        }
        if (server != null) {
            throw new IllegalStateException("NeoMCP HTTP server is already running");
        }
        NeoMcpClientBridge.install(NeoMcpClient::captureKubejsClientContext);
        MinecraftToolExecutor newToolExecutor = new MinecraftToolExecutor();
        McpHttpServer newServer = new McpHttpServer(newToolExecutor);
        try {
            newServer.start();
            server = newServer;
            toolExecutor = newToolExecutor;
        } catch (java.io.IOException | RuntimeException exception) {
            newServer.close();
            newToolExecutor.close();
            NeoMcpClientBridge.clear();
            throw new IllegalStateException(
                    "Unable to start NeoMCP HTTP server on localhost:" + NeoMcpConfig.port(),
                    exception);
        }
    }

    static void stopServer() {
        try {
            if (server != null) {
                server.close();
                server = null;
            }
            if (toolExecutor != null) {
                toolExecutor.close();
                toolExecutor = null;
            }
        } finally {
            NeoMcpClientBridge.clear();
            clientCapture = null;
        }
    }

    private static NeoMcpClientBridge.ClientValues captureKubejsClientContext(
            MinecraftServer server) throws Exception {
        if (!server.isSameThread()) {
            throw new IllegalStateException("KubeJS client context must be captured on the server thread");
        }

        ClientCapture capture = clientCapture;
        if (capture == null || capture.minecraft() == null) {
            return new NeoMcpClientBridge.ClientValues(null, null);
        }
        if (capture.integratedServer() != server) {
            throw new IllegalStateException(
                    "The local Minecraft client is not connected to the active integrated server");
        }
        if (capture.playerId() == null) {
            return new NeoMcpClientBridge.ClientValues(null, capture.minecraft());
        }

        ServerPlayer serverPlayer = server.getPlayerList().getPlayer(capture.playerId());
        return new NeoMcpClientBridge.ClientValues(serverPlayer, capture.minecraft());
    }

    private static final class MinecraftToolExecutor implements McpToolExecutor, AutoCloseable {
        private static final String KUBEJS_SCRIPT_FILE = "neomcp_injected.js";
        private final ClientActionController actionController;

        private MinecraftToolExecutor() {
            this.actionController = new ClientActionController(Minecraft.getInstance());
        }

        @Override
        public void close() {
            actionController.close();
        }

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
        public JsonObject listMods() throws Exception {
            return callOnClientThread(() -> {
                List<IModInfo> loadedMods = ModList.get().getMods().stream()
                        .sorted(Comparator.comparing(IModInfo::getModId))
                        .toList();
                JsonArray mods = new JsonArray();
                for (IModInfo mod : loadedMods) {
                    JsonObject modData = new JsonObject();
                    modData.addProperty("id", mod.getModId());
                    modData.addProperty("name", mod.getDisplayName());
                    modData.addProperty("version", mod.getVersion().toString());
                    mods.add(modData);
                }
                JsonObject result = new JsonObject();
                result.addProperty("count", mods.size());
                result.add("mods", mods);
                return result;
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

            int maxLogLines = NeoMcpConfig.maxLogLines();
            ArrayDeque<String> lastLines = new ArrayDeque<>(maxLogLines);
            try (BufferedReader reader = Files.newBufferedReader(logPath, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (lastLines.size() == maxLogLines) {
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
        public JsonObject listLootTables() throws Exception {
            return callOnClientThread(() -> {
                MinecraftServer server = requireIntegratedServer();
                return callOnServerThread(server, () -> {
                    RegistryAccess.Frozen registries = server.reloadableRegistries().get();
                    HolderLookup.RegistryLookup<LootTable> lootTables =
                            registries.lookupOrThrow(Registries.LOOT_TABLE);
                    List<ResourceLocation> ids = lootTables.listElementIds()
                            .map(key -> key.location())
                            .sorted(Comparator.comparing(ResourceLocation::toString))
                            .toList();
                    JsonArray lootTableIds = new JsonArray();
                    for (ResourceLocation id : ids) {
                        lootTableIds.add(id.toString());
                    }
                    JsonObject result = new JsonObject();
                    result.addProperty("count", lootTableIds.size());
                    result.add("loot_tables", lootTableIds);
                    return result;
                });
            });
        }

        @Override
        public JsonObject getLootTable(String lootTableId) throws Exception {
            return callOnClientThread(() -> {
                MinecraftServer server = requireIntegratedServer();
                return callOnServerThread(server, () -> {
                    ResourceLocation id = parseResourceLocation(lootTableId, "loot table");
                    RegistryAccess.Frozen registries = server.reloadableRegistries().get();
                    HolderLookup.RegistryLookup<LootTable> lootTables =
                            registries.lookupOrThrow(Registries.LOOT_TABLE);
                    ResourceKey<LootTable> key = ResourceKey.create(Registries.LOOT_TABLE, id);
                    LootTable table = lootTables.get(key)
                            .map(holder -> holder.value())
                            .orElseThrow(() -> new IllegalArgumentException("Unknown loot table: " + id));
                    JsonObject result = new JsonObject();
                    result.addProperty("id", id.toString());
                    result.add("table", encodeLootTable(registries, id, table));
                    return result;
                });
            });
        }

        @Override
        public JsonObject searchLootTables(String itemId) throws Exception {
            return callOnClientThread(() -> {
                MinecraftServer server = requireIntegratedServer();
                return callOnServerThread(server, () -> {
                    ResourceLocation item = parseResourceLocation(itemId, "item");
                    RegistryAccess.Frozen registries = server.reloadableRegistries().get();
                    HolderLookup.RegistryLookup<LootTable> lootTables =
                            registries.lookupOrThrow(Registries.LOOT_TABLE);
                    List<ResourceKey<LootTable>> keys = lootTables.listElementIds()
                            .sorted(Comparator.comparing(key -> key.location().toString()))
                            .toList();
                    JsonArray matches = new JsonArray();
                    JsonArray skippedLootTables = new JsonArray();
                    for (ResourceKey<LootTable> key : keys) {
                        LootTable table = lootTables.get(key)
                                .map(holder -> holder.value())
                                .orElseThrow(() -> new IllegalStateException(
                                        "Loot table registry entry disappeared during search: " + key.location()));
                        JsonElement json;
                        try {
                            json = encodeLootTable(registries, key.location(), table);
                        } catch (RuntimeException exception) {
                            JsonObject skipped = new JsonObject();
                            skipped.addProperty("id", key.location().toString());
                            skipped.addProperty("error", exception.getMessage());
                            skippedLootTables.add(skipped);
                            continue;
                        }
                        if (containsItemIdentifier(json, item.toString())) {
                            matches.add(key.location().toString());
                        }
                    }
                    JsonObject result = new JsonObject();
                    result.addProperty("item_id", item.toString());
                    result.addProperty("count", matches.size());
                    result.add("loot_tables", matches);
                    result.add("skipped_loot_tables", skippedLootTables);
                    return result;
                });
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

        @Override
        public JsonObject lookAt(double x, double y, double z, int durationTicks) throws Exception {
            return callOnClientThread(() -> actionResult(
                    actionController.lookAt(x, y, z, durationTicks)));
        }

        @Override
        public JsonObject jump() throws Exception {
            return callOnClientThread(() -> actionResult(actionController.jump()));
        }

        @Override
        public JsonObject move(String direction, int durationTicks) throws Exception {
            return callOnClientThread(() -> actionResult(actionController.move(
                    ClientActionController.Movement.valueOf(direction.toUpperCase(Locale.ROOT)), durationTicks)));
        }

        @Override
        public JsonObject interact(String target, String hand) throws Exception {
            return callOnClientThread(() -> {
                InteractionHand interactionHand = parseInteractionHand(hand);
                ActionResult action = switch (target) {
                    case "looked_at" -> actionController.interactCrosshair(interactionHand);
                    case "air" -> actionController.interactAir(interactionHand);
                    default -> throw new IllegalArgumentException("Unknown interaction target: " + target);
                };
                return actionResult(action);
            });
        }

        @Override
        public JsonObject getActionStatus(long actionId) throws Exception {
            return callOnClientThread(() -> actionStatus(actionController.status(actionId)));
        }

        @Override
        public JsonObject cancelAction(long actionId) throws Exception {
            return callOnClientThread(() -> actionResult(actionController.cancel(actionId)));
        }

        @Override
        public JsonObject recipeCapabilities() throws Exception {
            return callOnClientThread(() -> {
                List<String> viewers = ClientRecipeViewerSupport.detectedViewers();
                JsonArray viewerIds = new JsonArray();
                viewers.forEach(viewerIds::add);
                JsonObject result = new JsonObject();
                result.addProperty("canonical_recipe_manager", true);
                result.addProperty("recipe_manager_source", "client_synchronized_recipe_manager");
                result.add("detected_viewers", viewerIds);
                result.addProperty("jei_adapter_available", ClientRecipeViewerSupport.jeiRuntimeAvailable());
                result.addProperty("emi_adapter_available", false);
                result.addProperty("rei_adapter_available", false);
                result.addProperty("recipe_card_capture", ClientRecipeViewerSupport.jeiRuntimeAvailable());
                JsonObject limits = new JsonObject();
                limits.addProperty("max_recipe_results", NeoMcpConfig.maxRecipeResults());
                limits.addProperty("max_recipe_inline_bytes", NeoMcpConfig.maxRecipeInlineBytes());
                limits.addProperty("max_recipe_tree_depth", NeoMcpConfig.maxRecipeTreeDepth());
                limits.addProperty("max_recipe_loop_depth", NeoMcpConfig.maxRecipeLoopDepth());
                limits.addProperty("max_recipe_graph_nodes", NeoMcpConfig.maxRecipeGraphNodes());
                result.add("limits", limits);
                return result;
            });
        }

        @Override
        public JsonObject findRecipes(String query, String recipeType, int limit) throws Exception {
            return callOnClientThread(() -> {
                RecipeCatalog catalog = ClientRecipeCatalog.create();
                List<RecipeCatalog.RecipeView> candidates = query.isBlank()
                        ? catalog.list()
                        : catalog.search(query);
                JsonArray recipes = new JsonArray();
                for (RecipeCatalog.RecipeView recipe : candidates) {
                    if (!recipeType.isBlank() && !recipeType.equals(recipe.type())) {
                        continue;
                    }
                    recipes.add(recipe.toJson());
                    if (recipes.size() >= limit) {
                        break;
                    }
                }
                JsonObject result = new JsonObject();
                result.addProperty("query", query);
                result.addProperty("recipe_type", recipeType);
                result.addProperty("count", recipes.size());
                result.addProperty("truncated", recipes.size() >= limit && candidates.size() > recipes.size());
                result.add("recipes", recipes);
                return result;
            });
        }

        @Override
        public JsonObject getRecipe(String recipeId) throws Exception {
            return callOnClientThread(() -> {
                ResourceLocation id = parseResourceLocation(recipeId, "recipe");
                RecipeCatalog.RecipeView recipe = ClientRecipeCatalog.create().get(id);
                JsonObject result = new JsonObject();
                result.addProperty("recipe_id", id.toString());
                result.add("recipe", recipe.toJson());
                return result;
            });
        }

        @Override
        public JsonObject viewRecipe(String recipeId, String viewer, String mode) throws Exception {
            return callOnClientThread(() -> {
                ResourceLocation id = parseResourceLocation(recipeId, "recipe");
                RecipeCatalog catalog = ClientRecipeCatalog.create();
                catalog.get(id);
                List<String> detected = ClientRecipeViewerSupport.detectedViewers();
                String selectedViewer = "auto".equals(viewer)
                        ? detected.stream().findFirst().orElseThrow(
                                () -> new IllegalStateException("No supported recipe viewer is loaded"))
                        : viewer;
                if (!"jei".equals(selectedViewer)) {
                    throw new UnsupportedOperationException(
                            "Recipe viewer adapter is not implemented for: " + selectedViewer);
                }
                if (!detected.contains(selectedViewer)) {
                    throw new IllegalStateException("Requested recipe viewer is not loaded: " + selectedViewer);
                }
                IRecipeViewerAdapter adapter = RecipeViewerAdapterRegistry.create(catalog);
                adapter.openRecipe(id, mode);
                JsonObject result = new JsonObject();
                result.addProperty("recipe_id", id.toString());
                result.addProperty("viewer", adapter.id());
                result.addProperty("mode", mode);
                result.addProperty("opened", true);
                return result;
            });
        }

        @Override
        public JsonObject getRecipeTree(String itemId, int maxDepth) throws Exception {
            return callOnClientThread(() -> {
                ResourceLocation id = parseResourceLocation(itemId, "item");
                IRecipeViewerAdapter adapter = recipeAdapter();
                return adapter.getRecipeTree(id, maxDepth);
            });
        }

        @Override
        public JsonObject getItemUsages(String itemId) throws Exception {
            return callOnClientThread(() -> {
                ResourceLocation id = parseResourceLocation(itemId, "item");
                return recipeAdapter().getItemUsages(id);
            });
        }

        @Override
        public JsonObject getWorkstationRecipes(String machineId) throws Exception {
            return callOnClientThread(() -> {
                ResourceLocation id = parseResourceLocation(machineId, "workstation item");
                return recipeAdapter().getWorkstationRecipes(id);
            });
        }

        @Override
        public JsonObject scanForLoops(String itemId, int maxDepth) throws Exception {
            return callOnClientThread(() -> {
                ResourceLocation id = parseResourceLocation(itemId, "item");
                return recipeAdapter().scanForLoops(id, maxDepth);
            });
        }

        @Override
        public JsonObject captureRecipeCard(String recipeId, boolean savePng) throws Exception {
            return callOnClientThread(() -> {
                Minecraft minecraft = Minecraft.getInstance();
                if (!RenderSystem.isOnRenderThread()) {
                    throw new IllegalStateException("Recipe card capture is not running on the render thread");
                }
                ResourceLocation id = parseResourceLocation(recipeId, "recipe");
                RecipeCatalog catalog = ClientRecipeCatalog.create();
                IRecipeViewerAdapter adapter = RecipeViewerAdapterRegistry.create(catalog);
                if (!"jei".equals(adapter.id())) {
                    throw new IllegalStateException("JEI is required for recipe card capture");
                }
                adapter.openRecipe(id, "recipe");
                int width = NeoMcpConfig.recipeCardWidth();
                int height = NeoMcpConfig.recipeCardHeight();
                int previousFramebuffer = GlStateManager.getBoundFramebuffer();
                TextureTarget target = new TextureTarget(width, height, true, true);
                Path temporaryPng = null;
                NativeImage image = null;
                boolean projectionBackedUp = false;
                boolean modelViewPushed = false;
                boolean targetBound = false;
                Matrix4fStack modelViewStack = RenderSystem.getModelViewStack();
                try {
                    target.setClearColor(0.08F, 0.08F, 0.08F, 1.0F);
                    target.bindWrite(true);
                    targetBound = true;
                    target.clear(true);
                    RenderSystem.viewport(0, 0, width, height);
                    RenderSystem.backupProjectionMatrix();
                    projectionBackedUp = true;
                    modelViewStack.pushMatrix();
                    modelViewPushed = true;
                    RenderSystem.setProjectionMatrix(
                            new Matrix4f().setOrtho(
                                    0.0F,
                                    width,
                                    height,
                                    0.0F,
                                    1000.0F,
                                    ClientHooks.getGuiFarPlane()),
                            VertexSorting.ORTHOGRAPHIC_Z);
                    modelViewStack.translation(
                            0.0F,
                            0.0F,
                            10_000.0F - ClientHooks.getGuiFarPlane());
                    RenderSystem.applyModelViewMatrix();
                    Lighting.setupFor3DItems();
                    net.minecraft.client.gui.GuiGraphics graphics = new net.minecraft.client.gui.GuiGraphics(
                            minecraft, minecraft.renderBuffers().bufferSource());
                    graphics.fill(0, 0, width, height, 0xFF151515);
                    graphics.flush();
                    adapter.renderRecipeCard(id, graphics, width, height);
                    graphics.flush();
                    minecraft.renderBuffers().bufferSource().endBatch();
                    image = Screenshot.takeScreenshot(target);
                    temporaryPng = Files.createTempFile(
                            minecraft.gameDirectory.toPath().toAbsolutePath().normalize(),
                            "neomcp-recipe-card-", ".png");
                    image.writeToFile(temporaryPng);
                    byte[] png = Files.readAllBytes(temporaryPng);
                    if (png.length == 0) {
                        throw new IllegalStateException("Recipe card capture produced an empty PNG payload");
                    }
                    JsonObject result = new JsonObject();
                    result.addProperty("recipe_id", id.toString());
                    result.addProperty("viewer", adapter.id());
                    result.addProperty("width", width);
                    result.addProperty("height", height);
                    result.addProperty("mime_type", "image/png");
                    result.addProperty("saved_to_screenshots", savePng);
                    if (savePng) {
                        Path screenshotPath = saveRecipeCardPng(minecraft, png);
                        result.addProperty("screenshot_path", screenshotPath.toString());
                    }
                    result.addProperty("png_base64", Base64.getEncoder().encodeToString(png));
                    return result;
                } finally {
                    if (image != null) {
                        image.close();
                    }
                    try {
                        if (modelViewPushed) {
                            modelViewStack.popMatrix();
                            RenderSystem.applyModelViewMatrix();
                        }
                    } finally {
                        if (projectionBackedUp) {
                            RenderSystem.restoreProjectionMatrix();
                        }
                    }
                    try {
                        if (targetBound) {
                            target.unbindWrite();
                        }
                    } finally {
                        try {
                            target.destroyBuffers();
                        } finally {
                            GlStateManager._glBindFramebuffer(36160, previousFramebuffer);
                            RenderSystem.viewport(
                                    0,
                                    0,
                                    minecraft.getWindow().getWidth(),
                                    minecraft.getWindow().getHeight());
                        }
                    }
                    if (temporaryPng != null) {
                        Files.deleteIfExists(temporaryPng);
                    }
                }
            });
        }

        @Override
        public JsonObject dumpRecipes(String modNamespace, String recipeType, boolean saveJson) throws Exception {
            return callOnClientThread(() -> {
                JsonObject result = new RecipeAnalysis(ClientRecipeCatalog.create())
                        .dumpRecipes(modNamespace, recipeType,
                                saveJson ? Integer.MAX_VALUE : NeoMcpConfig.maxRecipeResults(),
                                saveJson ? Long.MAX_VALUE : NeoMcpConfig.maxRecipeInlineBytes());
                return saveJson ? saveRecipeResult(result, "recipes", "catalog", "recipes") : result;
            });
        }

        @Override
        public JsonObject analyzeRecipeComplexity(String itemId, boolean saveJson) throws Exception {
            return callOnClientThread(() -> {
                ResourceLocation id = parseResourceLocation(itemId, "item");
                JsonObject result = new RecipeAnalysis(ClientRecipeCatalog.create()).analyzeItem(id);
                return saveJson ? saveRecipeResult(result, "analysis", "complexity", null) : result;
            });
        }

        @Override
        public JsonObject checkRecipeCycles(String itemId, boolean saveJson) throws Exception {
            return callOnClientThread(() -> {
                ResourceLocation id = parseResourceLocation(itemId, "item");
                JsonObject result = new RecipeAnalysis(ClientRecipeCatalog.create()).detectCycles(id);
                return saveJson ? saveRecipeResult(result, "analysis", "cycles", "cycles") : result;
            });
        }

        @Override
        public JsonObject findUnderutilizedItems(String modNamespace, boolean saveJson) throws Exception {
            return callOnClientThread(() -> {
                JsonObject result = new RecipeAnalysis(ClientRecipeCatalog.create())
                        .underutilizedItems(modNamespace,
                                saveJson ? Integer.MAX_VALUE : NeoMcpConfig.maxRecipeResults());
                return saveJson ? saveRecipeResult(result, "analysis", "underutilized", "items") : result;
            });
        }

        private JsonObject saveRecipeResult(
                JsonObject result, String operation, String subdirectory, String largeField)
                throws IOException {
            JsonResultWriter.DumpMetadata metadata = JsonResultWriter.write(
                    result, operation, subdirectory, Minecraft.getInstance().gameDirectory.toPath());
            if (largeField != null) {
                result.remove(largeField);
            }
            result.addProperty("saved_to_file", true);
            result.addProperty("file_path", metadata.path().toString());
            result.addProperty("file_format", "json");
            result.addProperty("file_size_bytes", metadata.sizeBytes());
            return result;
        }

        private Path saveRecipeCardPng(Minecraft minecraft, byte[] png) throws IOException {
            Path gameDirectory = minecraft.gameDirectory.toPath().toAbsolutePath().normalize().toRealPath();
            Path screenshotDirectory = gameDirectory.resolve("screenshots").normalize();
            Files.createDirectories(screenshotDirectory);
            if (Files.isSymbolicLink(screenshotDirectory)) {
                throw new IllegalStateException("Recipe card screenshot directory must not be a symbolic link");
            }
            Path resolvedScreenshotDirectory = screenshotDirectory.toRealPath();
            if (!gameDirectory.equals(resolvedScreenshotDirectory.getParent())) {
                throw new IllegalStateException("Recipe card screenshot directory escaped the game directory");
            }
            Path screenshotPath = screenshotDirectory.resolve("neomcp_recipe_"
                    + UUID.randomUUID() + ".png").normalize();
            if (!resolvedScreenshotDirectory.equals(screenshotPath.getParent())) {
                throw new IllegalStateException("Recipe card screenshot path escaped its target directory");
            }
            Files.write(screenshotPath, png, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            if (!Files.isRegularFile(screenshotPath) || Files.size(screenshotPath) == 0L) {
                throw new IllegalStateException("Recipe card screenshot was not written: " + screenshotPath);
            }
            return screenshotPath;
        }

        private IRecipeViewerAdapter recipeAdapter() {
            return RecipeViewerAdapterRegistry.create(ClientRecipeCatalog.create());
        }

        private InteractionHand parseInteractionHand(String value) {
            return switch (value) {
                case "main_hand" -> InteractionHand.MAIN_HAND;
                case "off_hand" -> InteractionHand.OFF_HAND;
                default -> throw new IllegalArgumentException("Unknown interaction hand: " + value);
            };
        }

        private JsonObject actionResult(ActionResult action) {
            JsonObject result = new JsonObject();
            result.addProperty("action_id", action.id());
            result.addProperty("state", action.state().name().toLowerCase(Locale.ROOT));
            result.addProperty("message", action.message());
            return result;
        }

        private JsonObject actionStatus(ActionStatus status) {
            JsonObject result = new JsonObject();
            result.addProperty("action_id", status.id());
            result.addProperty("state", status.state().name().toLowerCase(Locale.ROOT));
            result.addProperty("elapsed_ticks", status.elapsedTicks());
            result.addProperty("message", status.message());
            return result;
        }

        private String blockEntityTypeId(BlockEntityType<?> type) {
            net.minecraft.resources.ResourceLocation key = BlockEntityType.getKey(type);
            if (key == null) {
                throw new IllegalStateException("Block entity type is not registered: " + type);
            }
            return key.toString();
        }

        private MinecraftServer requireIntegratedServer() {
            MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
            if (server == null) {
                throw new IllegalStateException("No integrated server is running; loot tables are server-side data");
            }
            return server;
        }

        private ResourceLocation parseResourceLocation(String value, String description) {
            ResourceLocation id = ResourceLocation.tryParse(value);
            if (id == null) {
                throw new IllegalArgumentException("Invalid " + description + " ResourceLocation: " + value);
            }
            return id;
        }

        private JsonElement encodeLootTable(
                RegistryAccess.Frozen registries,
                ResourceLocation id,
                LootTable table) {
            return LootTable.DIRECT_CODEC.encodeStart(
                            registries.createSerializationContext(JsonOps.INSTANCE),
                            table)
                    .getOrThrow(error -> new IllegalStateException(
                            "Unable to encode loot table " + id + ": " + error));
        }

        private boolean containsItemIdentifier(JsonElement element, String itemId) {
            if (element.isJsonObject()) {
                JsonObject object = element.getAsJsonObject();
                for (String key : object.keySet()) {
                    JsonElement value = object.get(key);
                    if (isItemIdentifierKey(key)
                            && value.isJsonPrimitive()
                            && value.getAsJsonPrimitive().isString()
                            && itemId.equals(value.getAsString())) {
                        return true;
                    }
                    if (containsItemIdentifier(value, itemId)) {
                        return true;
                    }
                }
                return false;
            }
            if (element.isJsonArray()) {
                for (JsonElement value : element.getAsJsonArray()) {
                    if (containsItemIdentifier(value, itemId)) {
                        return true;
                    }
                }
            }
            return false;
        }

        private boolean isItemIdentifierKey(String key) {
            return "name".equals(key) || "item".equals(key) || "id".equals(key);
        }

        private void runOnClientThread(ThrowingAction action) throws Exception {
            callOnClientThread(() -> {
                action.run();
                return Boolean.TRUE;
            });
        }

        private <T> T callOnServerThread(MinecraftServer server, ServerAction<T> action) throws Exception {
            if (server.isSameThread()) {
                return action.call();
            }
            AtomicReference<ServerActionState> state = new AtomicReference<>(ServerActionState.QUEUED);
            CompletableFuture<T> result = new CompletableFuture<>();
            server.execute(() -> {
                if (!state.compareAndSet(ServerActionState.QUEUED, ServerActionState.RUNNING)) {
                    return;
                }
                try {
                    result.complete(action.call());
                } catch (Exception exception) {
                    result.completeExceptionally(exception);
                } finally {
                    state.set(ServerActionState.COMPLETED);
                }
            });
            try {
                return result.get(NeoMcpConfig.actionTimeoutSeconds(), TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                state.compareAndSet(ServerActionState.QUEUED, ServerActionState.CANCELLED);
                Thread.currentThread().interrupt();
                throw exception;
            } catch (TimeoutException exception) {
                if (state.compareAndSet(ServerActionState.QUEUED, ServerActionState.CANCELLED)) {
                    throw new TimeoutException("Minecraft server action timed out before it started");
                }
                throw new TimeoutException("Minecraft server action timed out after it started; outcome is unknown");
            }
        }
    }

    private static <T> T callOnClientThread(ClientAction<T> action) throws Exception {
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
                return result.get(NeoMcpConfig.actionTimeoutSeconds(), TimeUnit.SECONDS);
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

    private record ClientCapture(
            Object minecraft,
            MinecraftServer integratedServer,
            UUID playerId) {
    }

    private enum ClientActionState {
        QUEUED,
        RUNNING,
        COMPLETED,
        CANCELLED
    }

    private enum ServerActionState {
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

    @FunctionalInterface
    private interface ServerAction<T> {
        T call() throws Exception;
    }
}
