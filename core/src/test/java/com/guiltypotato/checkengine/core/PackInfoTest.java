package com.guiltypotato.checkengine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guiltypotato.checkengine.core.scan.PackInfo;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PackInfoTest {
    @TempDir
    Path game;

    /** CurseForge: the pack's name, not the loader's "name" that comes first in the file. */
    @Test
    void readsTheCurseForgeInstanceName() throws Exception {
        Files.writeString(game.resolve("minecraftinstance.json"), """
                {"baseModLoader":{"name":"neoforge-21.1.209","forgeVersion":"21.1.209"},
                 "installedAddons":[{"name":"Create","addonID":1}],
                 "name":"Beyond The Crank","isUnlocked":true}""");
        PackInfo p = PackInfo.load(game);
        assertEquals("Beyond The Crank", p.name());
        assertEquals("BEYOND THE CRANK", p.headlineName());
        assertNull(p.supportLink());
    }

    @Test
    void readsPrismsInstanceName() throws Exception {
        Path instance = game.resolve("My Pack");
        Path dotMinecraft = instance.resolve(".minecraft");
        Files.createDirectories(dotMinecraft);
        Files.writeString(instance.resolve("instance.cfg"), "InstanceType=OneSix\nname=Sky Factory 9\n");
        assertEquals("Sky Factory 9", PackInfo.load(dotMinecraft).name());
    }

    /** The pack maker's file wins, and a support link must be a web link. */
    @Test
    void thePackMakersSettingsWin() throws Exception {
        Files.createDirectories(game.resolve("config"));
        Files.writeString(game.resolve("minecraftinstance.json"), "{\"name\":\"Beyond The Crank (1)\"}");
        Files.writeString(game.resolve("config").resolve(PackInfo.FILE),
                "pack_name=Beyond the Crank\nsupport_link=https://discord.gg/example\n");
        PackInfo p = PackInfo.load(game);
        assertEquals("Beyond the Crank", p.name());
        assertEquals("https://discord.gg/example", p.supportLink());

        Files.writeString(game.resolve("config").resolve(PackInfo.FILE), "support_link=javascript:alert(1)\n");
        assertNull(PackInfo.load(game).supportLink());
    }

    @Test
    void writesAnEmptyTemplateForPackMakers() throws Exception {
        Files.createDirectories(game.resolve("config"));
        PackInfo p = PackInfo.load(game);
        assertNull(p.name());
        assertTrue(Files.readString(game.resolve("config").resolve(PackInfo.FILE)).contains("support_link="));
    }

    @Test
    void aLongNameIsLeftOutOfTheHeadline() {
        assertNull(new PackInfo("DarkRPG - RPG with Magic Origins Quests Adventure", null).headlineName());
    }

    /** Quiet mode: the pack maker hides warnings players don't need; problems that stop the game always show. */
    @Test
    void quietModeHidesWarningsNeverProblems() throws Exception {
        Files.createDirectories(game.resolve("config"));
        Files.writeString(game.resolve("config").resolve(PackInfo.FILE),
                "hide_warnings=same-job-mods, Xaero's Minimap, update-notes\n");
        PackInfo pack = PackInfo.load(game);
        var sameJob = new com.guiltypotato.checkengine.core.scan.Finding(
                com.guiltypotato.checkengine.core.scan.Finding.Severity.WARNING, "same-job-mods", "2 minimaps: a and b",
                "", "", java.util.List.of());
        var dupe = new com.guiltypotato.checkengine.core.scan.Finding(
                com.guiltypotato.checkengine.core.scan.Finding.Severity.WARNING, "duplicate-mod",
                "Duplicate mod: Xaero's Minimap", "", "", java.util.List.of());
        var other = new com.guiltypotato.checkengine.core.scan.Finding(
                com.guiltypotato.checkengine.core.scan.Finding.Severity.WARNING, "duplicate-mod", "Duplicate mod: JEI",
                "", "", java.util.List.of());
        var missing = new com.guiltypotato.checkengine.core.scan.Finding(
                com.guiltypotato.checkengine.core.scan.Finding.Severity.ERROR, "same-job-mods", "Missing mod: create",
                "", "", java.util.List.of());
        assertEquals(java.util.List.of(other, missing), pack.visible(java.util.List.of(sameJob, dupe, other, missing)));
        assertTrue(pack.hidesScreen("update-notes"));
        assertTrue(!pack.hidesScreen("settings"));

        Files.writeString(game.resolve("config").resolve(PackInfo.FILE), "quiet=true\n");
        PackInfo quiet = PackInfo.load(game);
        assertEquals(java.util.List.of(missing), quiet.visible(java.util.List.of(sameJob, dupe, other, missing)));
        assertTrue(quiet.hidesScreen("settings"));
    }
}
