package com.guiltypotato.checkengine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guiltypotato.checkengine.core.model.ModJar;
import com.guiltypotato.checkengine.core.scan.JarReader;
import com.guiltypotato.checkengine.core.scan.Materials;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Two mods each adding a tin ingot: one material, two items. */
class MaterialsTest {
    @TempDir
    Path mods;

    @Test
    void sameIngotFromTwoMods() throws Exception {
        TestJars.neoMod("techa", "1.0", null).with("assets/techa/models/item/tin_ingot.json", "{}")
                .with("assets/techa/models/item/deepslate_tin_ore.json", "{}")
                .with("assets/techa/models/item/weighted_pressure_plate.json", "{}").writeTo(mods, "techa.jar");
        TestJars.neoMod("techb", "1.0", null).with("assets/techb/models/item/tin_ingot.json", "{}")
                .with("assets/techb/models/item/tin_ore.json", "{}")
                .with("assets/techb/models/item/weighted_pressure_plate.json", "{}").writeTo(mods, "techb.jar");
        List<ModJar> jars = List.of(JarReader.read(mods.resolve("techa.jar")), JarReader.read(mods.resolve("techb.jar")));

        var dupes = Materials.find(jars);
        assertEquals(1, dupes.size(), dupes.toString());
        assertEquals("tin", dupes.get(0).material());
        assertEquals(List.of("ingot", "ore"), List.copyOf(dupes.get(0).kinds().keySet()));
        String text = Materials.toText(dupes, List.of());
        assertTrue(text.contains("Tin (ingot, ore) from 2 mods"), text);
        assertTrue(text.contains("Almost Unified"), text);
    }
}
