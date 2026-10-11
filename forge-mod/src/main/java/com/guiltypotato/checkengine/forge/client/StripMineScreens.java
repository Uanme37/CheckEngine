package com.guiltypotato.checkengine.forge.client;

import com.guiltypotato.checkengine.forge.BootCheck;
import com.guiltypotato.checkengine.forge.CheckEngine;
import com.guiltypotato.checkengine.core.crash.CrashDoctor.LastCrash;
import com.guiltypotato.checkengine.core.strip.StripMine;
import com.guiltypotato.checkengine.core.strip.StripMineHelper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.fml.loading.FMLPaths;

/** Strip Mine's screens: starting a search from Crash Doctor, each round on the title screen, and the result. */
final class StripMineScreens {
    /** Our own screen, so going back to the title screen from it doesn't show it again. */
    static Screen showing;
    /** True while our screen waits for an answer (26.1 may try to open the title screen over it). */
    static boolean open;

    private StripMineScreens() {}

    private static Path game() {
        return FMLPaths.GAMEDIR.get();
    }

    private static Path mods() {
        return game().resolve("mods");
    }

    /** "Find the mod behind this crash?" from the Crash Doctor screen. */
    static Screen confirmStart(Screen back, LastCrash crash) {
        int mods = countMods();
        int rounds = mods <= 1 ? 1 : 32 - Integer.numberOfLeadingZeros(mods - 1);
        return show(new ConfirmScreen(yes -> {
            open = false;
            if (!yes) {
                Minecraft.getInstance().setScreen(back);
                return;
            }
            try {
                StripMine.start(mods(), BootCheck.outputFolder(), crash.main().title(),
                        StripMine.isStartupCrash(crash.explained().description()), false);
                StripMineHelper.launch(game(), mods(), BootCheck.outputFolder());
                Minecraft.getInstance().stop();
            } catch (IOException e) {
                CheckEngine.LOGGER.warn("Check Engine: couldn't start Strip Mine", e);
                Minecraft.getInstance().setScreen(back);
            }
        }, Component.literal("Strip Mine: find the mod behind this crash"),
                Component.literal("Check Engine switches off half of your mods, you launch, and the result narrows it "
                        + "down. About " + rounds + " rounds for " + mods + " mods. Nothing is deleted, and you can stop "
                        + "any time.\n\nQuit the game now to set up round 1?"),
                Component.literal("Quit and start"), Component.literal("Cancel")));
    }

    /** The title screen during a search, or null when there's nothing to show. */
    static Screen onTitle(Screen title, Screen previous) {
        Path folder = BootCheck.outputFolder();
        StripMine.State done = StripMine.finished(folder);
        if (done != null) return result(title, done);
        StripMine.State s = StripMine.running(folder);
        if (s == null || (previous != null && previous == showing)) return null;
        String left = s.suspects().size() + " suspects left, about " + s.roundsLeft() + " rounds to go";
        if (s.startup()) {
            StripMine.markWorked(folder);
            return show(new ConfirmScreen(quit -> {
                open = false;
                if (quit) Minecraft.getInstance().stop();
                else stop(title);
            }, Component.literal("Strip Mine, round " + s.round() + ": no crash"),
                    Component.literal("With " + s.disabled().size() + " mods switched off, the pack loads fine. "
                            + left + ".\n\nQuit the game and press Play again for round " + (s.round() + 1) + "."),
                    Component.literal("Quit game"), Component.literal("Stop Strip Mine")));
        }
        return show(new ConfirmScreen(noCrash -> {
            open = false;
            if (noCrash) {
                StripMine.markWorked(folder);
                Minecraft.getInstance().stop();
            } else {
                Minecraft.getInstance().setScreen(title);
            }
        }, Component.literal("Strip Mine, round " + s.round()),
                Component.literal(s.disabled().size() + " mods are switched off. Play and do what crashed before. "
                        + "If it crashes, just press Play again. If it doesn't, come back here and press No crash. "
                        + left + "."),
                Component.literal("No crash: next round"), Component.literal("Keep testing")));
    }

    private static Screen result(Screen title, StripMine.State s) {
        String found = s.found();
        Component head = Component.literal(found != null ? "Strip Mine found it!" : "Strip Mine narrowed it down");
        Component text = Component.literal(found != null
                ? found + " causes the crash" + (s.crash().isEmpty() ? "" : " (" + s.crash() + ")") + ". With it "
                        + "switched off, the pack doesn't crash.\n\nFix: update it, or remove it. If you already have "
                        + "the newest version, send the crash report to its author.\nEvery mod is switched back on."
                : "These mods need each other, so Strip Mine can't split them: " + String.join(", ", s.suspects())
                        + ". The problem is one of them.\nEvery mod is switched back on.");
        return show(new ConfirmScreen(openFolder -> {
            open = false;
            try {
                StripMine.clear(BootCheck.outputFolder());
            } catch (IOException ignored) {
                // shown again next time; harmless
            }
            if (openFolder) Util.getPlatform().openFile(mods().toFile());
            Minecraft.getInstance().setScreen(title);
        }, head, text, Component.literal("Open mods folder"), Component.literal("OK")));
    }

    private static void stop(Screen title) {
        try {
            StripMine.stop(mods(), BootCheck.outputFolder());
            StripMine.clear(BootCheck.outputFolder());
        } catch (IOException e) {
            CheckEngine.LOGGER.warn("Check Engine: couldn't stop Strip Mine", e);
        }
        Minecraft.getInstance().setScreen(show(new ConfirmScreen(quit -> {
            open = false;
            if (quit) Minecraft.getInstance().stop();
            else Minecraft.getInstance().setScreen(title);
        }, Component.literal("Strip Mine stopped"),
                Component.literal("Every mod is switched back on. Restart the game to load them all."),
                Component.literal("Quit game"), Component.literal("Later"))));
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
