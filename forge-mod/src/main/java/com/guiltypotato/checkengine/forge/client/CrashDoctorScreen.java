package com.guiltypotato.checkengine.forge.client;

import com.guiltypotato.checkengine.ui.DashScreen;
import com.guiltypotato.checkengine.forge.BootCheck;
import com.guiltypotato.checkengine.forge.CheckEngine;
import com.guiltypotato.checkengine.core.crash.CrashDoctor;
import com.guiltypotato.checkengine.core.crash.CrashDoctor.LastCrash;
import com.guiltypotato.checkengine.core.crash.CrashHelp;
import com.guiltypotato.checkengine.core.scan.Finding;
import com.guiltypotato.checkengine.core.scan.HelpBundle;
import com.guiltypotato.checkengine.core.strip.GoBack;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.fml.loading.FMLPaths;

/** Crash Doctor: shown once before the title screen when the previous game crashed. */
public final class CrashDoctorScreen extends DashScreen {
    private static final String ISSUES = "https://github.com/Uanme37/CheckEngine/issues";

    CrashDoctorScreen(Screen next, LastCrash crash) {
        this(next, crash, new Screen[1]);
    }

    private CrashDoctorScreen(Screen next, LastCrash crash, Screen[] self) {
        super(RED, crash.beforeInstall() ? "CRASHED BEFORE INSTALL" : tr("checkengine.crash.title").toUpperCase(),
                com.guiltypotato.checkengine.core.ui.ScreenHelp.CRASH_CODE, com.guiltypotato.checkengine.core.ui.UiText.date(crash.when()), () -> Minecraft.getInstance().setScreen(next), tabs(next, crash, self),
                List.of(new Btn(tr("checkengine.screen.continue"), () -> Minecraft.getInstance().setScreen(next), false,
                        "Carry on to the title screen.")));
        self[0] = this;
        copies(slim() ? null : () -> copyForHelp(this, crash));
    }

    /** Crash Assistant already showed the logs when the game closed: then Crash Doctor keeps to what it can fix. */
    private static boolean slim() {
        return CrashDoctor.crashAssistant(FMLPaths.GAMEDIR.get());
    }

    /**
     * The side-bar tabs: Fix (opens first), Crash lines, What changed, Get help. A tab with nothing to show is left out.
     * With Crash Assistant installed there are no crash lines and no Copy for help: it already has both.
     */
    private static List<Tab> tabs(Screen next, LastCrash crash, Screen[] self) {
        Finding main = crash.main();
        boolean slim = slim();
        List<Tab> out = new ArrayList<>();

        // Fix: on top only when Check Engine knows the cause; a guess is said to be one.
        // A broken mob or machine crashes the world every time it loads: offer to remove it, safely.
        Path world = com.guiltypotato.checkengine.core.crash.WorldRescue.newestWorld(FMLPaths.GAMEDIR.get());
        boolean rescuable = com.guiltypotato.checkengine.core.crash.WorldRescue.fits(main) && world != null;
        List<Section> fix = new ArrayList<>();
        if (crash.sure()) {
            List<Line> lines = new ArrayList<>(List.of(Line.strong(main.title())));
            if (main.fix() != null) lines.add(Line.text(rescuable ? main.fix()
                    : com.guiltypotato.checkengine.core.crash.WorldRescue.fixWithoutRescue(main.fix())));
            if (main.detail() != null) {
                List.of(main.detail().split("\n")).stream().limit(3).forEach(l -> lines.add(Line.dim(l)));
            }
            fix.add(new Section(null, GREEN, "", lines)); // the tab is called Fix: no heading
        } else {
            List<Line> unsure = new ArrayList<>();
            if (crash.culprit() != null) {
                unsure.add(Line.text("It happened inside " + crash.culprit() + ", but that mod may not be the cause."));
            }
            unsure.add(Line.dim(com.guiltypotato.checkengine.core.crash.CrashTranslator.STRIP_MINE_HINT));
            fix.add(new Section("NOT SURE WHAT CAUSED THIS", GREY, "?", unsure));
        }
        if (crash.beforeInstall()) {
            fix.get(0).lines().add(0, Line.dim("From before Check Engine was installed, so there's nothing to undo."));
        }
        if (slim) {
            fix.add(new Section("CRASH ASSISTANT", GREY, "i", List.of(Line.dim(
                    "Crash Assistant showed the logs when the game closed. Check Engine adds the fix buttons."))));
        }
        List<Btn> undo = undoButtons(next, crash, self);
        List<Btn> fixButtons = new ArrayList<>();
        if (rescuable) {
            fixButtons.add(new Btn("Rescue world", () -> Minecraft.getInstance().setScreen(rescue(self[0], next, crash,
                    world)), true, "Backs the world up, then removes the broken mob or machine the next time it loads."));
        }
        fixButtons.addAll(undo);
        // An F3+C test crash has no mod behind it: nothing for Strip Mine to find.
        if (!"debug-crash".equals(main.code())) {
            fixButtons.add(new Btn(tr("checkengine.crash.strip_mine"),
                    () -> Minecraft.getInstance().setScreen(StripMineScreens.confirmStart(self[0], crash)), true,
                    "Finds the mod behind the crash by switching half the mods off each launch."));
        }
        out.add(new Tab("Fix", crash.sure() ? "✓" : "?", crash.sure() ? GREEN : GREY, null, fix, fixButtons));

        // Crash lines: the crash's own words, the mod at fault in amber.
        List<Section> lines = new ArrayList<>();
        if (!slim && !crash.keyLines().isEmpty()) {
            lines.add(new Section("FROM THE CRASH REPORT", AMBER, "!", crash.keyLines().stream()
                    .map(k -> k.culprit() ? Line.mark(k.text()) : Line.dim(k.text())).toList()));
        }
        List<Finding> others = crash.explained().findings().subList(1, crash.explained().findings().size());
        if (!slim && !others.isEmpty()) {
            lines.add(new Section("ALSO IN THE CRASH REPORT", GREY, "+",
                    others.stream().map(f -> Line.text("- " + f.title())).toList()));
        }
        if (!lines.isEmpty()) out.add(new Tab("Crash lines", "!", AMBER, null, lines, logButtons(crash)));

        // What changed since the pack last worked, with the buttons that put it back.
        if (!crash.beforeInstall() && crash.changes() != null && !crash.changes().isEmpty()) {
            out.add(new Tab("Changed", "±", AMBER, Integer.toString(crash.changes().changes().size()), List.of(
                    new Section("CHANGED SINCE IT LAST WORKED", AMBER, "±",
                            List.of(crash.changes().summary().split("\n")).stream().map(Line::text).toList())), undo));
        }

        // Get help: what to send, and the buttons that make it.
        List<Line> help = new ArrayList<>();
        List<Btn> helpButtons = new ArrayList<>();
        if (!slim) {
            help.add(Line.text("Copy for help puts the cause, the crash lines, what changed and your versions on the "
                    + "clipboard, ready to paste in your pack's Discord or a GitHub issue."));
            helpButtons.add(new Btn("Copy for help", () -> copyForHelp(self[0], crash), true,
                    "Copies the cause, the key crash lines, what changed and your versions, ready to paste where you "
                            + "ask for help. Your name and IP are blanked out."));
        } else {
            help.add(Line.text("Crash Assistant's window has the logs and its own way to share them."));
        }
        help.add(Line.text("Make help file puts Check Engine's reports, the mod list and the logs in one zip."));
        help.add(Line.dim("Your name, user folder and IP are blanked out. Nothing is uploaded."));
        // Check Engine has no Discord: wrong answers go to its GitHub issues (opens in the browser).
        help.add(Line.text("Check Engine got it wrong? Report it on GitHub, with " + (slim ? "" : "Copy for help or ") + "the help file."));
        helpButtons.add(helpFileButton());
        helpButtons.add(new Btn("Report it", () -> Util.getPlatform().openUri(ISSUES), true,
                "Opens Check Engine's GitHub issues in your browser."));
        // With Crash Assistant there's no Crash lines tab: its log buttons go here.
        if (lines.isEmpty()) helpButtons.addAll(logButtons(crash));
        out.add(new Tab("Get help", "?", BLUE, null, List.of(new Section("GET HELP", BLUE, "?", help)), helpButtons));
        return out;
    }

    /** "Copy for help": the message goes on the clipboard, and the screen shows exactly what was copied. */
    private static void copyForHelp(Screen back, LastCrash crash) {
        String text = CrashHelp.message(crash, BootCheck.get(), Minecraft.getInstance().getUser().getName(),
                BootCheck.outputFolder());
        Minecraft.getInstance().keyboardHandler.setClipboard(text);
        Minecraft.getInstance().setScreen(copied(back, text));
    }

    /** Shows exactly what went on the clipboard. */
    static Screen copied(Screen back, String text) {
        return new DashScreen(BLUE, "COPIED FOR HELP", null, null, List.of(
                new Section("PASTE IT IN YOUR HELP CHANNEL", BLUE, "i", List.of(
                        Line.text("Press Ctrl+V in your pack's Discord or in a GitHub issue."),
                        Line.dim("Your name, user folder and IP are blanked out. Nothing was uploaded."))),
                new Section("WHAT WAS COPIED", GREY, "i", List.of(text.split("\n")).stream().map(Line::dim).toList())),
                List.of(new Btn("Continue", () -> Minecraft.getInstance().setScreen(back), true)),
                () -> Minecraft.getInstance().setScreen(back)).from(back);
    }

    /** Undo new mods and Undo config changes, when they apply (never for a crash from before Check Engine). */
    private static List<Btn> undoButtons(Screen next, LastCrash crash, Screen[] self) {
        List<Btn> out = new ArrayList<>();
        // Before install there's no last good launch; an F3+C test crash has nothing to fix.
        if (crash.beforeInstall() || "debug-crash".equals(crash.main().code())) return out;
        // Anything changed since the pack last worked (mods, versions, configs): one button puts it all back.
        GoBack.Plan plan = goBackPlan();
        if (plan != null && !plan.isEmpty()) {
            out.add(new Btn("Go back to when it worked", () -> Minecraft.getInstance().setScreen(goBack(self[0], plan)),
                    true, plan.summary() + ". Nothing is deleted, and you can put it back."));
        }
        return out;
    }

    /** "Rescue world?": what happens, what it costs, then back up and switch the setting on. */
    private static Screen rescue(Screen back, Screen next, LastCrash crash, Path world) {
        String name = world.getFileName().toString();
        return new DashScreen(AMBER, "RESCUE WORLD: " + name.toUpperCase(), null, null, List.of(
                new Section("WHAT HAPPENS", AMBER, "?", List.of(
                        Line.text("1. Check Engine copies \"" + name + "\" first (it shows in your world list)."),
                        Line.text("2. Open the world: the loader removes " + com.guiltypotato.checkengine.core.crash
                                .WorldRescue.what(crash.main()) + " instead of crashing."),
                        Line.text("3. Next time you start the game, the setting turns itself off again."))),
                new Section("GOOD TO KNOW", RED, "!", List.of(
                        Line.text("The broken mob or machine is gone for good, with what was inside it."),
                        Line.dim("Anything else that errors while the world is open is removed too. The backup has "
                                + "everything as it was.")))),
                List.of(new Btn("Back up and rescue", () -> {
                    Minecraft.getInstance().setScreen(working());
                    Thread t = new Thread(() -> {
                        String text;
                        boolean ok;
                        try {
                            Path backup = com.guiltypotato.checkengine.core.crash.WorldRescue.start(
                                    FMLPaths.GAMEDIR.get(), BootCheck.outputFolder(), world);
                            text = "Backup made: \"" + backup.getFileName() + "\". Now open \"" + name + "\".";
                            ok = true;
                        } catch (IOException | RuntimeException e) {
                            CheckEngine.LOGGER.warn("Check Engine: world rescue failed", e);
                            text = "Couldn't set it up: " + e.getMessage() + ". Nothing was changed in the world.";
                            ok = false;
                        }
                        String msg = text;
                        boolean done = ok;
                        Minecraft.getInstance().execute(() -> Minecraft.getInstance().setScreen(new DashScreen(
                                done ? GREEN : RED, done ? "WORLD READY TO RESCUE" : "WORLD RESCUE FAILED", null, null,
                                List.of(new Section(done ? "NEXT" : "WHAT HAPPENED", done ? GREEN : RED,
                                        done ? "✓" : "!", List.of(Line.text(msg)))),
                                List.of(new Btn("Continue", () -> Minecraft.getInstance().setScreen(next), true)),
                                () -> Minecraft.getInstance().setScreen(next))));
                    }, "Check Engine world rescue");
                    t.setDaemon(true);
                    t.start();
                }, true), new Btn("Cancel", () -> Minecraft.getInstance().setScreen(back), false)),
                () -> Minecraft.getInstance().setScreen(back)).from(back);
    }

    /** Worked out in the background since the game started (it reads every jar); null if it isn't ready. */
    private static GoBack.Plan goBackPlan() {
        return com.guiltypotato.checkengine.core.strip.GoBackPlans.now();
    }

    /** "Go back to when it worked?": everything that changes in player words, then Go back or Cancel. */
    static Screen goBack(Screen back, GoBack.Plan plan) {
        List<Line> list = new ArrayList<>();
        list.add(Line.text(plan.summary() + "."));
        List<String> changes = plan.lines().stream().filter(l -> !l.contains("can't put it back")).toList();
        changes.stream().limit(10).forEach(l -> list.add(Line.dim("- " + l)));
        if (changes.size() > 10) list.add(Line.dim("...and " + (changes.size() - 10) + " more"));
        List<Section> sections = new ArrayList<>();
        if (plan.lastWorkedShort() != null) {
            sections.add(new Section(null, GREY, "", List.of(Line.dim("Last worked " + plan.lastWorkedShort()))));
        }
        sections.add(new Section("WHAT CHANGES", AMBER, "±", list));
        List<Line> cant = new ArrayList<>();
        plan.notKept().stream().limit(5).forEach(n -> cant.add(Line.text(n + ": old version not kept, can't put it "
                + "back")));
        if (plan.notKept().size() > 5) cant.add(Line.dim("...and " + (plan.notKept().size() - 5) + " more"));
        if (plan.note() != null) cant.add(Line.dim(plan.note()));
        if (!cant.isEmpty()) sections.add(new Section("STAYS AS IT IS", GREY, "i", cant));
        // One line: it sits right above the buttons and must fit without scrolling
        sections.add(new Section("SAFE", GREEN, "✓", List.of(
                Line.text("Nothing is deleted. Put it back (next screen) undoes it."))));
        Path game = FMLPaths.GAMEDIR.get();
        if (com.guiltypotato.checkengine.core.strip.StripMine.lockedByCurseForge(game)) {
            sections.add(0, new Section("FIRST: UNLOCK THE PACK", RED, "!", List.of(
                    Line.text(com.guiltypotato.checkengine.core.strip.StripMine.UNLOCK_STEPS))));
        }
        // Both mods and configs changed: the player picks (a pack maker's own config edits may be among them)
        List<Btn> buttons = new ArrayList<>();
        if (plan.hasMods() && !plan.configs().isEmpty()) {
            boolean configsFirst = com.guiltypotato.checkengine.core.scan.PackInfo.goBackConfigsByDefault(game);
            // both full buttons: it's a real choice; the pack maker's default comes first
            Btn all = goBackButton(plan, game, true, "Mods and configs", true,
                    "Configs go back to when it worked too.");
            Btn mods = goBackButton(plan, game, false, "Mods only", true,
                    "Your configs stay as they are now (your own pack work is kept).");
            buttons.add(configsFirst ? all : mods);
            buttons.add(configsFirst ? mods : all);
        } else {
            buttons.add(goBackButton(plan, game, true, "Go back", true, null));
        }
        buttons.add(new Btn("Cancel", () -> Minecraft.getInstance().setScreen(back), false));
        return new DashScreen(AMBER, "GO BACK TO WHEN IT WORKED?", null, null, sections, buttons,
                () -> Minecraft.getInstance().setScreen(back)).from(back);
    }

    /**
     * One Go back button. The game is running, so configs go back at the next start (mods save theirs as the game
     * closes); the mods go now.
     */
    private static Btn goBackButton(GoBack.Plan plan, Path game, boolean configs, String label, boolean primary,
                                    String tip) {
        return new Btn(label, () -> {
            String text;
            boolean ok;
            try {
                List<String> locked = GoBack.apply(game, BootCheck.outputFolder(), plan, configs, true);
                // NeoForge 26.1 holds the jars open until the game exits: rename those on the way out, unless Put it
                // back undid this before the game closed.
                Path ce = BootCheck.outputFolder();
                if (!locked.isEmpty()) Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    if (GoBack.done(ce)) com.guiltypotato.checkengine.core.strip.StripMine.switchOff(game.resolve("mods"), locked);
                }));
                text = "The pack is back to when it worked. Restart the game to load it."
                        + (configs && !plan.configs().isEmpty() ? " Your configs go back as it starts." : "");
                ok = true;
            } catch (IOException | RuntimeException e) {
                CheckEngine.LOGGER.warn("Check Engine: couldn't go back", e);
                text = "Couldn't go back: " + e.getMessage() + ". Put it back undoes what was done.";
                ok = false;
            }
            com.guiltypotato.checkengine.core.strip.GoBackPlans.refresh(game, BootCheck.outputFolder());
            Minecraft.getInstance().setScreen(wentBack(ok, text));
        }, primary, tip);
    }

    /** After Go back: quit to load it, or put it all back. */
    private static Screen wentBack(boolean ok, String text) {
        return new DashScreen(ok ? GREEN : RED, ok ? "BACK TO WHEN IT WORKED" : "COULDN'T GO BACK", null, null,
                List.of(new Section(ok ? "DONE" : "WHAT HAPPENED", ok ? GREEN : RED, ok ? "✓" : "!",
                        ok ? List.of(Line.text(text), Line.dim("The CurseForge app may still list the newer versions: "
                                + "that's only its own list.")) : List.of(Line.text(text)))),
                List.of(new Btn("Quit", () -> Minecraft.getInstance().stop(), true),
                        new Btn("Put it back", () -> {
                            String done;
                            try {
                                List<String> back = GoBack.putBack(FMLPaths.GAMEDIR.get(), BootCheck.outputFolder());
                                com.guiltypotato.checkengine.core.strip.GoBackPlans.refresh(FMLPaths.GAMEDIR.get(), BootCheck.outputFolder());
                                done = back.isEmpty() ? "Put back how you had it." : "Put back how you had it ("
                                        + back.size() + " change" + (back.size() == 1 ? "" : "s") + ").";
                            } catch (IOException | RuntimeException e) {
                                CheckEngine.LOGGER.warn("Check Engine: couldn't put it back", e);
                                done = "Couldn't put it back: " + e.getMessage();
                            }
                            Minecraft.getInstance().setScreen(new DashScreen(GREY, "PUT BACK", null, null, List.of(
                                    new Section("DONE", GREY, "i", List.of(Line.text(done),
                                            Line.dim("Restart the game so it loads your mods again.")))),
                                    List.of(new Btn("Quit", () -> Minecraft.getInstance().stop(), true)),
                                    () -> Minecraft.getInstance().stop()).light(AMBER));
                        }, false, "Undoes Go back: your newer mods and configs come back.")),
                () -> Minecraft.getInstance().stop());
    }

    private static Screen working() {
        return new DashScreen(AMBER, "BACKING UP THE WORLD...", null, null, List.of(new Section("PLEASE WAIT", AMBER,
                "»", List.of(Line.text("Copying the world. Big worlds take a minute.")))), List.of(), () -> {});
    }

    private static List<Btn> logButtons(LastCrash crash) {
        return List.of(
                new Btn(tr("checkengine.crash.open_crash"), () -> Util.getPlatform().openFile(crash.file().toFile()), true,
                        "Opens Minecraft's own crash report."),
                new Btn("Game log", () -> {
                    // latest.log is already this launch's log: find the one from the session that crashed
                    Thread t = new Thread(() -> {
                        Path log;
                        try {
                            log = CrashDoctor.saveLog(FMLPaths.GAMEDIR.get(), crash, BootCheck.outputFolder());
                        } catch (IOException | RuntimeException e) {
                            CheckEngine.LOGGER.warn("Check Engine: couldn't save the crash's game log", e);
                            log = null;
                        }
                        Path open = log != null ? log : crash.file();
                        Minecraft.getInstance().execute(() -> Util.getPlatform().openFile(open.toFile()));
                    }, "Check Engine crash log");
                    t.setDaemon(true);
                    t.start();
                }, true, "Opens the game log from the session that crashed. If it's gone, opens the crash report."));
    }

    private static Btn helpFileButton() {
        return new Btn(tr("checkengine.crash.help"), () -> {
            // In the background: reading a big pack's logs takes a few seconds, the screen shouldn't freeze
            Thread t = new Thread(() -> {
                try {
                    HelpBundle.create(FMLPaths.GAMEDIR.get(), BootCheck.get());
                    Minecraft.getInstance().execute(() ->
                            Util.getPlatform().openFile(BootCheck.outputFolder().toFile()));
                } catch (IOException | RuntimeException e) {
                    CheckEngine.LOGGER.warn("Check Engine: couldn't make the help file", e);
                }
            }, "Check Engine help file");
            t.setDaemon(true);
            t.start();
        }, true, "Makes one zip to send when you ask for help on Discord or GitHub. Your name and IP are blanked out.");
    }

    private static String tr(String key) {
        return Component.translatable(key).getString();
    }
}
