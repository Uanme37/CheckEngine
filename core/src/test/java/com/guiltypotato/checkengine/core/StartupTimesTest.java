package com.guiltypotato.checkengine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.guiltypotato.checkengine.core.scan.StartupTimes;
import com.guiltypotato.checkengine.core.scan.StartupTimes.ModTime;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StartupTimesTest {
    @TempDir
    Path dir;

    private static StartupTimes.Data sample() {
        return new StartupTimes.Data(130_000, 20_000, 70_000, 40_000, List.of(
                new ModTime("jei", "Just Enough Items", 400),
                new ModTime("kubejs", "KubeJS", 8_400),
                new ModTime("tiny", "Tiny Mod", 12)), "2026-10-07T12:30");
    }

    @Test
    void timesReadLikeAPerson() {
        assertEquals("850 ms", StartupTimes.time(850));
        assertEquals("4.2 s", StartupTimes.time(4_200));
        assertEquals("2 min 05 s", StartupTimes.time(125_000));
    }

    @Test
    void reportListsSlowestFirstAndSkipsTinyOnes() {
        String text = StartupTimes.format(sample(), false);
        assertTrue(text.contains("Title screen after 2 min 10 s"), text);
        assertTrue(text.indexOf("KubeJS") < text.indexOf("Just Enough Items"), text);
        assertFalse(text.contains("Tiny Mod"), text);
    }

    @Test
    void savesAndLoadsForTheChecker() throws IOException {
        assertNull(StartupTimes.load(dir));
        StartupTimes.save(dir, sample(), true);
        StartupTimes.Data back = StartupTimes.load(dir);
        assertEquals(130_000, back.totalMs());
        assertEquals("kubejs", back.mods().get(0).id());
        assertTrue(StartupTimes.wasServer(dir));
    }
}
