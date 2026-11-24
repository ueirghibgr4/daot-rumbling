package com.rumblingmod;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Entry point. Fabric calls onInitialize() when the game starts. */
public class RumblingMod implements ModInitializer {

    public static final String MOD_ID = "daot_rumbling";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static RumblingConfig CONFIG;

    @Override
    public void onInitialize() {
        CONFIG = RumblingConfig.load();
        RumblingCommand.register();
        RumblingManager.init();
        LOGGER.info("DAOT Rumbling loaded. Use /daot start rumbling");
    }
}
