package com.guiltypotato.checkengine.client;

import com.guiltypotato.checkengine.BootCheck;
import com.guiltypotato.checkengine.CheckEngine;
import com.guiltypotato.checkengine.core.crash.CrashDoctor.LastCrash;
import com.guiltypotato.checkengine.core.scan.Finding;
import com.guiltypotato.checkengine.core.scan.HelpBundle;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.loading.FMLPaths;

/** Crash Doctor: shown once before the title screen when the previous game crashed. */
public final class CrashDoctorScreen extends DashScreen {
    CrashDoctorScreen(Screen next, LastCrash crash) {
        this(next, crash, new Screen[1]);
    }

    private CrashDoctorScreen(Screen next, LastCrash crash, Screen[] self) {
        super(AMBER, tr("checkengine.crash.title").toUpperCase(), "CRASH", crash.when(), sections(crash),
                buttons(next, crash, self), () -> Minecraft.getInstance().setScreen(next));
        self[0] = this;
    }

    private static List<Section> sections(LastCrash crash) {
        Finding main = crash.main();
        List<Section> out = new ArrayList<>();
        List<Line> what = new ArrayList<>(List.of(Line.strong(main.title())));
        if (main.detail() != null) for (String l : main.detail().split("\n")) what.add(Line.dim(l));
        out.add(new Section("WHAT HAPPENED", AMBER, "!", what));
        List<Finding> others = crash.explained().findings().subList(1, crash.explained().findings().size());
        if (!others.isEmpty()) {
            out.add(new Section("ALSO IN THE CRASH REPORT", GREY, "+",
                    others.stream().map(f -> Line.text("- " + f.title())).toList()));
        }
        if (crash.changes() != null && !crash.changes().isEmpty()) {
            out.add(new Section("CHANGED SINCE IT LAST WORKED", AMBER, "±",
                    List.of(crash.changes().summary().split("\n")).stream().map(Line::text).toList()));
        }
        if (main.fix() != null) out.add(new Section("FIX", GREEN, "✔", List.of(Line.text(main.fix()))));
        return out;
    }

    private static List<Btn> buttons(Screen next, LastCrash crash, Screen[] self) {
        List<Btn> out = new ArrayList<>(baseButtons(next, crash, self));
        // A config edit since the pack last worked is often the whole story: offer to put the old ones back.
        boolean configs = crash.changes() != null && crash.changes().changes().stream()
                .anyMatch(c -> c.type() == com.guiltypotato.checkengine.core.scan.Changes.Type.CONFIG);
        // A broken mob or machine crashes the world every time it loads: offer to remove it, safely.
        Path world = com.guiltypotato.checkengine.core.crash.WorldRescue.newestWorld(FMLPaths.GAMEDIR.get());
        if (com.guiltypotato.checkengine.core.crash.WorldRescue.fits(crash.main()) && world != null) {
            out.add(1, new Btn("Rescue world", () -> Minecraft.getInstance().setScreen(rescue(self[0], next, crash,
                    world)), true, "Backs the world up, then removes the broken mob or machine the next time it loads."));
        }
        if (configs) {
            out.add(2, new Btn("Undo config changes", () -> undoConfigs(next), true,
                    "Puts back the configs from the last launch that worked. Your current ones are kept in "
                            + "checkengine/config-undo-<date>, nothing is deleted."));
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
                                        done ? "✔" : "!", List.of(Line.text(msg)))),
                                List.of(new Btn("Continue", () -> Minecraft.getInstance().setScreen(next), true)),
                                () -> Minecraft.getInstance().setScreen(next))));
                    }, "Check Engine world rescue");
                    t.setDaemon(true);
                    t.start();
                }, true), new Btn("Back", () -> Minecraft.getInstance().setScreen(back), false)),
                () -> Minecraft.getInstance().setScreen(back));
    }

    private static Screen working() {
        return new DashScreen(AMBER, "BACKING UP THE WORLD...", null, null, List.of(new Section("PLEASE WAIT", AMBER,
                "»", List.of(Line.text("Copying the world. Big worlds take a minute.")))), List.of(), () -> {});
    }

    private static void undoConfigs(Screen next) {
        String text;
        try {
            Path kept = com.guiltypotato.checkengine.core.scan.ConfigCheckpoint.restore(FMLPaths.GAMEDIR.get(),
                    BootCheck.outputFolder());
            text = kept == null ? "Nothing to undo: the configs already match the last launch that worked."
                    : "The configs from the last launch that worked are back. Your changed ones are in "
                            + BootCheck.outputFolder().getParent().relativize(kept) + ".";
        } catch (IOException e) {
            CheckEngine.LOGGER.warn("Check Engine: couldn't put the configs back", e);
            text = "Couldn't put the configs back: " + e.getMessage();
        }
        Minecraft.getInstance().setScreen(new DashScreen(GREEN, "CONFIGS PUT BACK", null, null, List.of(
                new Section("DONE", GREEN, "✔", List.of(Line.text(text),
                        Line.dim("Restart the game so every mod reads them again.")))),
                List.of(new Btn("Quit game", () -> Minecraft.getInstance().stop(), true),
                        new Btn("Later", () -> Minecraft.getInstance().setScreen(next), false)),
                () -> Minecraft.getInstance().setScreen(next)));
    }

    private static List<Btn> baseButtons(Screen next, LastCrash crash, Screen[] self) {
        return List.of(
                new Btn(tr("checkengine.screen.continue"), () -> Minecraft.getInstance().setScreen(next), true),
                new Btn(tr("checkengine.crash.strip_mine"),
                        () -> Minecraft.getInstance().setScreen(StripMineScreens.confirmStart(self[0], crash)), true),
                new Btn(tr("checkengine.crash.open_details"), () -> {
                    try {
                        Path details = crash.save(BootCheck.outputFolder());
                        Util.getPlatform().openFile(details.toFile());
                    } catch (IOException e) {
                        CheckEngine.LOGGER.warn("Check Engine: couldn't save the crash details", e);
                    }
                }, false, "Opens Check Engine's full explanation of this crash in a text file."),
                new Btn(tr("checkengine.crash.open_crash"), () -> Util.getPlatform().openFile(crash.file().toFile()), false,
                        "Opens Minecraft's own crash report."),
                new Btn(tr("checkengine.crash.help"), () -> {
                    try {
                        HelpBundle.create(FMLPaths.GAMEDIR.get(), BootCheck.get());
                        Util.getPlatform().openFile(BootCheck.outputFolder().toFile());
                    } catch (IOException e) {
                        CheckEngine.LOGGER.warn("Check Engine: couldn't make the help file", e);
                    }
                }, false, "Makes one zip to send when you ask for help on Discord or GitHub. Your name and IP "
                        + "are blanked out."));
    }

    private static String tr(String key) {
        return Component.translatable(key).getString();
    }
}
