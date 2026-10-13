package com.guiltypotato.checkengine.core.intercept;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SetupCheckTest {
    @TempDir
    Path game;

    /** CurseForge pack with the author's 11.5 GB recommendation, launched with 4 GB: warn, in CurseForge's words. */
    @Test
    void farTooLittleMemoryIsCaught() throws Exception {
        Files.createDirectories(game.resolve("mods"));
        Files.writeString(game.resolve("minecraftinstance.json"),
                "{\"name\":\"Beyond The Crank\",\"recommendedRam\":11776,\"isUnlocked\":true}");
        SetupCheck.Warning w = SetupCheck.memory(game, 4096);
        assertNotNull(w);
        assertEquals("LOW MEMORY FOR BEYOND THE CRANK", w.headline());
        assertTrue(w.problem().contains("4 GB") && w.problem().contains("11.5 GB"), w.problem());
        assertTrue(w.fix().startsWith("CurseForge:"), w.fix());
        assertTrue(w.quitFirst());
    }

    /** A bit under the recommendation is fine: only far too little gets a window. */
    @Test
    void aLittleUnderIsNotWorthAWindow() throws Exception {
        Files.createDirectories(game.resolve("mods"));
        Files.writeString(game.resolve("minecraftinstance.json"), "{\"name\":\"P\",\"recommendedRam\":12000}");
        assertNull(SetupCheck.memory(game, 8192));
        assertNull(SetupCheck.memory(game, 12544));
    }

    @Test
    void onlyAMuchNewerJavaIsWorthAWindow() {
        assertNull(SetupCheck.java(21, 17, game), "Java 21 on 1.20.1 is common and fine");
        assertNull(SetupCheck.java(25, 21, game));
        SetupCheck.Warning w = SetupCheck.java(24, 17, game);
        assertNotNull(w);
        assertEquals("JAVA 24 MAY BREAK SOME MODS", w.headline());
        assertFalse(w.quitFirst(), "continue is the main button: it usually works");
    }

    @Test
    void dontAskAgainSticks() {
        Path folder = game.resolve("checkengine");
        assertFalse(SetupCheck.ignored(folder, "ignore_java"));
        SetupCheck.ignore(folder, "ignore_java");
        assertTrue(SetupCheck.ignored(folder, "ignore_java"));
        assertFalse(SetupCheck.ignored(folder, "ignore_memory"));
    }
}
