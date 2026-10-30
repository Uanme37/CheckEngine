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
    /** The kick screen already explained (each one only once). */
    private static Screen joinExplained;

    private ClientEvents() {}

    static {
        CeLook.apply(); // Check Engine's name, lamp and help link on the shared screens
    }

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
        fixedToast(report);
        Screen next = title;
        if (report != null) {
            // Quiet mode: the pack maker can hide warnings players don't need
            List<Finding> findings = com.guiltypotato.checkengine.core.scan.PackInfo.load(
                    BootCheck.outputFolder().getParent()).visible(BootCheck.worthShowing(report)).stream()
                    // the backup screen already says a mod went back a version: don't say it twice
                    .filter(f -> !(f.code().equals(com.guiltypotato.checkengine.core.scan.Changes.DOWNGRADE_CODE)
                            && DowngradeScreen.willShow(report.changes()))).toList();
            // the player's own choices: warnings they said "Got it" to, or warnings off altogether
            findings = com.guiltypotato.checkengine.core.scan.Quiet.toShow(BootCheck.outputFolder(), findings);
            if (!findings.isEmpty()) next = new BootWarningScreen(title, findings);
        }
        // Crash Doctor first: if the last game crashed, that's what the player wants to know about.
        LastCrash crash = BootCheck.lastCrash();
        // After a pack update, once: what's new, updated and gone (Crash Doctor comes first if the last game crashed).
        // Not for a player who only wants to hear from Check Engine when something breaks.
        if (crash == null && report != null && !com.guiltypotato.checkengine.core.scan.Quiet.onlyWhenItBreaks(BootCheck.outputFolder())) {
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
        if (crash != null) next = crashScreen(next, crash, report);
        if (next != title) event.setNewScreen(next);
    }

    /**
     * Backup for Crash Doctor: in some big packs another mod puts the plain title screen back over ours before it's
     * seen. If the title screen gets drawn while an unseen crash is waiting, open Crash Doctor on top of it.
     */
    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        try {
            // Pit Stop button on the title screen (beside Accessibility / Language)
            TitleButtons.init(event.getScreen(), event.getListenersList(), event::addListener,
                    BootCheck.lastCrash() != null && !crashSeen);
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
                Minecraft.getInstance().execute(() -> Minecraft.getInstance().getToasts().addToast(new CeToast(toast[1])));
                return next;
            }
        }
        return new CrashDoctorScreen(next, report == null ? crash : crash.withChanges(report.changes()));
    }

    /** Mods > Check Engine > Config opens the player's warnings switch. Called on the player's side only. */
    public static void configScreen() {
        net.minecraftforge.fml.ModLoadingContext.get().registerExtensionPoint(
                net.minecraftforge.client.ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new net.minecraftforge.client.ConfigScreenHandler.ConfigScreenFactory(
                        (mc, parent) -> WarningsScreen.of(parent)));
    }

    /** "Fixed!": a problem or warning from the last launch is gone. Once per launch, never during Strip Mine. */
    private static void fixedToast(Report report) {
        if (fixedChecked || report == null || StripMine.running(BootCheck.outputFolder()) != null) return;
        fixedChecked = true;
        try {
            com.guiltypotato.checkengine.core.scan.WarningLog.update(BootCheck.outputFolder(),
                    com.guiltypotato.checkengine.core.scan.PackInfo.load(BootCheck.outputFolder().getParent())
                            .visible(BootCheck.worthShowing(report)),
                    java.time.LocalDateTime.now().withSecond(0).withNano(0).toString());
        } catch (java.io.IOException | RuntimeException e) {
            CheckEngine.LOGGER.warn("Check Engine: couldn't update the warning log", e);
        }
        String slower = TitleButtons.slowerNotice();
        if (slower != null) CheckEngine.LOGGER.info("Check Engine: {}", slower);
        java.util.List<String> fixed = com.guiltypotato.checkengine.core.scan.FixedProblems.update(BootCheck.outputFolder(),
                BootCheck.worthShowing(report));
        if (!fixed.isEmpty()) CheckEngine.LOGGER.info("Check Engine: fixed since last launch: {}", fixed);
        if (com.guiltypotato.checkengine.core.scan.Quiet.onlyWhenItBreaks(BootCheck.outputFolder())) fixed = java.util.List.of();
        // One toast for both, wrapping long text, so nothing is cut off and two toasts don't stack over the logo
        String[] toast = com.guiltypotato.checkengine.core.scan.StartNotice.of(fixed, slower);
        if (toast == null) return;
        Minecraft.getInstance().execute(() -> Minecraft.getInstance().getToasts().addToast(
                new CeToast(toast[1])));
    }

    /** Draws the Pit Stop button's lamp, start time and "!" over the plain button. */
    @SubscribeEvent
    public static void onScreenRender(ScreenEvent.Render.Post event) {
        try {
            TitleButtons.render(event.getScreen(), event.getGuiGraphics());
        } catch (Throwable t) {
            // the button still works without its drawing
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
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END) return;
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
