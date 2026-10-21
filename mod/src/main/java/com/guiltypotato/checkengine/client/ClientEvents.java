package com.guiltypotato.checkengine.client;

import com.guiltypotato.checkengine.BootCheck;
import com.guiltypotato.checkengine.CheckEngine;
import com.guiltypotato.checkengine.StartupTimer;
import com.guiltypotato.checkengine.core.crash.CrashDoctor.LastCrash;
import com.guiltypotato.checkengine.core.scan.Finding;
import com.guiltypotato.checkengine.core.scan.Report;
import com.guiltypotato.checkengine.core.strip.StripMine;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.gui.ModMismatchDisconnectedScreen;

@EventBusSubscriber(modid = CheckEngine.MOD_ID, value = Dist.CLIENT)
public final class ClientEvents {
    private static boolean checked;
    private static boolean crashSeen;
    private static boolean openReport;
    /** The kick screen already explained (each one only once). */
    private static Screen joinExplained;

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
        // Kicked for a mod mismatch: say which mods, once (the Details button opens NeoForge's own screen).
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
        fixedToast(report);
        Screen next = title;
        if (report != null) {
            // Quiet mode: the pack maker can hide warnings players don't need
            List<Finding> findings = com.guiltypotato.checkengine.core.scan.PackInfo.load(
                    BootCheck.outputFolder().getParent()).visible(BootCheck.worthShowing(report)).stream()
                    // the backup screen already says a mod went back a version: don't say it twice
                    .filter(f -> !(f.code().equals(com.guiltypotato.checkengine.core.scan.Changes.DOWNGRADE_CODE)
                            && DowngradeScreen.willShow(report.changes()))).toList();
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
        // A mod went back to an older version: offer a backup of the worlds played last before one is opened.
        if (crash == null && report != null) {
            Screen down = DowngradeScreen.of(next, report.changes());
            if (down != null) next = down;
        }
        // A kick (Invalid player data) last session that the kick screen didn't explain
        if (crash == null) {
            Screen join = JoinFailScreen.lastSession(next);
            if (join != null) next = join;
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
            explainJoinKick(event);
            // Another mod (FancyMenu...) put the title screen over our backup screen before it was answered: back on top
            if (event.getScreen() instanceof TitleScreen && DowngradeScreen.showing != null
                    && Minecraft.getInstance().screen != DowngradeScreen.showing && downgradeReopens++ < 3) {
                Screen ours = DowngradeScreen.showing;
                CheckEngine.LOGGER.info("Check Engine: the title screen covered the backup screen, opening it again");
                Minecraft.getInstance().execute(() -> Minecraft.getInstance().setScreen(ours));
                return;
            }
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

    /** Kicked with "Invalid player data": open the explanation over the kick screen, once. */
    private static void explainJoinKick(ScreenEvent.Init.Post event) {
        Screen s = event.getScreen();
        if (!(s instanceof net.minecraft.client.gui.screens.DisconnectedScreen) || s == joinExplained) return;
        String reason = net.minecraft.client.resources.language.I18n.get("multiplayer.disconnect.invalid_player_data");
        boolean found = false;
        for (var l : event.getListenersList()) {
            if (l instanceof net.minecraft.client.gui.components.AbstractWidget w && w.getMessage().getString().contains(reason)) {
                found = true;
                break;
            }
        }
        if (!found) return;
        joinExplained = s;
        CheckEngine.LOGGER.info("Check Engine: kicked with Invalid player data, explaining it");
        Minecraft.getInstance().execute(() -> Minecraft.getInstance().setScreen(JoinFailScreen.of(s, true)));
    }

    private static boolean fixedChecked;
    private static int downgradeReopens;

    /** "Fixed!": a problem or warning from the last launch is gone. Once per launch, never during Strip Mine. */
    private static void fixedToast(Report report) {
        if (fixedChecked || report == null || StripMine.running(BootCheck.outputFolder()) != null) return;
        fixedChecked = true;
        java.util.List<String> fixed = com.guiltypotato.checkengine.core.scan.FixedProblems.update(BootCheck.outputFolder(),
                BootCheck.worthShowing(report));
        if (fixed.isEmpty()) return;
        CheckEngine.LOGGER.info("Check Engine: fixed since last launch: {}", fixed);
        Minecraft.getInstance().execute(() -> net.minecraft.client.gui.components.toasts.SystemToast.add(Minecraft.getInstance().getToasts(),
                    new net.minecraft.client.gui.components.toasts.SystemToast.SystemToastId(10000L),
                net.minecraft.network.chat.Component.literal("Check Engine: Fixed!"),
                net.minecraft.network.chat.Component.literal(com.guiltypotato.checkengine.core.scan.FixedProblems.toastText(fixed))));
    }

    /** "Open report" in the scan's chat line runs this; the screen opens on the next tick, after chat closes. */
    @SubscribeEvent
    public static void onClientCommands(net.neoforged.neoforge.client.event.RegisterClientCommandsEvent event) {
        event.getDispatcher().register(net.minecraft.commands.Commands.literal("cereport").executes(c -> {
            openReport = true;
            return 1;
        }));
    }

    @SubscribeEvent
    public static void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        if (CheckEngine.joinRequested) { // /checkengine join in singleplayer
            CheckEngine.joinRequested = false;
            Minecraft.getInstance().setScreen(JoinFailScreen.of(null, false));
            return;
        }
        if (!openReport && !CheckEngine.reportRequested) return;
        openReport = false;
        CheckEngine.reportRequested = false;
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
