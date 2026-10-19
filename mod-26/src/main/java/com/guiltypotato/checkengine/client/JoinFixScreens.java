package com.guiltypotato.checkengine.client;

import com.guiltypotato.checkengine.BootCheck;
import com.guiltypotato.checkengine.CheckEngine;
import com.guiltypotato.checkengine.client.DashScreen.Btn;
import com.guiltypotato.checkengine.client.DashScreen.Line;
import com.guiltypotato.checkengine.client.DashScreen.Section;
import com.guiltypotato.checkengine.core.scan.JoinFix;
import com.guiltypotato.checkengine.core.scan.JoinMismatch.Failure;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.util.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;

/** "Fix it" on the can't-join screen: a working screen, then what was done and what's left for the player. */
final class JoinFixScreens {
    private JoinFixScreens() {}

    static void start(List<Failure> failures, Screen back) {
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(new DashScreen(DashScreen.BLUE, "FIXING YOUR MODS", null, null,
                List.of(new Section("WORKING", DashScreen.BLUE, "i", List.of(
                        Line.text("Switching off the mods the server doesn't have..."),
                        Line.dim("This only takes a few seconds.")))),
                List.of(), () -> {}));
        Thread t = new Thread(() -> {
            List<String> done;
            List<JoinFix.Step> steps = List.of();
            try {
                Path mods = FMLPaths.MODSDIR.get();
                Map<String, Path> jars = new HashMap<>();
                Map<String, String> names = new HashMap<>();
                ModList.get().getMods().forEach(m -> {
                    names.put(m.getModId(), m.getDisplayName());
                    Path file = m.getOwningFile().getFile().getFilePath();
                    // Only jars sitting in mods/, not mods packed inside another jar.
                    if (file != null && mods.equals(file.getParent()) && Files.isRegularFile(file)) jars.put(m.getModId(), file);
                });
                steps = JoinFix.plan(failures, jars, names);
                done = JoinFix.apply(steps, mods, BootCheck.outputFolder());
            } catch (Exception e) {
                CheckEngine.LOGGER.warn("Check Engine: join fix failed", e);
                done = List.of("Couldn't fix the mods: " + e.getMessage());
            }
            List<String> result = done;
            List<JoinFix.Step> pages = steps.stream().filter(s -> s.action() == JoinFix.Action.OPEN_PAGE).toList();
            mc.execute(() -> mc.setScreen(result(result, pages, back)));
        }, "Check Engine join fix");
        t.setDaemon(true);
        t.start();
    }

    private static Screen result(List<String> done, List<JoinFix.Step> pages, Screen back) {
        boolean changed = done.stream().anyMatch(d -> d.startsWith("Installed") || d.startsWith("Switched off"));
        List<Line> lines = new ArrayList<>();
        done.forEach(d -> lines.add(d.startsWith("Installed") || d.startsWith("Switched off") ? Line.text(d) : Line.dim(d)));
        if (changed) lines.add(Line.strong("Restart the game to load the changes, then join again."));
        List<Btn> buttons = new ArrayList<>();
        if (changed) buttons.add(new Btn("Quit game", () -> Minecraft.getInstance().stop(), true,
                "Mods only load when the game starts. Start it again from your launcher."));
        if (!pages.isEmpty()) {
            buttons.add(new Btn("Open mod pages", () -> pages.forEach(p -> Util.getPlatform().openUri(java.net.URI.create(p.url()))), !changed,
                    "Opens the Modrinth page of each mod Check Engine couldn't get for you."));
        }
        if (changed) {
            buttons.add(new Btn("Undo", () -> {
                try {
                    JoinFix.undo(FMLPaths.MODSDIR.get(), BootCheck.outputFolder());
                } catch (Exception e) {
                    CheckEngine.LOGGER.warn("Check Engine: join fix undo failed", e);
                }
                Minecraft.getInstance().setScreen(back);
            }, false, "Puts your mods back the way they were."));
        }
        buttons.add(new Btn("Back", () -> Minecraft.getInstance().setScreen(back), buttons.isEmpty()));
        return new DashScreen(changed ? DashScreen.GREEN : DashScreen.AMBER, changed ? "MODS FIXED" : "NOTHING I COULD FIX",
                null, null, List.of(new Section("WHAT CHECK ENGINE DID", changed ? DashScreen.GREEN : DashScreen.AMBER,
                changed ? "✔" : "!", lines)), buttons, () -> Minecraft.getInstance().setScreen(back));
    }
}
