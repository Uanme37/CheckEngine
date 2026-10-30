package com.guiltypotato.checkengine.client;

import com.guiltypotato.checkengine.ui.DashScreen;
import static com.guiltypotato.checkengine.ui.DashScreen.AMBER;
import static com.guiltypotato.checkengine.ui.DashScreen.GREEN;
import static com.guiltypotato.checkengine.ui.DashScreen.GREY;
import static com.guiltypotato.checkengine.ui.DashScreen.RED;

import com.guiltypotato.checkengine.BootCheck;
import com.guiltypotato.checkengine.CheckEngine;
import com.guiltypotato.checkengine.ui.DashScreen.Btn;
import com.guiltypotato.checkengine.ui.DashScreen.Line;
import com.guiltypotato.checkengine.ui.DashScreen.Section;
import com.guiltypotato.checkengine.core.crash.CrashDoctor.LastCrash;
import com.guiltypotato.checkengine.core.strip.StripMine;
import com.guiltypotato.checkengine.core.strip.StripMineHelper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.fml.loading.FMLPaths;

/** Strip Mine's screens: starting a search from Crash Doctor, each round on the title screen, and the result. */
final class StripMineScreens {
    /** Our own screen, so going back to the title screen from it doesn't show it again. */
    static Screen showing;
    /** True while our screen waits for an answer (26.1 may try to open the title screen over it). */
    static boolean open;

    private static final String HEADLINE = "STRIP MINE";

    private StripMineScreens() {}

    private static Path game() {
        return FMLPaths.GAMEDIR.get();
    }

    private static Path mods() {
        return game().resolve("mods");
    }

    private static void go(Screen s) {
        open = false;
        Minecraft.getInstance().setScreen(s);
    }

    /** "Find the mod behind this crash?" from the Crash Doctor screen. */
    static Screen confirmStart(Screen back, LastCrash crash) {
        return confirmStart(back, crash, false);
    }

    private static Screen confirmStart(Screen back, LastCrash crash, boolean stillLocked) {
        // No mod changed since the pack last worked, but several configs did: search those instead.
        List<String> configs = com.guiltypotato.checkengine.core.strip.ConfigStrip.suspects(game(),
                BootCheck.outputFolder(), crash.changes());
        if (!configs.isEmpty()) return confirmConfigs(back, crash, configs);
        int mods = countMods();
        int rounds = mods <= 1 ? 1 : 32 - Integer.numberOfLeadingZeros(mods - 1);
        List<Section> sections = new ArrayList<>();
        if (StripMine.lockedByCurseForge(game())) {
            List<Line> unlock = new ArrayList<>(List.of(
                    Line.text("CurseForge has this pack locked, so it would put switched-off mods straight back."),
                    Line.text(StripMine.UNLOCK_STEPS)));
            if (stillLocked) unlock.add(Line.strong("Still locked: turn on \"Allow content management\" first."));
            sections.add(new Section("FIRST: LET STRIP MINE CHANGE THIS PACK", RED, "!", unlock));
        }
        sections.add(new Section("HOW IT WORKS", AMBER, "?", DashScreen.lines(
                "Check Engine switches off half of your mods, and you launch.",
                "It crashes: the faulty mod is still on. No crash: it's in the half that was off.",
                "Mods you changed lately are tested first.")));
        sections.add(new Section("SAFE", GREEN, "✓", DashScreen.lines(
                "Nothing is deleted: mods are renamed to .disabled and switched back on at the end.",
                "You can stop any time from the title screen, and every mod goes back.")));
        sections.add(new Section("WHAT HAPPENS NOW", GREY, "»", DashScreen.lines(
                "The game closes. Press Play again to start round 1. About " + rounds + " launches for " + mods
                        + " mods, fewer if you changed mods lately.")));
        return show(new DashScreen(AMBER, "STRIP MINE: FIND THE FAULTY MOD", null, null, sections, List.of(
                new Btn("Quit and start", () -> {
                    if (StripMine.lockedByCurseForge(game())) { // checked again: the app saves the switch right away
                        go(confirmStart(back, crash, true));
                        return;
                    }
                    open = false;
                    try {
                        StripMine.start(mods(), BootCheck.outputFolder(), crash.main().title(),
                                StripMine.isStartupCrash(crash.explained().description()), false);
                        StripMineHelper.launch(game(), mods(), BootCheck.outputFolder());
                        Minecraft.getInstance().stop();
                    } catch (IOException e) {
                        CheckEngine.LOGGER.warn("Check Engine: couldn't start Strip Mine", e);
                        go(back);
                    }
                }, true),
                new Btn("Cancel", () -> go(back), false)), () -> go(back)).from(back));
    }

    /** "Find the bad config?": like the mod search, over the config files changed since the pack last worked. */
    private static Screen confirmConfigs(Screen back, LastCrash crash, List<String> configs) {
        int rounds = 32 - Integer.numberOfLeadingZeros(configs.size() - 1);
        return show(new DashScreen(AMBER, "STRIP MINE: FIND THE BAD CONFIG", null, null, List.of(
                new Section("HOW IT WORKS", AMBER, "?", DashScreen.lines(
                        "No mod changed since the pack last worked, but " + configs.size() + " config files did.",
                        "Check Engine puts half of them back as they were then, and you launch.")),
                new Section("SAFE", GREEN, "✓", DashScreen.lines(
                        "Your own versions wait in checkengine/config-strip and all come back at the end.",
                        "You can stop any time from the title screen.")),
                new Section("WHAT HAPPENS NOW", GREY, "»", DashScreen.lines(
                        "The game closes. Press Play again to start round 1. About " + rounds + " launches."))),
                List.of(new Btn("Quit and start", () -> {
                    open = false;
                    try {
                        com.guiltypotato.checkengine.core.strip.ConfigStrip.start(game(), BootCheck.outputFolder(),
                                configs, crash.main().title(),
                                StripMine.isStartupCrash(crash.explained().description()), false);
                        StripMineHelper.launch(game(), mods(), BootCheck.outputFolder());
                        Minecraft.getInstance().stop();
                    } catch (IOException e) {
                        CheckEngine.LOGGER.warn("Check Engine: couldn't start Strip Mine", e);
                        go(back);
                    }
                }, true), new Btn("Cancel", () -> go(back), false)), () -> go(back)).from(back));
    }

    /** "12 mods are switched off" / "3 config files are back to how they were" */
    private static String offText(StripMine.State s) {
        int n = s.disabled().size();
        return s.configs() ? n + " config file" + (n == 1 ? " is" : "s are") + " back to how " + (n == 1 ? "it was"
                : "they were") + " when the pack last worked" : n + " mods are switched off";
    }

    /** The title screen during a search, or null when there's nothing to show. */
    static Screen onTitle(Screen title, Screen previous) {
        Path folder = BootCheck.outputFolder();
        StripMine.State done = StripMine.finished(folder);
        if (done != null) return result(title, done);
        StripMine.State s = StripMine.running(folder);
        if (s == null || (previous != null && previous == showing)) return null;
        String code = "ROUND " + s.round();
        if (s.startup()) {
            StripMine.markWorked(folder);
            // No crash: the faulty mod is one of the suspects that were switched off this round.
            int after = (int) s.suspects().stream().filter(s.toDisable()::contains).count();
            int roundsAfter = after <= 1 ? 0 : 32 - Integer.numberOfLeadingZeros(after - 1);
            Screen[] self = new Screen[1];
            self[0] = new DashScreen(AMBER, HEADLINE + ": NO CRASH THIS ROUND", code,
                    after <= 1 ? "FOUND" : after + " SUSPECTS LEFT", List.of(
                    new Section("THIS ROUND", GREEN, "✓", List.of(
                            Line.strong("With " + offText(s) + ", the pack loads fine."),
                            Line.dim(s.configs() ? "So the bad config is one of those."
                                    : "So the faulty mod is one of the mods that were off."))),
                    new Section("WHAT TO DO", AMBER, "»", List.of(Line.text(after <= 1
                            ? "That's the one. Quit the game and press Play to see which mod it is."
                            : "Quit the game and press Play again for round " + (s.round() + 1) + ". About "
                                    + roundsAfter + (roundsAfter == 1 ? " round" : " rounds") + " to go.")))), List.of(
                    new Btn("Quit", () -> {
                        open = false;
                        Minecraft.getInstance().stop();
                    }, true),
                    new Btn("Stop Strip Mine", () -> askStop(self[0], title), false)),
                    () -> askStop(self[0], title));
            return show(self[0]);
        }
        Screen[] self = new Screen[1];
        self[0] = new DashScreen(AMBER, HEADLINE + ": PLAY TO TEST", code, s.suspects().size() + " SUSPECTS LEFT",
                List.of(new Section("THIS ROUND", AMBER, "!", List.of(
                                Line.strong(offText(s) + "."),
                                Line.text("Play and do what crashed before."))),
                        new Section("WHAT TO DO", GREEN, "»", DashScreen.lines(
                                "It crashes: just press Play again.",
                                "It doesn't: come back here and press No crash. About " + s.roundsLeft()
                                        + (s.roundsLeft() == 1 ? " round" : " rounds") + " to go."))), List.of(
                new Btn("No crash: next round", () -> {
                    open = false;
                    StripMine.markWorked(folder);
                    Minecraft.getInstance().stop();
                }, true),
                new Btn("Continue", () -> go(title), true),
                new Btn("Stop Strip Mine", () -> askStop(self[0], title), false)), () -> go(title));
        return show(self[0]);
    }

    private static Screen result(Screen title, StripMine.State s) {
        String found = s.found();
        Runnable done = () -> {
            try {
                StripMine.clear(BootCheck.outputFolder());
            } catch (IOException ignored) {
                // shown again next time; harmless
            }
        };
        List<Section> sections = new ArrayList<>();
        List<Btn> buttons = new ArrayList<>();
        if (found != null && s.configs()) {
            sections.add(new Section("THE BAD CONFIG", GREEN, "✓", List.of(Line.strong(found),
                    Line.dim(s.crash().isEmpty() ? "With its old version, the pack doesn't crash."
                            : "The crash: " + s.crash()),
                    Line.dim("Found in " + s.round() + (s.round() == 1 ? " round." : " rounds.") + " Every config file is back as you had it."))));
            sections.add(new Section("FIX", GREEN, "✓", DashScreen.lines(
                    "Put back its version from when the pack last worked. Yours is kept in "
                            + "checkengine/config-undo-<date>, nothing is deleted.",
                    "Or open it and undo your last edit there.")));
            buttons.add(new Btn("Put the old one back and quit", () -> {
                done.run();
                try {
                    com.guiltypotato.checkengine.core.scan.ConfigCheckpoint.restoreOne(game(), BootCheck.outputFolder(),
                            found);
                } catch (IOException e) {
                    CheckEngine.LOGGER.warn("Check Engine: couldn't put the config back", e);
                }
                open = false;
                Minecraft.getInstance().stop();
            }, true));
        } else if (found != null) {
            List<String> off = List.of(found);
            try {
                off = StripMine.switchOffPlan(mods(), found);
            } catch (IOException ignored) {
                // just the jar
            }
            List<String> plan = off;
            sections.add(new Section("THE FAULTY MOD", GREEN, "✓", List.of(Line.strong(found),
                    Line.dim(s.crash().isEmpty() ? "With it switched off, the pack doesn't crash."
                            : "The crash: " + s.crash()),
                    Line.dim("Found in " + s.round() + (s.round() == 1 ? " round." : " rounds.") + " Every mod is switched back on."))));
            sections.add(new Section("FIX", GREEN, "✓", DashScreen.lines(
                    "Switch it off: renamed to .disabled, nothing is deleted" + (plan.size() > 1 ? " (with the "
                            + (plan.size() - 1) + " mods that need it)." : "."),
                    "Or update it, or send the crash report to its author.")));
            buttons.add(new Btn("Switch it off and quit", () -> {
                done.run();
                List<String> locked = StripMine.switchOff(mods(), plan);
                // NeoForge 26.1 holds the jars open until the game exits: rename those on the way out.
                if (!locked.isEmpty()) {
                    Runtime.getRuntime().addShutdownHook(new Thread(() -> StripMine.switchOff(mods(), locked)));
                }
                open = false;
                Minecraft.getInstance().stop();
            }, true));
        } else if (s.gaveUp()) {
            sections.add(new Section("COULDN'T FINISH", AMBER, "!", DashScreen.lines(
                    "Test rounds kept failing in a different way: switching mods off broke other mods, so it "
                            + "couldn't narrow anything down.",
                    "Every mod is switched back on. Use Help > Copy for help and send that to the pack's author.")));
        } else {
            sections.add(new Section("ONE OF THESE", AMBER, "!", List.of(
                    Line.text("These mods need each other, so Strip Mine can't split them: "
                            + String.join(", ", s.suspects())),
                    Line.dim("Every mod is switched back on."))));
        }
        buttons.add(new Btn("Continue", () -> {
            done.run();
            go(title);
        }, found == null));
        buttons.add(new Btn("Open mods folder", () -> Util.getPlatform().openFile(mods().toFile()), false));
        return show(new DashScreen(found != null ? GREEN : AMBER, found != null ? "STRIP MINE FOUND IT"
                : s.gaveUp() ? "STRIP MINE COULDN'T FINISH" : "STRIP MINE NARROWED IT DOWN", "CE-10",
                "STRIP MINE RESULT", sections, buttons, () -> {
                    done.run();
                    go(title);
                }));
    }

    /** "Stop Strip Mine?": one wrong click shouldn't throw away a search that's rounds in. */
    private static void askStop(Screen round, Screen title) {
        go(show(new DashScreen(RED, "STOP STRIP MINE?", null, null, List.of(
                new Section("ARE YOU SURE?", RED, "!", DashScreen.lines(
                        "Every mod goes back on and this search is lost: you'd start again from round 1."))),
                List.of(new Btn("Cancel", () -> go(show(round)), false),
                        new Btn("Stop Strip Mine", () -> stop(title), true)), () -> go(show(round))).from(round)));
    }

    /**
     * During a search the loader's "warnings while loading" screen only says what the test switched off (a missing
     * performance mod...). Skip it like Proceed would. Returns true if it did; error screens are never skipped.
     */
    static boolean skipWarnings(Screen screen) {
        if (screen == null || !screen.getClass().getName().endsWith(".LoadingErrorScreen")) return false;
        if (StripMine.running(BootCheck.outputFolder()) == null) return false;
        try {
            java.lang.reflect.Field errors = screen.getClass().getDeclaredField("modLoadErrors");
            errors.setAccessible(true);
            if (!((java.util.List<?>) errors.get(screen)).isEmpty()) return false;
            Runnable next;
            try {
                java.lang.reflect.Field task = screen.getClass().getDeclaredField("nextScreenTask");
                task.setAccessible(true);
                next = (Runnable) task.get(screen);
            } catch (NoSuchFieldException e) {
                next = () -> Minecraft.getInstance().setScreen(null); // Forge 1.20.1's Proceed does this
            }
            Minecraft.getInstance().execute(next);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false; // the player clicks Proceed themselves
        }
    }

    private static void stop(Screen title) {
        try {
            StripMine.stop(mods(), BootCheck.outputFolder());
            StripMine.clear(BootCheck.outputFolder());
        } catch (IOException e) {
            CheckEngine.LOGGER.warn("Check Engine: couldn't stop Strip Mine", e);
        }
        go(show(new DashScreen(GREEN, "STRIP MINE STOPPED", null, null, List.of(
                new Section("ALL BACK ON", GREEN, "✓", DashScreen.lines(
                        "Every mod is switched back on. Restart the game to load them all."))),
                List.of(new Btn("Quit", () -> {
                    open = false;
                    Minecraft.getInstance().stop();
                }, true), new Btn("Continue", () -> go(title), false)), () -> go(title))));
    }

    private static Screen show(Screen s) {
        showing = s;
        open = true;
        return s;
    }

    private static int countMods() {
        try (Stream<Path> f = Files.list(mods())) {
            return (int) f.filter(p -> p.getFileName().toString().endsWith(".jar")).count() - 1;
        } catch (IOException e) {
            return 0;
        }
    }
}
