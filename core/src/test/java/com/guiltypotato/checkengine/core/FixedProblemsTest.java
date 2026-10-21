package com.guiltypotato.checkengine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guiltypotato.checkengine.core.scan.Finding;
import com.guiltypotato.checkengine.core.scan.FixedProblems;
import com.guiltypotato.checkengine.core.world.SafeRemove;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 0.18: the "Fixed!" toast and the worlds offered for a backup after a downgrade. */
class FixedProblemsTest {
    @TempDir
    Path game;

    private static Finding warn(String title) {
        return new Finding(Finding.Severity.WARNING, "x", title, "", "", List.of());
    }

    @Test
    void goneProblemsAreFixedOnce() {
        Path ce = game.resolve("checkengine");
        assertTrue(FixedProblems.update(ce, List.of(warn("Missing mod: A"), warn("Same job: B"))).isEmpty(), "first launch");
        List<String> fixed = FixedProblems.update(ce, List.of(warn("Same job: B")));
        assertEquals(List.of("Missing mod: A"), fixed);
        assertEquals("Missing mod: A", FixedProblems.toastText(fixed));
        assertTrue(FixedProblems.update(ce, List.of(warn("Same job: B"))).isEmpty(), "said once");
    }

    @Test
    void changeWarningsThatClearThemselvesAreNotFixes() {
        Path ce = game.resolve("checkengine");
        FixedProblems.update(ce, List.of(new Finding(Finding.Severity.WARNING,
                com.guiltypotato.checkengine.core.scan.Changes.DOWNGRADE_CODE, "AppleSkin went back", "", "", List.of())));
        assertTrue(FixedProblems.update(ce, List.of()).isEmpty(), "a downgrade warning going away isn't a fix");
    }

    @Test
    void recentWorldsSkipBackupsAndFolders() throws Exception {
        Path saves = game.resolve("saves");
        Files.createDirectories(saves.resolve("Old"));
        Files.writeString(saves.resolve("Old/level.dat"), "x");
        Files.createDirectories(saves.resolve("New"));
        Files.writeString(saves.resolve("New/level.dat"), "x");
        saves.resolve("Old/level.dat").toFile().setLastModified(1000);
        Files.createDirectories(saves.resolve("New - backup before x 2026"));
        Files.writeString(saves.resolve("New - backup before x 2026/level.dat"), "x");
        Files.createDirectories(saves.resolve("not a world"));
        assertEquals(List.of("New", "Old"), SafeRemove.recentWorlds(game, 3).stream()
                .map(p -> p.getFileName().toString()).toList());
    }
}
