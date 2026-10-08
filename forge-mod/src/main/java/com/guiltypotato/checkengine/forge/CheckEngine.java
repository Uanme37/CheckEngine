package com.guiltypotato.checkengine.forge;

import com.guiltypotato.checkengine.core.scan.StartupTimes;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

/** Forge 1.20.1 entry point. Same features as the NeoForge mod: boot check, warning screen, /checkengine scan. */
@Mod(CheckEngine.MOD_ID)
public final class CheckEngine {
    public static final String MOD_ID = "checkengine";
    public static final Logger LOGGER = LogUtils.getLogger();

    public CheckEngine() {
        StartupTimer.install();
        BootCheck.start();
        MinecraftForge.EVENT_BUS.addListener(CheckEngine::registerCommands);
        // Dedicated servers measure once they're up (players measure at the title screen, see ClientEvents).
        MinecraftForge.EVENT_BUS.addListener((ServerStartedEvent e) -> {
            if (e.getServer().isDedicatedServer()) {
                BootCheck.launchSucceeded();
                StartupTimer.finish(true);
            }
        });
    }

    private static void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("checkengine")
                .requires(s -> s.hasPermission(2))
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
                .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_FILE, path))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(path))))), false);
        return 1;
    }
}
