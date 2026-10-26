package com.guiltypotato.checkengine.core;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guiltypotato.checkengine.core.crash.CrashTranslator;
import com.guiltypotato.checkengine.core.scan.Finding;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * The crash library: hundreds of real crash reports (public GitHub issues and our own test packs) in
 * CheckEngine/crash-library/, kept on the dev PC only (other people's logs, so never committed). Every build checks
 * that Crash Doctor still reads all of them and doesn't get worse at naming the mod. Skipped where the folder is
 * missing (CI).
 */
class CrashLibraryTest {
    /** How many reports may end in "couldn't tell which mod" (2026-10-14: 132 of 400). Lower it as rules improve. */
    private static final double MAX_UNCLEAR_SHARE = 0.34;

    @Test
    void crashDoctorReadsTheWholeLibrary() throws Exception {
        Path library = Path.of("..", "crash-library");
        Assumptions.assumeTrue(Files.isDirectory(library), "no crash library on this machine");
        List<Path> reports;
        try (Stream<Path> files = Files.walk(library)) {
            reports = files.filter(p -> p.toString().endsWith(".txt")).toList();
        }
        Assumptions.assumeTrue(reports.size() >= 50, "library too small to judge");
        int unclear = 0;
        List<String> unreadable = new ArrayList<>();
        for (Path p : reports) {
            String text = Files.readString(p, StandardCharsets.UTF_8);
            Finding main = CrashTranslator.translate(text).findings().get(0); // never throws, never empty
            if (main.code().equals("unknown-crash")) unreadable.add(p.getFileName().toString());
            if (main.title().equals("Game crashed") || main.title().equals("Mod loading problem")
                    || main.code().equals("unknown-crash")) {
                unclear++;
            }
        }
        double share = (double) unclear / reports.size();
        System.out.printf("Crash library: %d reports, %d unclear (%.0f%%), %d unreadable%n", reports.size(), unclear,
                share * 100, unreadable.size());
        assertTrue(share <= MAX_UNCLEAR_SHARE, "Crash Doctor got worse: " + unclear + " of " + reports.size()
                + " reports name no mod");
    }
}
