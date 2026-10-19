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
        assertTrue(w.fixNote().startsWith("CurseForge:"), w.fixNote());
        assertTrue(w.fix().contains("helps") && w.detail().contains("can run on less"), w.fix());
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

    /** Real ATM11: CurseForge said 4 GB, loading alone used 2.7 GB, 4 GB crashed with out of memory: warn. */
    @Test
    void measuredUseBeatsATooLowCurseForgeNumber() throws Exception {
        Files.createDirectories(game.resolve("mods"));
        Files.createDirectories(game.resolve("checkengine"));
        Files.writeString(game.resolve("minecraftinstance.json"), "{\"name\":\"All the Mods 11\",\"recommendedRam\":4096}");
        Files.writeString(game.resolve("checkengine").resolve("ram.properties"), "max_mb=12544\nused_after_load_mb=2714\n");
        SetupCheck.Warning w = SetupCheck.memory(game, 4096);
        assertNotNull(w);
        assertTrue(w.problem().contains("measured on your last launch"), w.problem());
        assertNull(SetupCheck.memory(game, 8192));
    }

    /** The pack maker's recommended_ram wins over CurseForge's number and Check Engine's measurement. */
    @Test
    void thePackMakersRecommendationWins() throws Exception {
        Files.createDirectories(game.resolve("config"));
        Files.createDirectories(game.resolve("mods"));
        Files.writeString(game.resolve("config").resolve("checkengine-pack.properties"), "pack_name=My Pack\nrecommended_ram=10G\n");
        SetupCheck.Warning w = SetupCheck.memory(game, 2048);
        assertNotNull(w);
        assertTrue(w.problem().contains("My Pack recommends 10 GB"), w.problem());
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
