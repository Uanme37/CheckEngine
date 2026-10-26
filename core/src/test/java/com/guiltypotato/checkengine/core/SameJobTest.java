package com.guiltypotato.checkengine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guiltypotato.checkengine.core.scan.SameJob;
import com.guiltypotato.checkengine.core.scan.SameJob.Installed;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Two mods doing one job, without the false alarms seen in 35 real packs. */
class SameJobTest {
    @Test
    void twoMinimapsAreNamed() {
        var f = SameJob.check(Map.of("xaerominimap", new Installed("Xaero's Minimap", "xaero.jar"),
                "journeymap", new Installed("JourneyMap", "journeymap.jar")));
        assertEquals(1, f.size());
        assertTrue(f.get(0).title().startsWith("2 minimaps: "), f.get(0).title());
    }

    @Test
    void oneJarWithTwoNamesStubsAndKnownPairsAreFine() {
        // Embeddium also answers to "rubidium" from the same jar
        assertTrue(SameJob.check(Map.of("embeddium", new Installed("Embeddium", "embeddium.jar"),
                "rubidium", new Installed("Rubidium (Embeddium)", "embeddium.jar"))).isEmpty());
        // REI Plugin Compatibilities carries a JEI stand-in
        assertTrue(SameJob.check(Map.of("roughlyenoughitems", new Installed("Roughly Enough Items (REI)", "rei.jar"),
                "jei", new Installed("Roughly Enough Items (JEI Stub)", "reipc.jar"))).isEmpty());
        // EMI shows JEI's recipes on purpose
        assertTrue(SameJob.check(Map.of("jei", new Installed("JEI", "jei.jar"),
                "emi", new Installed("EMI", "emi.jar"))).isEmpty());
    }
}
