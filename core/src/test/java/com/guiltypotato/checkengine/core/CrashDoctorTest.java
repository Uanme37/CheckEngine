package com.guiltypotato.checkengine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guiltypotato.checkengine.core.crash.CrashDoctor;
import com.guiltypotato.checkengine.core.crash.CrashDoctor.LastCrash;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CrashDoctorTest {
    @TempDir
    Path game;

    private static final String CRASH = """
            ---- Minecraft Crash Report ----
            Time: 2026-10-08 12:30:00
            Description: Mod loading failures have occurred; consult the issue messages for more details

            net.neoforged.neoforge.logging.CrashReportExtender$ModLoadingCrashException: Mod loading has failed

            -- Mod loading issue for: create_submarine --
            Details:
            	Mod file: /C:/x/mods/create_submarine-2.2.4.jar
            	Failure message: Mod create_submarine requires aeronautics 1.1.3 or above
            		Currently, aeronautics is not installed

            -- System Details --
            """;

    private Path ce() {
        return game.resolve("checkengine");
    }

    private Path crash(String name, long ageMillis) throws IOException {
        Path dir = Files.createDirectories(game.resolve("crash-reports"));
        Path f = Files.writeString(dir.resolve(name), CRASH);
        Files.setLastModifiedTime(f, FileTime.fromMillis(System.currentTimeMillis() - ageMillis));
        return f;
    }

    @Test
    void explainsTheLastSessionsCrashOnce() throws IOException {
        assertNull(CrashDoctor.checkAndStartSession(game, ce())); // first launch with Check Engine
        crash("crash-2026-10-08_12.30.00-fml.txt", 0);

        LastCrash c = CrashDoctor.checkAndStartSession(game, ce());
        assertNotNull(c);
        assertEquals("Missing mod: aeronautics", c.main().title());
        assertTrue(c.save(ce()).toFile().isFile());
        assertTrue(Files.readString(ce().resolve(CrashDoctor.REPORT_FILE)).contains("your last game crashed"));

        assertNull(CrashDoctor.checkAndStartSession(game, ce()), "already shown");
    }

    /** From a real test run: a mod that throws while loading, with Check Engine's own clues in the same report. */
    @Test
    void modThatCrashedWhileLoadingIsNamedAndOurOwnNotesAreSkipped() {
        String report = """
                ---- Minecraft Crash Report ----
                Description: Mod loading failures have occurred; consult the issue messages for more details

                net.neoforged.neoforge.logging.CrashReportExtender$ModLoadingCrashException: Mod loading has failed

                -- Mod loading issue --
                Details:
                	Mod file: <No mod information provided>
                	Failure message: Check Engine clue: Duplicate mod: AppleSkin
                		Fix: Keep appleskin-neoforge-mc1.21-3.0.9.jar (the newest) and remove the others.
                	Mod version: <No mod information provided>

                -- Mod loading issue for: cecrash --
                Details:
                	Mod file: /C:/x/mods/cecrash-1.0.0.jar
                	Failure message: CE Test Crash Mod (cecrash) has failed to load correctly
                		java.lang.IllegalStateException: CE test: this mod crashes on purpose
                	Mod version: 1.0.0
                	Exception message: java.lang.IllegalStateException: CE test: this mod crashes on purpose
                """;
        var f = com.guiltypotato.checkengine.core.crash.CrashTranslator.translate(report).findings();
        assertEquals(1, f.size(), f.toString());
        assertEquals("CE Test Crash Mod crashed while loading", f.get(0).title());
        assertTrue(f.get(0).detail().endsWith("Its error: IllegalStateException: CE test: this mod crashes on purpose"),
                f.get(0).detail());
        assertEquals(java.util.List.of("cecrash-1.0.0.jar"), f.get(0).files());
    }

    /** NeoForge 26.1 lists loading errors under the exception, without "-- Mod loading issue --" blocks. */
    @Test
    void readsTheNeoForge261LoadingErrorList() {
        String report = """
                ---- Minecraft Crash Report ----
                Description: Bootstrap

                net.neoforged.fml.ModLoadingException: Loading errors encountered:
                	- CE Test Crash Mod (cecrash) has failed to load correctly
                	  java.lang.IllegalStateException: CE test: this mod crashes on purpose
                Loading warnings encountered:
                	- Check Engine clue: Duplicate mod: CE Test Dupe
                	  Fix: Keep cedupe-1.1.0.jar (the newest) and remove the others.

                	at net.neoforged.fml.ModLoader.waitForFuture(ModLoader.java:253) ~[loader-11.0.15.jar:11.0] {}
                """;
        var f = com.guiltypotato.checkengine.core.crash.CrashTranslator.translate(report).findings();
        assertEquals(1, f.size(), f.toString());
        assertEquals("CE Test Crash Mod crashed while loading", f.get(0).title());
    }

    @Test
    void ignoresCrashesFromBeforeCheckEngine() throws IOException {
        crash("crash-2025-01-01_10.00.00-client.txt", 86_400_000L * 30);
        assertNull(CrashDoctor.checkAndStartSession(game, ce()));
        assertNull(CrashDoctor.checkAndStartSession(game, ce()));
    }
}
