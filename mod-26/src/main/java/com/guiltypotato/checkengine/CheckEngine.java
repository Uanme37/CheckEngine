package com.guiltypotato.checkengine;

import com.guiltypotato.checkengine.core.scan.HelpBundle;
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
import net.neoforged.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.neoforged.fml.loading.FMLPaths;
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
        LoadingClues.add();
        modEventBus.addListener((FMLLoadCompleteEvent e) -> LoadingClues.remove());
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
        // Ops only, except export in singleplayer: that's when a player needs it most.
        event.getDispatcher().register(Commands.literal("checkengine")
                .then(Commands.literal("scan").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(ctx -> DataScan.run(ctx.getSource())))
                .then(Commands.literal("startup").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(ctx -> startup(ctx.getSource())))
                .then(Commands.literal("export")
                        .requires(s -> Commands.<CommandSourceStack>hasPermission(Commands.LEVEL_GAMEMASTERS).test(s)
                                || !s.getServer().isDedicatedServer())
                        .executes(ctx -> export(ctx.getSource()))));
    }

    /** /checkengine export: one zip with the reports, mod list and logs, to send to whoever is helping. */
    private static int export(CommandSourceStack source) {
        try {
            String path = HelpBundle.create(FMLPaths.GAMEDIR.get(), BootCheck.get()).toAbsolutePath().toString();
            source.sendSuccess(() -> Component.literal("Check Engine: help file saved: ").append(Component.literal(
                    path.substring(path.lastIndexOf(java.io.File.separatorChar) + 1)).withStyle(s -> s.withUnderlined(true)
                    .withClickEvent(new ClickEvent.OpenFile(BootCheck.outputFolder().toString()))
                    .withHoverEvent(new HoverEvent.ShowText(Component.literal(path))))), false);
            source.sendSuccess(() -> Component.literal("Send it to whoever is helping you. Your Windows user name, "
                    + "player name and IP addresses were blanked out."), false);
            return 1;
        } catch (java.io.IOException e) {
            source.sendFailure(Component.literal("Check Engine: couldn't make the help file: " + e.getMessage()));
            return 0;
        }
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
