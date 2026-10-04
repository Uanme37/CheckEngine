package com.guiltypotato.packdoctor;

import com.mojang.logging.LogUtils;
import net.minecraft.commands.Commands;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.slf4j.Logger;

@Mod(PackDoctor.MOD_ID)
public final class PackDoctor {
    public static final String MOD_ID = "packdoctor";
    public static final Logger LOGGER = LogUtils.getLogger();

    public PackDoctor(IEventBus modEventBus, ModContainer modContainer) {
        BootCheck.start();
        NeoForge.EVENT_BUS.addListener(PackDoctor::registerCommands);
        // Dedicated servers measure once they're up (players measure at the title screen, see ClientEvents).
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent e) -> {
            if (e.getServer().isDedicatedServer()) BootCheck.recordMemory();
        });
    }

    private static void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("packdoctor")
                .requires(s -> s.hasPermission(2))
                .then(Commands.literal("scan").executes(ctx -> DataScan.run(ctx.getSource()))));
    }
}
