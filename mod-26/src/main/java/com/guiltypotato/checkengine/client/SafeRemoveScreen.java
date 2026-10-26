package com.guiltypotato.checkengine.client;

import com.guiltypotato.checkengine.CheckEngine;
import com.guiltypotato.checkengine.client.DashScreen.Btn;
import com.guiltypotato.checkengine.client.DashScreen.Line;
import com.guiltypotato.checkengine.client.DashScreen.Section;
import com.guiltypotato.checkengine.core.scan.Changes;
import com.guiltypotato.checkengine.core.world.SafeRemove;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.fml.loading.FMLPaths;

/** Before a world opens without a mod it still uses: what goes, a one-click backup, or the mod back on. */
final class SafeRemoveScreen {
    /** Ours while it's open (26.1 may try to open the title screen over it). */
    static Screen showing;
    static boolean shown;

    private SafeRemoveScreen() {}

    private static void go(Screen s) {
        showing = null;
        Minecraft.getInstance().setScreen(s);
    }

    /** The screen, or null when no world uses a removed mod. */
    static Screen of(Screen next, List<SafeRemove.WorldHit> hits, Changes changes) {
        if (shown || hits.isEmpty()) return null;
        shown = true;
        List<Section> sections = new ArrayList<>();
        for (SafeRemove.WorldHit h : hits.subList(0, Math.min(3, hits.size()))) {
            List<Line> lines = new ArrayList<>();
            h.byMod().forEach((mod, u) -> lines.add(Line.text(mod + ": " + SafeRemove.describe(u))));
            sections.add(new Section("WORLD: " + h.name().toUpperCase(), DashScreen.RED, "!", lines));
        }
        if (hits.size() > 3) {
            sections.add(new Section("AND " + (hits.size() - 3) + " MORE WORLDS", DashScreen.GREY, "+", List.of()));
        }
        sections.add(new Section("GOOD TO KNOW", DashScreen.AMBER, "?", DashScreen.lines(
                "Opening a world without the mod removes these for good, with what's inside them.",
                "A backup is a copy in your world list: open it once the mod is back.")));
        // A mod the player switched off (renamed to .disabled) can go straight back on.
        Path mods = FMLPaths.GAMEDIR.get().resolve("mods");
        List<String> canPutBack = new ArrayList<>();
        if (changes != null) {
            for (Changes.Change c : changes.changes()) {
                if (c.type() == Changes.Type.REMOVED && c.file() != null
                        && Files.isRegularFile(mods.resolve(c.file() + ".disabled"))
                        && !Files.exists(mods.resolve(c.file()))) {
                    canPutBack.add(c.file());
                }
            }
        }
        List<Btn> buttons = new ArrayList<>();
        buttons.add(new Btn("Back up worlds", () -> backUp(hits, next), true,
                "Copies each world above next to itself in your world list."));
        if (!canPutBack.isEmpty()) {
            buttons.add(new Btn("Switch them back on and quit", () -> {
                for (String f : canPutBack) {
                    try {
                        Files.move(mods.resolve(f + ".disabled"), mods.resolve(f));
                    } catch (IOException e) {
                        CheckEngine.LOGGER.warn("Check Engine: couldn't switch {} back on", f, e);
                    }
                }
                showing = null;
                Minecraft.getInstance().stop();
            }, true, "Takes .disabled off their names. Start the game again to load them."));
        }
        buttons.add(new Btn("Continue", () -> go(next), false));
        return showing = new DashScreen(DashScreen.RED, "WORLDS STILL USE REMOVED MODS", null, null, sections, buttons,
                () -> go(next));
    }

    private static void backUp(List<SafeRemove.WorldHit> hits, Screen next) {
        Screen working = new DashScreen(DashScreen.AMBER, "BACKING UP WORLDS...", null, null, List.of(
                new Section("PLEASE WAIT", DashScreen.AMBER, "»", DashScreen.lines(
                        "Copying " + hits.size() + " world" + (hits.size() == 1 ? "" : "s") + ". Big worlds take a minute."))),
                List.of(), () -> {});
        showing = working;
        Minecraft.getInstance().setScreen(working);
        Thread t = new Thread(() -> {
            List<Line> lines = new ArrayList<>();
            boolean ok = true;
            for (SafeRemove.WorldHit h : hits) {
                try {
                    Path copy = SafeRemove.backup(h.folder());
                    lines.add(Line.text("\"" + copy.getFileName() + "\""));
                } catch (IOException | RuntimeException e) {
                    CheckEngine.LOGGER.warn("Check Engine: couldn't back up {}", h.name(), e);
                    lines.add(Line.text("Couldn't copy \"" + h.name() + "\": " + e.getMessage()));
                    ok = false;
                }
            }
            boolean done = ok;
            Minecraft.getInstance().execute(() -> {
                Screen result = new DashScreen(done ? DashScreen.GREEN : DashScreen.RED,
                        done ? "WORLDS BACKED UP" : "SOME BACKUPS FAILED", null, null, List.of(
                        new Section(done ? "IN YOUR WORLD LIST" : "WHAT HAPPENED", done ? DashScreen.GREEN : DashScreen.RED,
                                done ? "✔" : "!", lines)),
                        List.of(new Btn("Continue", () -> go(next), true)), () -> go(next));
                showing = result;
                Minecraft.getInstance().setScreen(result);
            });
        }, "Check Engine world backup");
        t.setDaemon(true);
        t.start();
    }
}
