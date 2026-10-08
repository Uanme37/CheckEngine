package com.guiltypotato.checkengine;

import com.guiltypotato.checkengine.core.scan.StartupTimes;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
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
        StartupTimer.install();
        BootCheck.start();
        NeoForge.EVENT_BUS.addListener(CheckEngine::registerCommands);
        // Dedicated servers measure once they're up (players measure at the title screen, see ClientEvents).
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent e) -> {
            if (e.getServer().isDedicatedServer()) {
                BootCheck.launchSucceeded();
                StartupTimer.finish(true);
            }
        });
    }

    private static void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("checkengine")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("scan").executes(ctx -> DataScan.run(ctx.getSource())))
                .then(Commands.literal("startup").executes(ctx -> startup(ctx.getSource()))));
    }

    /** /checkengine startup: the last launch's total and its slowest mods, with a link to the full report. */
    private static int startup(CommandSourceStack source) {
        StartupTimes.Data d = StartupTimes.load(BootCheck.outputFolder());
        if (d == null) {
            source.sendFailure(Component.literal("Check Engine: no startup times yet. They're measured on the next launch."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Check Engine: last startup took " + StartupTimes.time(d.totalMs())
                + " (mods " + StartupTimes.time(d.modsMs()) + ", then " + StartupTimes.time(d.finishingMs()) + ")"), false);
        d.mods().stream().limit(5).forEach(m -> source.sendSuccess(() -> Component.literal("  " + m.name() + ": "
                + StartupTimes.time(m.ms())), false));
        String path = BootCheck.outputFolder().resolve(StartupTimes.REPORT_FILE).toAbsolutePath().toString();
        source.sendSuccess(() -> Component.literal("Full report: ").append(Component.literal("checkengine/"
                + StartupTimes.REPORT_FILE).withStyle(s -> s.withUnderlined(true)
                .withClickEvent(new ClickEvent.OpenFile(path))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(path))))), false);
        return 1;
    }
}
