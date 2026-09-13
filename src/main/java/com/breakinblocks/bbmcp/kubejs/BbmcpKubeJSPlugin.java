package com.breakinblocks.bbmcp.kubejs;

import com.breakinblocks.bbmcp.mcp.McpDynamicToolRegistry;
import com.breakinblocks.bbmcp.mcp.McpHttpServer;
import dev.latvian.mods.kubejs.core.ReloadableServerResourcesKJS;
import dev.latvian.mods.kubejs.event.EventGroup;
import dev.latvian.mods.kubejs.event.EventGroupRegistry;
import dev.latvian.mods.kubejs.event.EventHandler;
import dev.latvian.mods.kubejs.plugin.KubeJSPlugin;
import dev.latvian.mods.kubejs.script.ScriptManager;
import dev.latvian.mods.kubejs.script.ScriptType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.Objects;

public final class BbmcpKubeJSPlugin implements KubeJSPlugin {
    private static final EventGroup GROUP = EventGroup.of("BbmcpEvents");
    private static final EventHandler REGISTER =
            GROUP.server("register", () -> BbmcpToolRegistrationEvent.class)
                    .exceptionHandler((event, container, throwable) -> {
                        if (event instanceof BbmcpToolRegistrationEvent registrationEvent) {
                            registrationEvent.recordFailure(throwable);
                        }
                        return throwable;
                    });
    @Override
    public void init() {
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onServerStopped);
    }

    @Override
    public void registerEvents(EventGroupRegistry registry) {
        registry.register(GROUP);
    }

    @Override
    public void afterScriptsLoaded(ScriptManager manager) {
        if (manager.scriptType != ScriptType.SERVER) {
            return;
        }

        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        if (activeServerScriptManager(server) != manager) {
            return;
        }
        reloadTools(manager, server);
    }

    private void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        ScriptManager manager = activeServerScriptManager(server);
        if (manager == null) {
            throw new IllegalStateException("KubeJS did not expose the active server script manager");
        }
        reloadTools(manager, server);
    }

    private void onServerStopped(ServerStoppedEvent event) {
        McpDynamicToolRegistry.INSTANCE.clear();
        McpHttpServer.broadcastToolsListChanged();
    }

    private void reloadTools(ScriptManager manager, MinecraftServer server) {
        ServerLevel level = server.overworld();
        if (level == null) {
            throw new IllegalStateException("Cannot load BBMCP KubeJS tools without an overworld");
        }
        McpDynamicToolRegistry registry = McpDynamicToolRegistry.INSTANCE;
        long generation = registry.beginReload();
        BbmcpToolRegistrationEvent registrationEvent = new BbmcpToolRegistrationEvent(
                server,
                level,
                Objects.requireNonNull(manager.contextFactory, "manager.contextFactory"),
                registry,
                generation);
        try {
            REGISTER.post(ScriptType.SERVER, registrationEvent);
            registrationEvent.throwIfFailed();
            registry.publishReload();
        } catch (RuntimeException | Error throwable) {
            registry.abortReload();
            throw throwable;
        }
        McpHttpServer.broadcastToolsListChanged();
    }

    private ScriptManager activeServerScriptManager(MinecraftServer server) {
        MinecraftServer.ReloadableResources resources = server.getServerResources();
        if (resources == null) {
            return null;
        }
        return ((ReloadableServerResourcesKJS) resources.managers()).kjs$getServerScriptManager();
    }
}
