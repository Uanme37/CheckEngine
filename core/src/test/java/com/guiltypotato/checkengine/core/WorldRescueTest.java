package com.guiltypotato.checkengine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guiltypotato.checkengine.core.crash.CrashTranslator;
import com.guiltypotato.checkengine.core.crash.WorldRescue;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A world that crashes every time it loads: back it up, remove the broken thing for one session, switch back. */
class WorldRescueTest {
    @TempDir
    Path game;

    private Path world(String name) throws Exception {
        Path w = game.resolve("saves").resolve(name);
        Files.createDirectories(w.resolve("region"));
        Files.writeString(w.resolve("level.dat"), "x");
        Files.writeString(w.resolve("region").resolve("r.0.0.mca"), "chunks");
        return w;
    }

    @Test
    void neoForgeRescueBacksUpTurnsOnAndOffAgain() throws Exception {
        Path w = world("My World");
        Path config = game.resolve("config").resolve("neoforge-server.toml");
        Files.createDirectories(config.getParent());
        Files.writeString(config, "#comment\nremoveErroringBlockEntities = false\nremoveErroringEntities = false\n");
        Path folder = game.resolve("checkengine");

        assertEquals(w, WorldRescue.newestWorld(game));
        Path backup = WorldRescue.start(game, folder, w);
        assertTrue(Files.exists(backup.resolve("region").resolve("r.0.0.mca")), "the world is copied first");
        assertTrue(Files.readString(config).contains("removeErroringEntities = true"));
        assertTrue(Files.readString(config).contains("removeErroringBlockEntities = true"));
        assertTrue(WorldRescue.active(folder));

        assertTrue(WorldRescue.finish(folder).contains("off again"));
        assertTrue(Files.readString(config).contains("removeErroringEntities = false"));
        assertFalse(WorldRescue.active(folder));
        assertTrue(Files.exists(folder.resolve("world-rescue-done.properties")), "a record stays");
        assertNull(WorldRescue.finish(folder), "only once");
    }

    /** Forge 1.20.1 keeps the setting in the world; a world that never made the file gets one. */
    @Test
    void forgeRescueWritesTheWorldsOwnConfig() throws Exception {
        Path w = world("Forge World");
        WorldRescue.start(game, game.resolve("checkengine"), w);
        String forge = Files.readString(w.resolve("serverconfig").resolve("forge-server.toml"));
        assertTrue(forge.contains("removeErroringEntities = true"), forge);
        WorldRescue.finish(game.resolve("checkengine"));
        assertTrue(Files.readString(w.resolve("serverconfig").resolve("forge-server.toml"))
                .contains("removeErroringEntities = false"));
    }

    @Test
    void onlyTickingCrashesGetARescue() {
        var ticking = CrashTranslator.translate("""
                Description: Ticking entity

                java.lang.NullPointerException: boom
                	at com.example.Mob.tick(Mob.java:1)

                -- Entity being ticked --
                Details:
                	Entity Type: example:mob (com.example.Mob)
                	Entity's Exact location: 120.50, 64.00, -33.20
                """).findings().get(0);
        assertTrue(WorldRescue.fits(ticking), ticking.code());
        assertTrue(WorldRescue.what(ticking).contains("example:mob"), WorldRescue.what(ticking));
        assertFalse(WorldRescue.fits(CrashTranslator.translate("Description: Initializing game\n\njava.lang.Exception: x\n")
                .findings().get(0)));
    }
}
