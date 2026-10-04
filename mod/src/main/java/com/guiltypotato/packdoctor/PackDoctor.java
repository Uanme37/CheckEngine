package com.guiltypotato.packdoctor;

import com.guiltypotato.packdoctor.core.PackDoctorCore;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

@Mod(PackDoctor.MOD_ID)
public final class PackDoctor {
    public static final String MOD_ID = "packdoctor";
    public static final Logger LOGGER = LogUtils.getLogger();

    public PackDoctor(IEventBus modEventBus, ModContainer modContainer) {
        LOGGER.info(PackDoctorCore.hello());
        // Phase 4: boot check + warning screen, /packdoctor scan command
    }
}
