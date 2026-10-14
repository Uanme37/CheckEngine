package com.guiltypotato.checkengine.core.strip;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guiltypotato.checkengine.core.scan.ConfigCheckpoint;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Strip Mine over changed configs: finds the one bad edit, and the player's files are all back at the end. */
class ConfigStripTest {
    @TempDir
    Path game;

    private Path ce() {
        return game.resolve("checkengine");
    }

    private void write(String path, String text) throws Exception {
        Path f = game.resolve(path);
        Files.createDirectories(f.getParent());
        Files.writeString(f, text);
    }

    @Test
    void findsTheBadConfigAndPutsTheOthersBack() throws Exception {
        Files.createDirectories(game.resolve("mods"));
        for (int i = 0; i < 6; i++) write("config/mod" + i + ".toml", "value = 1\n");
        ConfigCheckpoint.save(game, ce()); // the pack worked like this
        for (int i = 0; i < 6; i++) write("config/mod" + i + ".toml", "value = 2\n"); // then 6 edits
        write("config/new.toml", "fresh = true\n"); // and a new file
        String bad = "config/mod4.toml";

        List<String> suspects = ConfigStrip.suspects(game, ce(), null);
        assertEquals(7, suspects.size());
        StripMine.State s = ConfigStrip.start(game, ce(), suspects, "boom", true, true);
        for (int round = 0; round < 10 && s.active(); round++) {
            // the launch crashes while the bad file still has the player's edit
            boolean crashes = Files.readString(game.resolve(bad)).contains("value = 2");
            s = StripMine.advance(game, game.resolve("mods"), ce(),
                    crashes ? StripMine.Outcome.CRASHED : StripMine.Outcome.WORKED);
        }
        assertEquals(bad, s.found());
        for (int i = 0; i < 6; i++) {
            assertEquals("value = 2\n", Files.readString(game.resolve("config/mod" + i + ".toml")), "player's edit back");
        }
        assertEquals("fresh = true\n", Files.readString(game.resolve("config/new.toml")));

        // QUIT AND FIX IT: the old version of just that file, the edit kept aside
        Path kept = ConfigCheckpoint.restoreOne(game, ce(), bad);
        assertEquals("value = 1\n", Files.readString(game.resolve(bad)));
        assertEquals("value = 2\n", Files.readString(kept));
    }

    @Test
    void aChangedModMeansAModSearch() throws Exception {
        write("config/a.toml", "x");
        write("config/b.toml", "x");
        ConfigCheckpoint.save(game, ce());
        write("config/a.toml", "y");
        write("config/b.toml", "y");
        var mods = new com.guiltypotato.checkengine.core.scan.Changes("t", List.of(new com.guiltypotato.checkengine
                .core.scan.Changes.Change(com.guiltypotato.checkengine.core.scan.Changes.Type.ADDED, "create",
                "Create", null, "6.0", "create.jar")));
        assertTrue(ConfigStrip.suspects(game, ce(), mods).isEmpty());
        assertEquals(2, ConfigStrip.suspects(game, ce(), null).size());
    }
}
