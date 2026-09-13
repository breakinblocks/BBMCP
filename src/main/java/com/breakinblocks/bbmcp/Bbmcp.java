package com.breakinblocks.bbmcp;

import com.breakinblocks.bbmcp.config.BbmcpConfig;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

@Mod(Bbmcp.MOD_ID)
public final class Bbmcp {
    public static final String MOD_ID = "bbmcp";
    private static final Logger LOGGER = LogUtils.getLogger();

    public Bbmcp(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.CLIENT, BbmcpConfig.SPEC);
        LOGGER.info("BBMCP initialized");
    }
}
