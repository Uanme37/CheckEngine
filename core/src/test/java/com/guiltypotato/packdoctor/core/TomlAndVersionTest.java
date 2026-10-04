package com.guiltypotato.packdoctor.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guiltypotato.packdoctor.core.model.Dependency;
import com.guiltypotato.packdoctor.core.model.ModInfo;
import com.guiltypotato.packdoctor.core.scan.JarReader;
import com.guiltypotato.packdoctor.core.toml.Toml;
import com.guiltypotato.packdoctor.core.version.ModVersion;
import com.guiltypotato.packdoctor.core.version.VersionRange;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TomlAndVersionTest {

    @Test
    void readsRealWorldModsToml() {
        String toml = """
                modLoader = "javafml" # trailing comment
                loaderVersion = "[0,)"
                license = "Read attached LICENSE.md"

                [[mods]]
                modId = "create"
                version = "${file.jarVersion}"
                displayName = "Create"
                description = '''Technology that
                empowers the player.'''

                [[mixins]]
                config = "create.mixins.json"

                [[dependencies."create"]]
                modId = "flywheel"
                type = "required"
                versionRange = "[1.0.0,2.0)"
                side = "CLIENT"

                [[dependencies.create]]
                modId = "lithium"
                mandatory = false
                versionRange = "[0.14.7,)"
                reason = "Versions before 0.14.7 crash"
                """;
        List<ModInfo> mods = JarReader.modsFromToml(toml, "6.0.10");
        assertEquals(1, mods.size());
        ModInfo create = mods.get(0);
        assertEquals("create", create.modId());
        assertEquals("6.0.10", create.version());
        assertEquals(2, create.dependencies().size());
        Dependency flywheel = create.dependencies().get(0);
        assertEquals(Dependency.Type.REQUIRED, flywheel.type());
        assertEquals(Dependency.DepSide.CLIENT, flywheel.side());
        Dependency lithium = create.dependencies().get(1);
        assertEquals(Dependency.Type.OPTIONAL, lithium.type());
        assertEquals("Versions before 0.14.7 crash", lithium.reason());
    }

    @Test
    void tomlValueKinds() {
        Map<String, Object> t = Toml.parse("""
                a = 1_000
                b = true
                c = [ "x", 'y',
                  "z", ]
                d = { e = "f", g = 2 }
                "quoted key" = "v\\u0041"
                [x.y]
                z = 1.5
                """);
        assertEquals(1000L, t.get("a"));
        assertEquals(true, t.get("b"));
        assertEquals(List.of("x", "y", "z"), t.get("c"));
        assertEquals(Map.of("e", "f", "g", 2L), t.get("d"));
        assertEquals("vA", t.get("quoted key"));
        assertEquals(1.5, ((Map<?, ?>) ((Map<?, ?>) t.get("x")).get("y")).get("z"));
    }

    @Test
    void versionsCompareLikeNeoForge() {
        assertTrue(ModVersion.parse("2.0.10").compareTo(ModVersion.parse("2.0.2")) > 0);
        assertEquals(0, ModVersion.parse("1.0").compareTo(ModVersion.parse("1.0.0")));
        assertTrue(ModVersion.parse("1.0-beta").compareTo(ModVersion.parse("1.0")) < 0);
        assertTrue(ModVersion.parse("1.0-rc1").compareTo(ModVersion.parse("1.0-beta.3")) > 0);
        assertTrue(ModVersion.parse("21.1.251").compareTo(ModVersion.parse("21.1.219")) > 0);
    }

    /** Cases taken from a real ATM10 install that NeoForge accepts. */
    @Test
    void mavenRulesFromRealPacks() {
        assertTrue(VersionRange.parse("[1.21-3.5.0,)").contains("1.21.1-3.9.9"));
        assertTrue(VersionRange.parse("[1.21-88,)").contains("1.21.1-93-NEOFORGE"));
        assertTrue(VersionRange.parse("[1.1.24,1.2.0)").contains("1.1.24+a"));
        assertFalse(VersionRange.parse("(,1.21-3.4.4]").contains("1.21.1-3.16.3"));
        assertTrue(VersionRange.parse("[1.21,1.21z)").contains("1.21"));
        assertTrue(VersionRange.parse("[21.0.0-beta,)").contains("21.1.251"));
    }

    @Test
    void rangesMatch() {
        VersionRange r = VersionRange.parse("[1.0.0,2.0)");
        assertTrue(r.contains("1.0.0"));
        assertTrue(r.contains("1.9.99"));
        assertFalse(r.contains("2.0"));
        assertFalse(r.contains("0.6.10"));
        assertTrue(VersionRange.parse("[1.21.1]").contains("1.21.1"));
        assertFalse(VersionRange.parse("[1.21.1]").contains("1.21"));
        assertTrue(VersionRange.parse("[21.1.219,)").contains("21.1.251"));
        assertTrue(VersionRange.parse("(,1.0],[1.2,)").contains("1.3"));
        assertFalse(VersionRange.parse("(,1.0],[1.2,)").contains("1.1"));
        assertTrue(VersionRange.parse("*").contains("anything"));
        assertTrue(VersionRange.parse("1.0").contains("5.0")); // bare version = any, like NeoForge
        assertEquals("1.0.0 or newer", VersionRange.parse("[1.0.0,)").describe());
        assertEquals("exactly 1.21.1", VersionRange.parse("[1.21.1]").describe());
    }
}
