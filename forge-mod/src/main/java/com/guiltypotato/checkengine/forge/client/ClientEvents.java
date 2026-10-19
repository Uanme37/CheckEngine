package com.guiltypotato.checkengine.forge.client;

import com.guiltypotato.checkengine.forge.BootCheck;
import com.guiltypotato.checkengine.forge.CheckEngine;
import com.guiltypotato.checkengine.forge.StartupTimer;
import com.guiltypotato.checkengine.core.crash.CrashDoctor.LastCrash;
import com.guiltypotato.checkengine.core.scan.Finding;
import com.guiltypotato.checkengine.core.scan.Report;
import com.guiltypotato.checkengine.core.strip.StripMine;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.client.gui.ModMismatchDisconnectedScreen;

@Mod.EventBusSubscriber(modid = CheckEngine.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ClientEvents {
    private static boolean checked;
    private static boolean crashSeen;
    private static boolean openReport;

    private ClientEvents() {}

    /** The first time the title screen opens, show the boot check's problems in front of it. */
    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        // Never be the reason the game crashes: anything unexpected is logged and the game goes on as normal.
        try {
            opening(event);
        } catch (Throwable t) {
            CheckEngine.LOGGER.error("Check Engine: couldn't show its screen, carrying on without it", t);
        }
    }

    private static void opening(ScreenEvent.Opening event) {
        // During a Strip Mine round, the loader's warnings screen only lists what the test switched off.
        if (StripMineScreens.skipWarnings(event.getNewScreen())) {
            event.setCanceled(true);
            return;
        }
        // Kicked for a mod mismatch: say which mods, once (the Details button opens Forge's own screen).
        if (event.getNewScreen() instanceof ModMismatchDisconnectedScreen mismatch
                && !(event.getCurrentScreen() instanceof JoinMismatchScreen)) {
            Screen ours = JoinMismatchScreen.replace(mismatch);
            if (ours != null) event.setNewScreen(ours);
            return;
        }
        if (!(event.getNewScreen() instanceof TitleScreen title)) return;
        boolean first = !checked;
        checked = true;
        if (first) {
            BootCheck.launchSucceeded();
            StartupTimer.finish(false);
        }
        // Strip Mine: each round's result, or the answer, goes in front of everything else.
        Screen strip = StripMineScreens.onTitle(title, event.getCurrentScreen());
        if (strip != null) {
            event.setNewScreen(strip);
            return;
        }
        if (!first || StripMine.running(BootCheck.outputFolder()) != null) return;
        Report report = BootCheck.get();
        Screen next = title;
        if (report != null) {
            // Quiet mode: the pack maker can hide warnings players don't need
            List<Finding> findings = com.guiltypotato.checkengine.core.scan.PackInfo.load(
                    BootCheck.outputFolder().getParent()).visible(BootCheck.worthShowing(report));
            if (!findings.isEmpty() && !BootWarningScreen.dismissed(findings)) next = new BootWarningScreen(title, findings);
        }
        // Crash Doctor first: if the last game crashed, that's what the player wants to know about.
        LastCrash crash = BootCheck.lastCrash();
        // After a pack update, once: what's new, updated and gone (Crash Doctor comes first if the last game crashed).
        if (crash == null && report != null) {
            Screen updated = PackUpdatedScreen.of(next, report.changes());
            if (updated != null) next = updated;
        }
        // A world still uses a mod that's gone: say what it loses before it's opened (in front of the update notes).
        if (crash == null && report != null) {
            Screen worlds = SafeRemoveScreen.of(next, BootCheck.worldsUsingRemovedMods(), report.changes());
            if (worlds != null) next = worlds;
        }
        if (crash != null) next = new CrashDoctorScreen(next, report == null ? crash : crash.withChanges(report.changes()));
        if (next != title) event.setNewScreen(next);
    }

    /**
     * Backup for Crash Doctor: in some big packs another mod puts the plain title screen back over ours before it's
     * seen. If the title screen gets drawn while an unseen crash is waiting, open Crash Doctor on top of it.
     */
    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        try {
            if (event.getScreen() instanceof CrashDoctorScreen) {
                crashSeen = true;
                return;
            }
            if (!(event.getScreen() instanceof TitleScreen title) || !checked || crashSeen) return;
            LastCrash crash = BootCheck.lastCrash();
            if (crash == null || StripMine.running(BootCheck.outputFolder()) != null) return;
            crashSeen = true;
            Report report = BootCheck.get();
            CheckEngine.LOGGER.info("Check Engine: the title screen covered Crash Doctor, opening it again");
            Minecraft.getInstance().execute(() -> Minecraft.getInstance().setScreen(
                    new CrashDoctorScreen(title, report == null ? crash : crash.withChanges(report.changes()))));
        } catch (Throwable t) {
            CheckEngine.LOGGER.error("Check Engine: couldn't show its screen, carrying on without it", t);
        }
    }

    /** "Open report" in the scan's chat line runs this; the screen opens on the next tick, after chat closes. */
    @SubscribeEvent
    public static void onClientCommands(net.minecraftforge.client.event.RegisterClientCommandsEvent event) {
        event.getDispatcher().register(net.minecraft.commands.Commands.literal("cereport").executes(c -> {
            openReport = true;
            return 1;
        }));
    }

    @SubscribeEvent
    public static void onClientTick(net.minecraftforge.event.TickEvent.ClientTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END || !openReport) return;
        openReport = false;
        try {
            Minecraft.getInstance().setScreen(ReportScreen.open());
        } catch (Throwable t) {
            CheckEngine.LOGGER.error("Check Engine: couldn't show the report, it's in checkengine/scan-report.txt", t);
        }
    }

    /**
     * Clicking "[Open report]" in chat. Caught here on the player's side, because a clicked command can go straight
     * to the server (Forge does that), which doesn't know it.
     */
    @SubscribeEvent
    public static void onChatClick(ScreenEvent.MouseButtonPressed.Pre event) {
        try {
            if (!(event.getScreen() instanceof net.minecraft.client.gui.screens.ChatScreen chat)) return;
            net.minecraft.network.chat.Style style = net.minecraft.client.Minecraft.getInstance().gui.getChat().getClickedComponentStyleAt(event.getMouseX(), event.getMouseY());
            if (style == null || !(style.getClickEvent() != null && style.getClickEvent().getAction() == net.minecraft.network.chat.ClickEvent.Action.RUN_COMMAND && "/cereport".equals(style.getClickEvent().getValue()))) return;
            openReport = true;
            event.setCanceled(true);
        } catch (Throwable t) {
            CheckEngine.LOGGER.error("Check Engine: couldn't open the report", t);
        }
    }
}
