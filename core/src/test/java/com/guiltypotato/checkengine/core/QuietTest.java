package com.guiltypotato.checkengine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guiltypotato.checkengine.core.scan.Finding;
import com.guiltypotato.checkengine.core.scan.Finding.Severity;
import com.guiltypotato.checkengine.core.scan.LogBaseline;
import com.guiltypotato.checkengine.core.scan.LogProblems;
import com.guiltypotato.checkengine.core.scan.LogSpam;
import com.guiltypotato.checkengine.core.scan.Quiet;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Quiet: "Got it" per warning, the player's "Only tell me when it breaks", and known harmless log lines. */
class QuietTest {
    @TempDir
    Path folder;

    private static Finding warn(String title) {
        return new Finding(Severity.WARNING, "x", title, "", "", List.of());
    }

    @Test
    void gotItHidesEachWarningOnItsOwn() {
        Finding a = warn("3 minimaps: A, B, C"), b = warn("Duplicate mod: Create");
        Quiet.gotIt(folder, List.of(a));
        assertEquals(List.of(b), Quiet.toShow(folder, List.of(a, b)), "a new warning shows only itself");
        Quiet.gotIt(folder, List.of(b));
        assertEquals(List.of(), Quiet.toShow(folder, List.of(a, b)));
    }

    @Test
    void aWarningThatWentAwayAndCameBackShowsAgain() {
        Finding a = warn("Duplicate mod: Create");
        Quiet.gotIt(folder, List.of(a));
        assertEquals(List.of(), Quiet.toShow(folder, List.of()), "fixed");
        assertEquals(List.of(a), Quiet.toShow(folder, List.of(a)), "back: show it again");
    }

    @Test
    void onlyWhenItBreaksKeepsProblems() throws Exception {
        Finding problem = new Finding(Severity.ERROR, "x", "Missing mod: Create", "", "", List.of());
        Finding w = warn("3 minimaps: A, B, C");
        assertFalse(Quiet.onlyWhenItBreaks(folder));
        Quiet.setOnlyWhenItBreaks(folder, true);
        assertTrue(Quiet.onlyWhenItBreaks(folder));
        assertEquals(List.of(problem), Quiet.toShow(folder, List.of(problem, w)));
        Quiet.setOnlyWhenItBreaks(folder, false);
        assertEquals(List.of(problem, w), Quiet.toShow(folder, List.of(problem, w)));
    }

    /** A log with {@code n} warnings from mod {@code id} and 4 missing textures for it. */
    private static String noisyLog(String id, String name, int n) {
        StringBuilder log = new StringBuilder("[27Oct2026 10:00:00.000] [main/INFO] [LOADING]: Mod list:\n\t\t" + name
                + " 1.0 (" + id + ")\n");
        for (int i = 0; i < n; i++) {
            log.append("[27Oct2026 10:00:01.000] [main/WARN] [x/]: Something odd from mod ").append(id).append('\n');
        }
        for (int i = 0; i < 4; i++) {
            log.append("[27Oct2026 10:00:02.000] [Worker-3/WARN] [x/]: Missing textures in model ").append(id)
                    .append(":thing_").append(i).append("#inventory:\n");
        }
        return log.toString();
    }

    @Test
    void noiseThatWasThereWhenThePackWorkedIsNormal() throws Exception {
        Path game = folder.resolve("pack");
        Path logs = java.nio.file.Files.createDirectories(game.resolve("logs"));
        // a launch that worked: Copycats+ was already this noisy, with the same missing textures
        java.nio.file.Files.writeString(logs.resolve("latest.log"), noisyLog("copycats", "Create: Copycats+", 80));
        com.guiltypotato.checkengine.core.scan.LogBaseline.save(game.resolve("checkengine"), game);

        String now = noisyLog("copycats", "Create: Copycats+", 90) + noisyLog("newaddon", "New Addon", 300);
        LogBaseline.Normal normal = LogBaseline.forPack(game, List.of(now));
        LogSpam.Result spam = LogSpam.analyze(List.of(now)).onlyNew(normal);
        assertEquals(List.of("newaddon"), spam.noisy().stream().map(LogSpam.Source::mod).toList());
        assertEquals(List.of("Create: Copycats+"), spam.normal());
        LogProblems.Result assets = LogProblems.analyze(List.of(now), null).onlyNew(normal);
        assertEquals(Map.of("New Addon", 4), assets.missingAssets());
        assertTrue(assets.toText().contains("already missing when the pack worked"), assets.toText());
    }

    @Test
    void noiseThatGrewALotIsNew() {
        // never started with Check Engine: the earlier launches are what's normal
        String before = noisyLog("copycats", "Create: Copycats+", 60);
        String now = noisyLog("copycats", "Create: Copycats+", 400);
        LogBaseline.Normal normal = LogBaseline.forPack(null, List.of(now, before));
        assertEquals(List.of("copycats"),
                LogSpam.analyze(List.of(now)).onlyNew(normal).noisy().stream().map(LogSpam.Source::mod).toList());
        assertEquals(List.of(), LogSpam.analyze(List.of(before)).onlyNew(normal).noisy(), "same as before: normal");
    }

    @Test
    void knownHarmlessLogLinesBlameNobody() {
        StringBuilder log = new StringBuilder("""
                [24Oct2026 17:41:40.000] [main/INFO] [LOADING]: Mod list:
                		Mystical Agriculture 8.0 (mysticalagriculture)
                		MineColonies 1.1 (minecolonies)
                """);
        for (int i = 0; i < 80; i++) {
            log.append("[24Oct2026 17:41:52.136] [Worker-ResourceReload-18/WARN] [net.minecraft.client.resources.model"
                    + ".ModelBakery/]: Unable to load model: 'mysticalagriculture:item/crop").append(i)
                    .append("_essence' referenced from: mysticalagriculture:item/crop").append(i).append("_essence\n");
            log.append("[24Oct2026 17:42:00.746] [Render thread/WARN] [net.minecraft.client.sounds.SoundEngine/]: "
                    + "Missing sound for event: minecolonies:citizen.marksman.male").append(i).append(".general\n");
        }
        assertEquals(List.of(), LogSpam.analyze(List.of(log.toString())).noisy());
        assertEquals(Map.of(), LogProblems.analyze(List.of(log.toString()), null).missingAssets());
    }
}
