package com.guiltypotato.packdoctor.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guiltypotato.packdoctor.core.scan.Finding;
import com.guiltypotato.packdoctor.core.scan.RamAdvisor;
import com.guiltypotato.packdoctor.core.scan.RamAdvisor.Facts;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RamAdvisorTest {

    private static Finding advise(Facts f) {
        return RamAdvisor.advise(f).get(0);
    }

    @Test
    void estimateGrowsWithModsAndHeavyMods() {
        assertEquals(6144, RamAdvisor.recommendedMb(new Facts(79, List.of(), 0, null, 0, 0)));
        assertEquals(12288 + 2048, RamAdvisor.recommendedMb(new Facts(490, List.of("distanthorizons"), 0, null, 0, 0)));
    }

    @Test
    void realMeasurementWins() {
        // Used 5 GB after loading: double it.
        assertEquals(10240, RamAdvisor.recommendedMb(new Facts(79, List.of(), 0, null, 5000, 0)));
    }

    @Test
    void tooLittleIsAWarning() {
        Finding f = advise(new Facts(400, List.of(), 4096, "this pack's CurseForge setting", 0, 32768));
        assertEquals(Finding.Severity.WARNING, f.severity());
        assertTrue(f.title().startsWith("Memory: too little (4 GB, needs about 10 GB)"), f.title());
    }

    @Test
    void tooMuchForThePc() {
        Finding f = advise(new Facts(400, List.of(), 14336, "x", 0, 16384));
        assertEquals("Memory: leaves too little for Windows", f.title());
    }

    @Test
    void readsCurseForgeOverrideAndMeasurement(@TempDir Path pack) throws IOException {
        Path mods = Files.createDirectories(pack.resolve("mods"));
        Files.writeString(mods.resolve("DistantHorizons-2.3.jar"), "x");
        Files.writeString(mods.resolve("irissearch-1.0.jar"), "x"); // not Iris
        Files.writeString(pack.resolve("minecraftinstance.json"), "{\"isMemoryOverride\": true, \"allocatedMemory\": 8192}");
        Files.createDirectories(pack.resolve("packdoctor"));
        Files.writeString(pack.resolve("packdoctor/ram.properties"), "used_after_load_mb=3000\nmax_mb=12544\n");
        Facts f = RamAdvisor.facts(pack, mods);
        assertEquals(List.of("distanthorizons"), f.heavy());
        assertEquals(8192, f.allocatedMb());
        assertEquals(3000, f.measuredMb());
    }
}
