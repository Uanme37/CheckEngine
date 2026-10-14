package com.guiltypotato.checkengine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guiltypotato.checkengine.core.scan.ConfigCheckpoint;
import com.guiltypotato.checkengine.core.scan.ConfigCheckpoint.Kind;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** "One typo in a config broke my pack": say which config changed since it worked, and put it back. */
class ConfigCheckpointTest {
    @TempDir
    Path game;

    private Path folder() {
        return game.resolve("checkengine");
    }

    @Test
    void aChangedConfigIsFoundAndPutBack() throws Exception {
        Path config = game.resolve("config");
        Files.createDirectories(config.resolve("create"));
        Files.writeString(config.resolve("supplementaries-client.toml"), "book_texture = \"book\"\n");
        Files.writeString(config.resolve("create").resolve("server.toml"), "stress = 1.0\n");
        Files.write(config.resolve("menu-bg.png"), new byte[] {1, 2, 3}); // images aren't configs
        assertTrue(ConfigCheckpoint.changes(game, folder()).isEmpty(), "no checkpoint yet: nothing to compare");

        ConfigCheckpoint.save(game, folder()); // the launch worked
        assertTrue(ConfigCheckpoint.changes(game, folder()).isEmpty());

        // The typo, a new file, and a removed one.
        Files.writeString(config.resolve("supplementaries-client.toml"), "book_texture = \"bok\"\n");
        Files.writeString(config.resolve("new-mod.json"), "{}");
        Files.delete(config.resolve("create").resolve("server.toml"));
        List<ConfigCheckpoint.Change> changes = ConfigCheckpoint.changes(game, folder());
        assertEquals(3, changes.size(), changes.toString());
        assertTrue(changes.stream().anyMatch(c -> c.kind() == Kind.CHANGED
                && c.path().equals("config/supplementaries-client.toml")));

        Path kept = ConfigCheckpoint.restore(game, folder());
        assertEquals("book_texture = \"book\"\n", Files.readString(config.resolve("supplementaries-client.toml")));
        assertEquals("stress = 1.0\n", Files.readString(config.resolve("create").resolve("server.toml")));
        assertFalse(Files.exists(config.resolve("new-mod.json")), "added since: moved aside, the mod remakes it");
        assertEquals("book_texture = \"bok\"\n", Files.readString(kept.resolve("config/supplementaries-client.toml")),
                "the edited version is kept, never deleted");
        assertTrue(Files.exists(kept.resolve("config/new-mod.json")));
        assertTrue(ConfigCheckpoint.changes(game, folder()).isEmpty());
        assertNull(ConfigCheckpoint.restore(game, folder()), "nothing left to undo");
    }
}
