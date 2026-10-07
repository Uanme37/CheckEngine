package com.guiltypotato.checkengine;

import com.mojang.logging.LogUtils;
import net.minecraft.commands.Commands;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.slf4j.Logger;

@Mod(CheckEngine.MOD_ID)
public final class CheckEngine {
    public static final String MOD_ID = "checkengine";
    public static final Logger LOGGER = LogUtils.getLogger();

    public CheckEngine(IEventBus modEventBus, ModContainer modContainer) {
        BootCheck.start();
        NeoForge.EVENT_BUS.addListener(CheckEngine::registerCommands);
        // Dedicated servers measure once they're up (players measure at the title screen, see ClientEvents).
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent e) -> {
            if (e.getServer().isDedicatedServer()) BootCheck.recordMemory();
        });
    }

    private static void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("checkengine")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("scan").executes(ctx -> DataScan.run(ctx.getSource()))));
    }
}
