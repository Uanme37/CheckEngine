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
}
