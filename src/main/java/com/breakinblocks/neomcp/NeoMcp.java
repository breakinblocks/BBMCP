package com.breakinblocks.neomcp;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

@Mod(NeoMcp.MOD_ID)
public final class NeoMcp {
    public static final String MOD_ID = "neomcp";
    private static final Logger LOGGER = LogUtils.getLogger();

    public NeoMcp(IEventBus modEventBus, ModContainer modContainer) {
        LOGGER.info("NeoMCP initialized");
    }
}
