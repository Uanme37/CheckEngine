package com.guiltypotato.checkengine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guiltypotato.checkengine.core.strip.StripMine;
import com.guiltypotato.checkengine.core.strip.StripMine.Outcome;
import com.guiltypotato.checkengine.core.strip.StripMine.State;
import com.guiltypotato.checkengine.core.strip.StripMine.Status;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StripMineTest {
    @TempDir
    Path game;

    private Path mods() {
        return game.resolve("mods");
    }

    private Path folder() {
        return game.resolve("checkengine");
    }

    /** 20 mods; mod05..mod09 need "lib"; "lib" itself; Check Engine; and the player's own switched-off mod. */
    private void pack() throws Exception {
        Files.createDirectories(mods());
        for (int i = 0; i < 20; i++) {
            String id = "mod%02d".formatted(i);
            String deps = i >= 5 && i < 10 ? TestJars.dep(id, "lib", "required", "[1,)", "BOTH") : null;
            TestJars.neoMod(id, "1.0", deps).writeTo(mods(), id + "-1.0.jar");
        }
        TestJars.neoMod("lib", "1.0", null).writeTo(mods(), "lib-1.0.jar");
        TestJars.neoMod("checkengine", "0.11.0", null).writeTo(mods(), "checkengine-neoforge-1.21.1-0.11.0.jar");
        TestJars.neoMod("old", "1.0", null).writeTo(mods(), "old-1.0.jar.disabled");
    }

    private List<String> enabled() throws Exception {
        try (Stream<Path> f = Files.list(mods())) {
            return f.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".jar")).sorted().toList();
        }
    }

    /** Plays the game: it crashes while {@code bad} is switched on, and loads fine otherwise. */
    private State search(String bad) throws Exception {
        State s = StripMine.start(mods(), folder(), "Game crashed", true, true);
        for (int launches = 0; launches < 20 && s.active(); launches++) {
            List<String> on = enabled();
            assertTrue(on.contains("lib-1.0.jar") || on.stream().noneMatch(n -> n.matches("mod0[5-9]-1.0.jar")),
                    "a mod that needs lib was left on without it: " + on);
            Outcome result = on.contains(bad) ? Outcome.CRASHED : Outcome.WORKED;
            s = StripMine.advance(game, mods(), folder(), result);
        }
        return s;
    }

    @Test
    void findsTheBadModAndPutsEverythingBack() throws Exception {
        pack();
        List<String> before = enabled();
        for (String bad : List.of("mod00-1.0.jar", "mod07-1.0.jar", "mod19-1.0.jar", "lib-1.0.jar")) {
            State s = search(bad);
            assertEquals(Status.FOUND, s.status(), bad + ": " + s);
            assertEquals(bad, s.found());
            assertTrue(s.round() <= 7, "too many rounds for 21 suspects: " + s.round());
            assertEquals(before, enabled(), "every mod should be switched back on");
            StripMine.clear(folder());
        }
        assertTrue(Files.exists(mods().resolve("old-1.0.jar.disabled")), "the player's own .disabled file is untouched");
    }

    /**
     * Like Beyond the Crank: "hidden" uses "sneaky"'s code without declaring it, so switching sneaky off alone makes
     * hidden crash differently. Strip Mine must notice that isn't the original crash, keep sneaky on, and still find bad.
     */
    @Test
    void aDifferentCrashFromAnUndeclaredLibraryIsLearnedNotCounted() throws Exception {
        pack();
        TestJars.neoMod("sneaky", "1.0", null).with("com/sneaky/Api.class", new byte[] {1})
                .writeTo(mods(), "sneaky-1.0.jar");
        TestJars.neoMod("hidden", "1.0", null).writeTo(mods(), "hidden-1.0.jar");
        Path reports = game.resolve("crash-reports");
        Files.createDirectories(reports);
        String bad = "mod13-1.0.jar";
        State s = StripMine.start(mods(), folder(), "Mod13 crashed while loading", true, true);
        int launches = 0;
        for (; launches < 30 && s.active(); launches++) {
            List<String> on = enabled();
            Thread.sleep(15);
            if (on.contains(bad)) {
                Files.writeString(reports.resolve("crash-" + launches + ".txt"), """
                        Description: Mod loading error has occurred

                        -- Mod loading issue for: mod13 --
                        Details:
                        	Failure message: Mod13 (mod13) has failed to load correctly
                        		java.lang.IllegalStateException: boom
                        """);
            } else if (on.contains("hidden-1.0.jar") && !on.contains("sneaky-1.0.jar")) {
                Files.writeString(reports.resolve("crash-" + launches + ".txt"), """
                        Description: Initializing game

                        java.lang.NoClassDefFoundError: com/sneaky/Api
                        	at com.hidden.Hidden.init(Hidden.java:1) ~[hidden-1.0.jar%2312!/:?]
                        """);
            } else {
                StripMine.markWorked(folder());
            }
            Thread.sleep(15);
            s = StripMine.advance(game, mods(), folder(), null);
        }
        assertEquals(Status.FOUND, s.status(), String.join("\n", s.notes()));
        assertEquals(bad, s.found());
        assertTrue(launches <= 12, "took " + launches + " launches");
    }

    /**
     * Beyond the Crank: any mod failing to load ends in the same knock-on crash report ("Mod 'arch' is not
     * available"). Only latest.log says which mod failed, so that's what tells the original crash from new ones.
     */
    @Test
    void knockOnCrashReportsAreToldApartByTheLog() throws Exception {
        pack();
        TestJars.neoMod("sneaky", "1.0", null).with("com/sneaky/Api.class", new byte[] {1})
                .writeTo(mods(), "sneaky-1.0.jar");
        TestJars.neoMod("hidden", "1.0", null).writeTo(mods(), "hidden-1.0.jar");
        Path reports = game.resolve("crash-reports");
        Path logs = game.resolve("logs");
        Files.createDirectories(reports);
        Files.createDirectories(logs);
        String knockOn = """
                Description: Initializing game

                java.lang.IllegalStateException: Mod 'arch' is not available!
                	at TRANSFORMER/arch@1.0/dev.arch.Hooks.whenAvailable(Hooks.java:34) ~[arch-1.0.jar%23607!/:?]
                """;
        String bad = "mod13-1.0.jar";
        Files.writeString(logs.resolve("latest.log"),
                "[main/ERROR] Failed to create mod instance. ModID: mod13, class x.Mod13\n");
        Files.writeString(reports.resolve("crash-original.txt"), knockOn);
        State s = StripMine.start(mods(), folder(), "Mod didn't load: arch", true, true);
        assertEquals(List.of("mod13"), s.roots());
        int launches = 0;
        for (; launches < 30 && s.active(); launches++) {
            List<String> on = enabled();
            Thread.sleep(15);
            StringBuilder log = new StringBuilder("[main/INFO] Loading mods\n");
            boolean failed = false;
            if (on.contains(bad)) {
                log.append("[main/ERROR] Failed to create mod instance. ModID: mod13, class x.Mod13\n");
                failed = true;
            }
            if (on.contains("hidden-1.0.jar") && !on.contains("sneaky-1.0.jar")) {
                log.append("[main/ERROR] Failed to create mod instance. ModID: hidden, class x.Hidden\n")
                        .append("java.lang.NoClassDefFoundError: com/sneaky/Api\n");
                failed = true;
            }
            Files.writeString(logs.resolve("latest.log"), log.toString());
            if (failed) Files.writeString(reports.resolve("crash-" + launches + ".txt"), knockOn);
            else StripMine.markWorked(folder());
            Thread.sleep(15);
            s = StripMine.advance(game, mods(), folder(), null);
        }
        assertEquals(Status.FOUND, s.status(), String.join("\n", s.notes()));
        assertEquals(bad, s.found());
        assertTrue(launches <= 12, "took " + launches + " launches");
    }

    @Test
    void checkEngineAndThePlayersDisabledModsAreNeverSuspects() throws Exception {
        pack();
        State s = StripMine.start(mods(), folder(), "Game crashed", true, false);
        assertFalse(s.suspects().stream().anyMatch(n -> n.startsWith("checkengine")));
        assertFalse(s.suspects().contains("old-1.0.jar"));
        assertEquals(21, s.suspects().size(), "20 mods and lib");
        assertEquals(Status.PENDING, s.status());
        assertEquals(22, enabled().size(), "nothing renamed until the game is closed");
    }

    @Test
    void stopPutsEverythingBack() throws Exception {
        pack();
        List<String> before = enabled();
        StripMine.start(mods(), folder(), "Game crashed", true, true);
        assertTrue(enabled().size() < before.size());
        State s = StripMine.stop(mods(), folder());
        assertEquals(Status.STOPPED, s.status());
        assertEquals(before, enabled());
    }

    @Test
    void aLaunchThatNeitherCrashedNorLoadedRunsTheSameRoundAgain() throws Exception {
        pack();
        State s = StripMine.start(mods(), folder(), "Game crashed", true, true);
        List<String> on = enabled();
        State again = StripMine.advance(game, mods(), folder(), null); // no crash report, no marker
        assertEquals(s.round(), again.round());
        assertEquals(on, enabled());
    }

    @Test
    void crashReportAndTitleScreenAreDetected() throws Exception {
        pack();
        String crash = "Description: Initializing game\n\njava.lang.IllegalStateException: boom\n";
        State s = StripMine.start(mods(), folder(), com.guiltypotato.checkengine.core.crash.CrashTranslator
                .translate(crash).findings().get(0).title(), true, true);
        Thread.sleep(20);
        StripMine.markWorked(folder());
        State next = StripMine.advance(game, mods(), folder(), null);
        assertEquals(s.round() + 1, next.round());
        assertEquals(s.toDisable(), next.suspects(), "no crash: the bad mod is in the half that was off");

        Thread.sleep(20);
        Files.createDirectories(game.resolve("crash-reports"));
        Files.writeString(game.resolve("crash-reports/crash-test.txt"), crash);
        State third = StripMine.advance(game, mods(), folder(), null);
        assertEquals(next.round() + 1, third.round());
        assertFalse(third.suspects().stream().anyMatch(next.toDisable()::contains),
                "crashed: the half that was off is cleared");
    }
}
