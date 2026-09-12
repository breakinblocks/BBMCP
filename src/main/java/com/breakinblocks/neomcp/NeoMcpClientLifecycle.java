package com.breakinblocks.neomcp;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.GameShuttingDownEvent;

@EventBusSubscriber(modid = NeoMcp.MOD_ID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class NeoMcpClientLifecycle {
    private NeoMcpClientLifecycle() {
    }

    @SubscribeEvent
    public static void onGameShuttingDown(GameShuttingDownEvent event) {
        NeoMcpClient.stopServer();
    }
}
