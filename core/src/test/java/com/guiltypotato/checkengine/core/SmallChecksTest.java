package com.guiltypotato.checkengine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guiltypotato.checkengine.core.data.RecipeCheck;
import com.guiltypotato.checkengine.core.scan.BadPairs;
import com.guiltypotato.checkengine.core.scan.Changes;
import com.guiltypotato.checkengine.core.scan.Finding;
import com.guiltypotato.checkengine.core.scan.SameJob.Installed;
import com.guiltypotato.checkengine.core.scan.SettingsReset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 0.17: known bad mod pairs, settings reset by an update, recipe clashes with Polymorph. */
class SmallChecksTest {
    @TempDir
    Path game;

    @Test
    void badPairNamedUnlessAnAddOnJoinsThem() {
        var f = BadPairs.check(Map.of("bettercombat", new Installed("Better Combat", "bc.jar"),
                "epicfight", new Installed("Epic Fight", "ef.jar")));
        assertEquals(1, f.size());
        assertEquals("Better Combat and Epic Fight", f.get(0).title());
        assertEquals(Finding.Severity.WARNING, f.get(0).severity());
        assertTrue(BadPairs.check(Map.of("bettercombat", new Installed("Better Combat", "bc.jar"),
                "epicfight", new Installed("Epic Fight", "ef.jar"),
                "bcef", new Installed("Better Combat x Epic Fight Compat", "x.jar"))).isEmpty());
    }

    @Test
    void createWithShadersIsInfoAndColorwheelSilencesIt() {
        var f = BadPairs.check(Map.of("create", new Installed("Create", "create.jar"),
                "iris", new Installed("Iris", "iris.jar")));
        assertEquals(1, f.size());
        assertEquals(Finding.Severity.INFO, f.get(0).severity());
        assertTrue(BadPairs.check(Map.of("create", new Installed("Create", "create.jar"),
                "iris", new Installed("Iris", "iris.jar"),
                "colorwheel", new Installed("Colorwheel", "cw.jar"))).isEmpty());
    }

    @Test
    void updateThatResetsSettingsIsCaughtAndOldFileKept() throws Exception {
        Path ce = game.resolve("checkengine");
        Path saved = ce.resolve("config-checkpoint/files/config/create-client.toml");
        Files.createDirectories(saved.getParent());
        Files.writeString(saved, "[client]\n\tfov = 90\n\tshake = false\n");
        Files.createDirectories(game.resolve("config"));
        Files.writeString(game.resolve("config/create-client.toml"), "[client]\n\tfov = 70\n\tshake = true\n\tnewThing = 1\n");
        Changes changes = new Changes("2026-10-20T10:00", List.of(
                new Changes.Change(Changes.Type.UPDATED, "create", "Create", "6.0.8", "6.0.10", "create.jar"),
                new Changes.Change(Changes.Type.CONFIG, "config", "config/create-client.toml", null, "changed",
                        "config/create-client.toml")));
        var f = SettingsReset.check(changes, game, ce);
        assertEquals(1, f.size(), String.valueOf(f));
        assertEquals("Create settings may be back to default", f.get(0).title());
        assertTrue(f.get(0).detail().contains("client.fov: 90 -> 70"), f.get(0).detail());
        assertTrue(f.get(0).detail().contains("2 settings"), f.get(0).detail());
        assertTrue(Files.isRegularFile(ce.resolve("settings-before-update/config/create-client.toml")));
    }

    @Test
    void onlyNewSettingsOrAnotherModsFileIsFine() throws Exception {
        Path ce = game.resolve("checkengine");
        Path saved = ce.resolve("config-checkpoint/files/config/create-client.toml");
        Files.createDirectories(saved.getParent());
        Files.writeString(saved, "fov = 90\n");
        Files.createDirectories(game.resolve("config"));
        Files.writeString(game.resolve("config/create-client.toml"), "fov = 90\nnewThing = 1\n");
        Changes changes = new Changes("2026-10-20T10:00", List.of(
                new Changes.Change(Changes.Type.UPDATED, "create", "Create", "6.0.8", "6.0.10", "create.jar"),
                new Changes.Change(Changes.Type.CONFIG, "config", "config/create-client.toml", null, "changed",
                        "config/create-client.toml")));
        assertTrue(SettingsReset.check(changes, game, ce).isEmpty());
    }

    @Test
    void polymorphMakesClashesInfo() {
        RecipeCheck check = new RecipeCheck(id -> id);
        RecipeCheck.Slot stick = RecipeCheck.slot("minecraft:stick", List.of("minecraft:stick"));
        check.shapeless("a:one", "mod/a", "a:one", List.of(stick, stick));
        check.shapeless("b:two", "mod/b", "b:two", List.of(stick, stick));
        check.playersCanPick(true);
        var f = check.findings().stream().filter(x -> x.code().equals(Finding.RECIPE_CLASH)).toList();
        assertEquals(1, f.size());
        assertEquals(Finding.Severity.INFO, f.get(0).severity());
        assertFalse(f.get(0).title().isEmpty());
    }

    @Test
    void jarJavaIsReadFromItsClassesAndTinyHelpersDontCount() throws Exception {
        assertEquals(21, com.guiltypotato.checkengine.core.scan.JarReader.read(classJar("mod.jar", 3)).java());
        assertEquals(0, com.guiltypotato.checkengine.core.scan.JarReader.read(classJar("data.jar", 1)).java());
    }

    private Path classJar(String name, int classes) throws Exception {
        Path jar = game.resolve(name);
        try (var out = new java.util.zip.ZipOutputStream(Files.newOutputStream(jar))) {
            for (int i = 0; i < classes; i++) {
                out.putNextEntry(new java.util.zip.ZipEntry("a/C" + i + ".class"));
                out.write(new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0, 0, 0, 65});
                out.closeEntry();
            }
        }
        return jar;
    }
}
