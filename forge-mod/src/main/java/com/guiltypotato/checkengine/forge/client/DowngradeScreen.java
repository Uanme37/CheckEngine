package com.guiltypotato.checkengine.forge.client;

import com.guiltypotato.checkengine.forge.CheckEngine;
import com.guiltypotato.checkengine.forge.client.DashScreen.Btn;
import com.guiltypotato.checkengine.forge.client.DashScreen.Line;
import com.guiltypotato.checkengine.forge.client.DashScreen.Section;
import com.guiltypotato.checkengine.core.scan.Changes;
import com.guiltypotato.checkengine.core.world.SafeRemove;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.fml.loading.FMLPaths;

/**
 * A mod went back to an older version since the pack last worked: worlds saved with the newer one can lose its
 * blocks or not open. Offer a backup of the worlds played last before one is opened.
 */
final class DowngradeScreen {
    private static final int WORLDS = 3;
    static boolean shown;
    /** Ours while it's open (26.1 may try to open the title screen over it). */
    static Screen showing;

    private DowngradeScreen() {}

    /** True when the backup screen will show this launch (a mod went back a version and there are worlds). */
    static boolean willShow(Changes changes) {
        return !shown && changes != null && changes.changes().stream().anyMatch(Changes.Change::downgrade)
                && !SafeRemove.recentWorlds(FMLPaths.GAMEDIR.get(), WORLDS).isEmpty();
    }

    /** The screen, or null when nothing went back a version or there are no worlds. */
    static Screen of(Screen next, Changes changes) {
        if (shown || changes == null) return null;
        List<Changes.Change> down = changes.changes().stream().filter(Changes.Change::downgrade).toList();
        if (down.isEmpty()) return null;
        List<Path> worlds = SafeRemove.recentWorlds(FMLPaths.GAMEDIR.get(), WORLDS);
        if (worlds.isEmpty()) return null;
        shown = true;
        List<Line> mods = new ArrayList<>();
        for (Changes.Change c : down.subList(0, Math.min(5, down.size()))) {
            mods.add(Line.text(c.name() + ": " + c.from() + " -> " + c.to()));
        }
        if (down.size() > 5) mods.add(Line.dim("and " + (down.size() - 5) + " more"));
        List<Line> list = new ArrayList<>();
        for (Path w : worlds) list.add(Line.text(w.getFileName().toString()));
        Runnable go = () -> {
            showing = null;
            Minecraft.getInstance().setScreen(next);
        };
        return showing = new DashScreen(DashScreen.AMBER, "BACK UP YOUR WORLDS FIRST?", null, null, List.of(
                new Section(down.size() == 1 ? "A MOD WENT BACK A VERSION" : "MODS WENT BACK A VERSION", DashScreen.AMBER,
                        "!", mods),
                new Section("WHY", DashScreen.AMBER, "?", DashScreen.lines(
                        "Worlds saved with the newer version can lose blocks and items from it, or not open.",
                        "A backup is a copy in your world list. Nothing else changes.")),
                new Section("WORLDS PLAYED LAST", DashScreen.GREY, "▣", list)),
                List.of(new Btn("Back up these worlds", () -> backUp(worlds, next), true,
                                "Copies each world next to itself in your world list."),
                        new Btn("Skip", go, false)), go);
    }

    private static void backUp(List<Path> worlds, Screen next) {
        Screen working = new DashScreen(DashScreen.AMBER, "BACKING UP WORLDS...", null, null, List.of(
                new Section("PLEASE WAIT", DashScreen.AMBER, "»", DashScreen.lines(
                        "Copying " + worlds.size() + " world" + (worlds.size() == 1 ? "" : "s") + ". Big worlds take a minute."))),
                List.of(), () -> {});
        showing = working;
        Minecraft.getInstance().setScreen(working);
        Thread t = new Thread(() -> {
            List<Line> lines = new ArrayList<>();
            boolean ok = true;
            for (Path w : worlds) {
                try {
                    lines.add(Line.text("\"" + SafeRemove.backup(w, "going back a version").getFileName() + "\""));
                } catch (IOException | RuntimeException e) {
                    CheckEngine.LOGGER.warn("Check Engine: couldn't back up {}", w.getFileName(), e);
                    lines.add(Line.text("Couldn't copy \"" + w.getFileName() + "\": " + e.getMessage()));
                    ok = false;
                }
            }
            boolean done = ok;
            Runnable go = () -> {
                showing = null;
                Minecraft.getInstance().setScreen(next);
            };
            Minecraft.getInstance().execute(() -> Minecraft.getInstance().setScreen(showing = new DashScreen(
                    done ? DashScreen.GREEN : DashScreen.RED, done ? "WORLDS BACKED UP" : "SOME BACKUPS FAILED", null, null,
                    List.of(new Section(done ? "IN YOUR WORLD LIST" : "WHAT HAPPENED", done ? DashScreen.GREEN : DashScreen.RED,
                            done ? "✔" : "!", lines)),
                    List.of(new Btn("Continue", go, true)), go)));
        }, "Check Engine world backup");
        t.setDaemon(true);
        t.start();
    }
}
