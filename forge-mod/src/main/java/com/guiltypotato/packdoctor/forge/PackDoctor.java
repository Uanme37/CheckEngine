package com.guiltypotato.packdoctor.forge;

import com.mojang.logging.LogUtils;
import net.minecraft.commands.Commands;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

/** Forge 1.20.1 entry point. Same features as the NeoForge mod: boot check, warning screen, /packdoctor scan. */
@Mod(PackDoctor.MOD_ID)
public final class PackDoctor {
    public static final String MOD_ID = "packdoctor";
    public static final Logger LOGGER = LogUtils.getLogger();

    public PackDoctor() {
        BootCheck.start();
        MinecraftForge.EVENT_BUS.addListener(PackDoctor::registerCommands);
        // Dedicated servers measure once they're up (players measure at the title screen, see ClientEvents).
        MinecraftForge.EVENT_BUS.addListener((ServerStartedEvent e) -> {
            if (e.getServer().isDedicatedServer()) BootCheck.recordMemory();
        });
    }

    private static void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("packdoctor")
                .requires(s -> s.hasPermission(2))
                .then(Commands.literal("scan").executes(ctx -> DataScan.run(ctx.getSource()))));
    }
}
