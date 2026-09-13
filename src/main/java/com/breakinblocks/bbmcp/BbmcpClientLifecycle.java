package com.breakinblocks.bbmcp;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import com.breakinblocks.bbmcp.recipe.ClientRecipeCatalog;

@EventBusSubscriber(modid = Bbmcp.MOD_ID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class BbmcpClientLifecycle {
    private BbmcpClientLifecycle() {
    }

    @SubscribeEvent
    public static void onGameShuttingDown(GameShuttingDownEvent event) {
        ClientRecipeCatalog.clear();
        BbmcpClient.stopServer();
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientRecipeCatalog.clear();
    }
}
