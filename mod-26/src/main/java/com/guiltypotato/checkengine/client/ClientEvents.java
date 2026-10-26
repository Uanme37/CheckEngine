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
    /** True once the player closed the warning screen (or there was nothing to show). */
    static boolean done;
    private static boolean memoryRecorded;
    private static boolean crashShown;
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
        // Strip Mine: each round's result, or the answer, goes in front of everything else. 26.1 can open the title
        // screen again while ours is waiting for an answer: keep ours.
        if (StripMineScreens.open && event.getCurrentScreen() == StripMineScreens.showing) {
            event.setNewScreen(event.getCurrentScreen());
            return;
        }
        Screen strip = StripMineScreens.onTitle(title, event.getCurrentScreen());
        if (strip != null) {
            if (!memoryRecorded) {
                memoryRecorded = true;
                BootCheck.launchSucceeded();
                StartupTimer.finish(false);
            }
            event.setNewScreen(strip);
            return;
        }
        if (StripMine.running(BootCheck.outputFolder()) != null) return;
        if (done) return;
        CheckEngine.LOGGER.debug("Check Engine: title screen opening over {}", event.getCurrentScreen());
        if (!memoryRecorded) {
            memoryRecorded = true;
            BootCheck.launchSucceeded();
            StartupTimer.finish(false);
        }
        // 26.1 can open the title screen more than once while starting up, so keep showing ours until it's closed.
        if (event.getCurrentScreen() instanceof BootWarningScreen || event.getCurrentScreen() instanceof CrashDoctorScreen
                || (event.getCurrentScreen() != null && (event.getCurrentScreen() == PackUpdatedScreen.showing
                        || event.getCurrentScreen() == SafeRemoveScreen.showing
                        || event.getCurrentScreen() == DowngradeScreen.showing))) {
            event.setNewScreen(event.getCurrentScreen());
            return;
        }
        Report report = BootCheck.get();
        fixedToast(report);
        // Quiet mode: the pack maker can hide warnings players don't need
        List<Finding> findings = report == null ? List.of() : com.guiltypotato.checkengine.core.scan.PackInfo.load(
                BootCheck.outputFolder().getParent()).visible(BootCheck.worthShowing(report)).stream()
                    // the backup screen already says a mod went back a version: don't say it twice
                    .filter(f -> !(f.code().equals(com.guiltypotato.checkengine.core.scan.Changes.DOWNGRADE_CODE)
                            && DowngradeScreen.willShow(report.changes()))).toList();
        Screen next = title;
        if (!findings.isEmpty() && !BootWarningScreen.dismissed(findings)) next = new BootWarningScreen(title, findings);
        // Crash Doctor first: if the last game crashed, that's what the player wants to know about (shown once).
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
        if (crash != null && !crashShown) {
            crashShown = true;
            next = crashScreen(next, crash, report);
        }
        if (next == title) {
            done = true;
            return;
        }
        event.setNewScreen(next);
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
            if (!(event.getScreen() instanceof TitleScreen title) || crashSeen || done) return;
            LastCrash crash = BootCheck.lastCrash();
            if (crash == null || StripMine.running(BootCheck.outputFolder()) != null) return;
            crashSeen = true;
            crashShown = true;
            Report report = BootCheck.get();
            CheckEngine.LOGGER.info("Check Engine: the title screen covered Crash Doctor, opening it again");
            Minecraft.getInstance().execute(() -> Minecraft.getInstance().setScreen(
                    crashScreen(title, crash, report)));
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

    /**
     * Crash Doctor for the last crash. A crash from before Check Engine was installed whose mod is gone or a new version
     * since then gets a toast instead ("Looks fixed" / "Might be fixed"), and the game goes on to {@code next}.
     */
    private static Screen crashScreen(Screen next, LastCrash crash, Report report) {
        if (crash.beforeInstall()) {
            String[] toast = crash.sinceToast(crash.since(report));
            if (toast != null) {
                crashSeen = true; // the backup check on the title screen mustn't show it again
                CheckEngine.LOGGER.info("{}: {}", toast[0], toast[1]);
                Minecraft.getInstance().execute(() -> net.minecraft.client.gui.components.toasts.SystemToast.add(
                        Minecraft.getInstance().getToastManager(), new net.minecraft.client.gui.components.toasts.SystemToast.SystemToastId(10000L),
                        net.minecraft.network.chat.Component.literal(toast[0]),
                        net.minecraft.network.chat.Component.literal(toast[1])));
                return next;
            }
        }
        return new CrashDoctorScreen(next, report == null ? crash : crash.withChanges(report.changes()));
    }

    /** "Fixed!": a problem or warning from the last launch is gone. Once per launch, never during Strip Mine. */
    private static void fixedToast(Report report) {
        if (fixedChecked || report == null || StripMine.running(BootCheck.outputFolder()) != null) return;
        fixedChecked = true;
        java.util.List<String> fixed = com.guiltypotato.checkengine.core.scan.FixedProblems.update(BootCheck.outputFolder(),
                BootCheck.worthShowing(report));
        if (fixed.isEmpty()) return;
        CheckEngine.LOGGER.info("Check Engine: fixed since last launch: {}", fixed);
        Minecraft.getInstance().execute(() -> net.minecraft.client.gui.components.toasts.SystemToast.add(Minecraft.getInstance().getToastManager(),
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
            Minecraft mc = Minecraft.getInstance();
            var finder = new net.minecraft.client.gui.ActiveTextCollector.ClickableStyleFinder(chat.getFont(),
                    (int) event.getMouseX(), (int) event.getMouseY());
            mc.gui.getChat().captureClickableText(finder, mc.getWindow().getGuiScaledHeight(), mc.gui.getGuiTicks(),
                    net.minecraft.client.gui.components.ChatComponent.DisplayMode.FOREGROUND);
            net.minecraft.network.chat.Style style = finder.result();
            if (style == null || !(style.getClickEvent() instanceof net.minecraft.network.chat.ClickEvent.RunCommand run && "/cereport".equals(run.command()))) return;
            openReport = true;
            event.setCanceled(true);
        } catch (Throwable t) {
            CheckEngine.LOGGER.error("Check Engine: couldn't open the report", t);
        }
    }
}
