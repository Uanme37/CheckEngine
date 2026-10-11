package com.guiltypotato.checkengine.core.strip;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guiltypotato.checkengine.core.TestJars;
import com.guiltypotato.checkengine.core.model.ModJar;
import com.guiltypotato.checkengine.core.scan.JarReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StripMinePlanTest {
    @TempDir
    Path mods;

    /**
     * Beyond the Crank: Architectury 13.0.11 is a mod of its own, and another mod carries an old 13.0.6 copy inside.
     * Switching the real one off must switch its addons off too: the packed copy is too old for them.
     */
    @Test
    void aPackedOldCopyDoesNotKeepAddonsHappy() throws Exception {
        TestJars.neoMod("arch", "13.0.11", null).writeTo(mods, "arch-13.0.11.jar");
        TestJars.neoMod("bundler", "1.0", null).nest("META-INF/jarjar/arch-13.0.6.jar",
                TestJars.neoMod("arch", "13.0.6", null)).writeTo(mods, "bundler-1.0.jar");
        TestJars.neoMod("glowup", "1.0", TestJars.dep("glowup", "arch", "required", "[13.0.8,)", "BOTH"))
                .writeTo(mods, "glowup-1.0.jar");
        Map<String, ModJar> jars = new HashMap<>();
        try (Stream<Path> f = Files.list(mods)) {
            f.forEach(p -> jars.put(p.getFileName().toString(), JarReader.read(p)));
        }
        Set<String> off = StripMine.withDependents("arch-13.0.11.jar", jars);
        assertTrue(off.contains("glowup-1.0.jar"), "addon must go off with the real Architectury: " + off);
    }
}
