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

    @Test
    void checkEngineAndThePlayersDisabledModsAreNeverSuspects() throws Exception {
        pack();
        State s = StripMine.start(mods(), folder(), "Game crashed", true, false);
        assertFalse(s.suspects().stream().anyMatch(n -> n.startsWith("checkengine")));
        assertFalse(s.suspects().contains("old-1.0.jar"));
        assertEquals(21, s.suspects().size());
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
        State s = StripMine.start(mods(), folder(), "Game crashed", true, true);
        Thread.sleep(20);
        StripMine.markWorked(folder());
        State next = StripMine.advance(game, mods(), folder(), null);
        assertEquals(s.round() + 1, next.round());
        assertEquals(s.toDisable(), next.suspects(), "no crash: the bad mod is in the half that was off");

        Thread.sleep(20);
        Files.createDirectories(game.resolve("crash-reports"));
        Files.writeString(game.resolve("crash-reports/crash-test.txt"), "Description: Initializing game\n");
        State third = StripMine.advance(game, mods(), folder(), null);
        assertEquals(next.round() + 1, third.round());
        assertFalse(third.suspects().stream().anyMatch(next.toDisable()::contains),
                "crashed: the half that was off is cleared");
    }
}
