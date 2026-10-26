package com.guiltypotato.checkengine.core.strip;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guiltypotato.checkengine.core.TestJars;
import com.guiltypotato.checkengine.core.scan.PackSnapshot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Back to how the pack last started: new mods (and what needs them) off, updated ones left alone, and back on. */
class UndoNewModsTest {
    @TempDir
    Path game;

    @Test
    void switchesOffNewModsAndPutsThemBack() throws Exception {
        Path mods = Files.createDirectories(game.resolve("mods"));
        Path ce = game.resolve("checkengine");
        TestJars.neoMod("create", "6.0.4", null).writeTo(mods, "create-6.0.4.jar"); // updated: stays
        TestJars.neoMod("oldie", "1.0", null).writeTo(mods, "oldie-1.0.jar");
        TestJars.neoMod("shiny", "2.0", null).writeTo(mods, "shiny-2.0.jar"); // new
        TestJars.neoMod("shinyaddon", "1.0", TestJars.dep("shinyaddon", "shiny", "required", "[2.0,)", "BOTH"))
                .writeTo(mods, "shinyaddon-1.0.jar"); // new, and needs shiny
        TestJars.neoMod("checkengine", "0.13.0", null).writeTo(mods, "checkengine-0.13.0.jar"); // never
        new PackSnapshot("2026-10-14T10:00", "1.21.1", "21.1.252", Map.of(
                "create", new PackSnapshot.Entry("Create", "6.0.3", "create-6.0.3.jar"),
                "oldie", new PackSnapshot.Entry("Oldie", "1.0", "oldie-1.0.jar"))).save(ce);

        List<String> plan = UndoNewMods.plan(mods, ce);
        assertEquals(List.of("shiny-2.0.jar", "shinyaddon-1.0.jar"), plan.stream().sorted().toList());

        assertTrue(UndoNewMods.apply(mods, ce, plan).isEmpty());
        assertTrue(Files.exists(mods.resolve("shiny-2.0.jar.disabled")));
        assertTrue(Files.exists(mods.resolve("create-6.0.4.jar")));
        assertEquals(2, UndoNewMods.switchedOff(ce).size());

        assertEquals(2, UndoNewMods.putBack(mods, ce).size());
        assertTrue(Files.exists(mods.resolve("shiny-2.0.jar")));
        assertTrue(Files.exists(mods.resolve("shinyaddon-1.0.jar")));
        assertTrue(UndoNewMods.switchedOff(ce).isEmpty());
    }

    @Test
    void nothingToUndoWithoutAGoodLaunch() throws Exception {
        Path mods = Files.createDirectories(game.resolve("mods"));
        TestJars.neoMod("shiny", "2.0", null).writeTo(mods, "shiny-2.0.jar");
        assertTrue(UndoNewMods.plan(mods, game.resolve("checkengine")).isEmpty());
    }
}
