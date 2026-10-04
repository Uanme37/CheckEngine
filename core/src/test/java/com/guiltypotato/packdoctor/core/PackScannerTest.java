package com.guiltypotato.packdoctor.core;

import static com.guiltypotato.packdoctor.core.TestJars.dep;
import static com.guiltypotato.packdoctor.core.TestJars.jar;
import static com.guiltypotato.packdoctor.core.TestJars.neoMod;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guiltypotato.packdoctor.core.model.Side;
import com.guiltypotato.packdoctor.core.scan.Finding;
import com.guiltypotato.packdoctor.core.scan.PackFolder;
import com.guiltypotato.packdoctor.core.scan.PackScanner;
import com.guiltypotato.packdoctor.core.scan.Report;
import com.guiltypotato.packdoctor.core.scan.ScanOptions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PackScannerTest {
    @TempDir
    Path mods;

    private static final ScanOptions CLIENT_1211 = new ScanOptions(Side.CLIENT, "1.21.1", "21.1.251", Set.of());

    private Report scan(ScanOptions o) throws IOException {
        return PackScanner.scan(mods, o);
    }

    @Test
    void healthyPackHasNoFindings() throws IOException {
        neoMod("ponder", "1.0.82", null).writeTo(mods, "ponder.jar");
        neoMod("create", "6.0.10", dep("create", "ponder", "required", "[1.0.82,)", "BOTH")
                + dep("create", "minecraft", "required", "[1.21.1]", "BOTH")
                + dep("create", "neoforge", "required", "[21.1.219,)", "BOTH")).writeTo(mods, "create.jar");
        Report r = scan(CLIENT_1211);
        assertEquals(List.of(), r.findings(), r.toText());
    }

    @Test
    void twoVersionsOfOneModAreDuplicates() throws IOException {
        neoMod("alexscaves", "2.0.2", null).writeTo(mods, "alexscaves-2.0.2.jar");
        neoMod("alexscaves", "2.0.10", null).writeTo(mods, "alexscaves-2.0.10.jar");
        List<Finding> dupes = scan(CLIENT_1211).byCode(Finding.DUPLICATE_MOD);
        assertEquals(1, dupes.size());
        assertTrue(dupes.get(0).fix().contains("alexscaves-2.0.10.jar"), "should keep the newest");
    }

    @Test
    void missingDependencyIsReportedOncePerMissingMod() throws IOException {
        neoMod("continuity", "3.0.0", dep("continuity", "connector", "required", "[2.0,)", "CLIENT"))
                .writeTo(mods, "continuity.jar");
        neoMod("other", "1.0", dep("other", "connector", "required", "*", "BOTH")).writeTo(mods, "other.jar");
        List<Finding> missing = scan(CLIENT_1211).byCode(Finding.MISSING_DEPENDENCY);
        assertEquals(1, missing.size());
        assertTrue(missing.get(0).detail().contains("2 mods need connector"));
    }

    @Test
    void clientSideDependencyIsNotNeededOnServer() throws IOException {
        neoMod("create", "6.0.10", dep("create", "flywheel", "required", "[1.0,2.0)", "CLIENT"))
                .writeTo(mods, "create.jar");
        assertEquals(1, scan(CLIENT_1211).byCode(Finding.MISSING_DEPENDENCY).size());
        assertEquals(0, scan(CLIENT_1211.withSide(Side.SERVER)).byCode(Finding.MISSING_DEPENDENCY).size());
    }

    @Test
    void wrongVersionOfDependencyOrOptionalMod() throws IOException {
        neoMod("flywheel", "0.6.10", null).writeTo(mods, "flywheel.jar");
        neoMod("lithium", "0.14.0", null).writeTo(mods, "lithium.jar");
        neoMod("create", "6.0.10", dep("create", "flywheel", "required", "[1.0.0,2.0)", "BOTH")
                + dep("create", "lithium", "optional", "[0.14.7,)", "BOTH")).writeTo(mods, "create.jar");
        assertEquals(2, scan(CLIENT_1211).byCode(Finding.WRONG_VERSION).size());
    }

    @Test
    void wrongMinecraftVersion() throws IOException {
        neoMod("oldmod", "1.0", dep("oldmod", "minecraft", "required", "[1.20.1]", "BOTH")).writeTo(mods, "old.jar");
        assertEquals(1, scan(CLIENT_1211).byCode(Finding.WRONG_VERSION).size());
        // unknown Minecraft version: can't judge, so stay quiet
        assertEquals(0, scan(ScanOptions.of(Side.CLIENT)).byCode(Finding.WRONG_VERSION).size());
    }

    @Test
    void sloppyRangeForSameMinecraftLineIsIgnored() throws IOException {
        // Real JEI 1.21.1 says "[1.21, 1.21.1)" and NeoForge loads it fine on 1.21.1.
        neoMod("pen", "1.0", dep("pen", "minecraft", "required", "[1.21]", "BOTH")).writeTo(mods, "pen.jar");
        neoMod("jei", "1.0", dep("jei", "minecraft", "required", "[1.21, 1.21.1)", "BOTH")).writeTo(mods, "jei.jar");
        assertEquals(List.of(), scan(CLIENT_1211).findings());
    }

    @Test
    void incompatibleAndDiscouragedMods() throws IOException {
        neoMod("optifine", "1.0", null).writeTo(mods, "optifine.jar");
        neoMod("rubidium", "1.0", null).writeTo(mods, "rubidium.jar");
        neoMod("sodium", "0.6", dep("sodium", "optifine", "incompatible", "*", "BOTH")
                + dep("sodium", "rubidium", "discouraged", "*", "BOTH")).writeTo(mods, "sodium.jar");
        Report r = scan(CLIENT_1211);
        assertEquals(1, r.byCode(Finding.INCOMPATIBLE_MOD).size());
        assertEquals(1, r.byCode(Finding.DISCOURAGED_MOD).size());
        assertEquals(Finding.Severity.WARNING, r.byCode(Finding.DISCOURAGED_MOD).get(0).severity());
    }

    @Test
    void dependencyInsideAnotherJarCounts() throws IOException {
        jar().with("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\nFMLModType: LIBRARY\n\n")
                .nest("META-INF/jarjar/kff.jar", neoMod("kotlinforforge", "5.12.0", null))
                .writeTo(mods, "kotlinforforge-all.jar");
        neoMod("kotlinmod", "1.0", dep("kotlinmod", "kotlinforforge", "required", "[5.0,)", "BOTH"))
                .writeTo(mods, "kotlinmod.jar");
        assertEquals(List.of(), scan(CLIENT_1211).findings());
    }

    @Test
    void bundledModsNeedTheirDependenciesToo() throws IOException {
        // Real create-aeronautics-bundled: the outer jar needs nothing, the mods inside it need Create.
        jar().with("META-INF/neoforge.mods.toml", TestJars.modsToml("aeronautics_bundle", "1.3.2", null))
                .nest("META-INF/jarjar/aeronautics.jar",
                        neoMod("aeronautics", "1.3.2", dep("aeronautics", "create", "required", "[6.0.10,)", "BOTH")))
                .writeTo(mods, "create-aeronautics-bundled.jar");
        List<Finding> missing = scan(CLIENT_1211).byCode(Finding.MISSING_DEPENDENCY);
        assertEquals(1, missing.size());
        assertEquals(List.of("create-aeronautics-bundled.jar"), missing.get(0).files());
    }

    /** Just enough of a class file: the @Mod annotation plus string entries (tag 1, 2-byte length, text). */
    private static String fakeModClass(String... strings) {
        StringBuilder sb = new StringBuilder("Êþº¾");
        for (String s : List.of(strings)) sb.append('\u0001').append((char) 0).append((char) s.length()).append(s);
        return sb.append("\u0001\u0000\u001ELnet/neoforged/fml/common/Mod;").toString();
    }

    @Test
    void hiddenDependencyInModClass() throws IOException {
        // Real Bobber Detector: its @Mod constructor uses Create's TooltipModifier without declaring Create.
        jar().with("META-INF/neoforge.mods.toml", TestJars.modsToml("bobberdetector", "1.0.3", null))
                .with("net/bobber/BobberDetectorImpl.class",
                        fakeModClass("com/simibubi/create/foundation/item/TooltipModifier"))
                .writeTo(mods, "bobberdetector.jar");
        // Real Create Goggles: checks Mods.MEKANISM.isLoaded() first, and its own compat/mekanism package is not Mekanism.
        jar().with("META-INF/neoforge.mods.toml", TestJars.modsToml("creategoggles", "6.1.1", null))
                .with("com/robocraft999/creategoggles/Main.class",
                        fakeModClass("MEKANISM", "isLoaded", "Lmekanism/api/Thing;",
                                "com/robocraft999/creategoggles/compat/mekanism/CompatMekanism"))
                .writeTo(mods, "creategoggles.jar");
        List<Finding> hidden = scan(CLIENT_1211).byCode(Finding.MISSING_DEPENDENCY);
        assertEquals(1, hidden.size(), hidden.toString());
        assertEquals("Hidden missing mod: Bobberdetector uses create", hidden.get(0).title());
        // With Create installed it's fine.
        neoMod("create", "6.0.10", null).writeTo(mods, "create.jar");
        assertEquals(0, scan(CLIENT_1211).byCode(Finding.MISSING_DEPENDENCY).size());
    }

    @Test
    void jarVersionPlaceholderComesFromManifest() throws IOException {
        jar().with("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\nImplementation-Version: 0.5.0\n\n")
                .with("META-INF/neoforge.mods.toml", TestJars.modsToml("lib", "${file.jarVersion}", null))
                .writeTo(mods, "lib.jar");
        neoMod("user", "1.0", dep("user", "lib", "required", "[1.0,)", "BOTH")).writeTo(mods, "user.jar");
        List<Finding> wrong = scan(CLIENT_1211).byCode(Finding.WRONG_VERSION);
        assertEquals(1, wrong.size());
        assertTrue(wrong.get(0).detail().contains("0.5.0"));
    }

    @Test
    void clientOnlyModsOnlyFlaggedOnServer() throws IOException {
        neoMod("sodium", "0.6", null).writeTo(mods, "sodium.jar");
        neoMod("myhud", "1.0", dep("myhud", "minecraft", "required", "[1.21.1]", "CLIENT")
                + dep("myhud", "neoforge", "required", "*", "CLIENT")).writeTo(mods, "myhud.jar");
        neoMod("tagged", "1.0", null).writeTo(mods, "Tagged-1.0.jar");
        neoMod("create", "6.0.10", null).writeTo(mods, "create.jar");
        ScanOptions server = new ScanOptions(Side.SERVER, "1.21.1", "21.1.251", Set.of("tagged-1.0.jar"));
        List<Finding> clientOnly = scan(server).byCode(Finding.CLIENT_ONLY_ON_SERVER);
        assertEquals(3, clientOnly.size(), clientOnly.toString());
        assertTrue(clientOnly.stream().noneMatch(f -> f.files().contains("create.jar")));
        assertEquals(0, scan(CLIENT_1211).byCode(Finding.CLIENT_ONLY_ON_SERVER).size());
    }

    @Test
    void newerMinecraftPatchIsNotForgiven() throws IOException {
        // Real Oh The Biomes We've Gone 4.4.0 wants 1.21.11; NeoForge refuses it on 1.21.1.
        neoMod("biomeswevegone", "4.4.0", dep("biomeswevegone", "minecraft", "required", "[1.21.11,)", "BOTH"))
                .writeTo(mods, "bwg.jar");
        assertEquals(1, scan(CLIENT_1211).byCode(Finding.WRONG_VERSION).size());
    }

    @Test
    void oldMandatoryFalseIsStillRequiredInNeoForgeToml() throws IOException {
        // Real Ars Botania 1.2.0: NeoForge ignores "mandatory" and crashed on the missing mod.
        neoMod("ars_botania", "1.2.0", """
                [[dependencies."ars_botania"]]
                modId="arseng"
                mandatory=false
                versionRange="[1.1.0,)"
                side="BOTH"
                """).writeTo(mods, "ars_botania.jar");
        assertEquals(1, scan(CLIENT_1211).byCode(Finding.MISSING_DEPENDENCY).size());
    }

    @Test
    void forgePackReadsTheForgeHalfOfDualLoaderJars() throws IOException {
        // Real bossesrise-1.20.1-forge: mods.toml (1.20.1 Forge) and neoforge.mods.toml (1.21.1) in one jar.
        jar().with("META-INF/mods.toml", TestJars.modsToml("bosses_rise", "1.0.9", dep("bosses_rise", "minecraft",
                        "required", "[1.20.1]", "BOTH")))
                .with("META-INF/neoforge.mods.toml", TestJars.modsToml("bosses_rise", "1.0.9", dep("bosses_rise",
                        "minecraft", "required", "[1.21.1]", "BOTH")))
                .writeTo(mods, "bossesrise.jar");
        ScanOptions forge = new ScanOptions(Side.CLIENT, "1.20.1", "47.4.4", Set.of(), ScanOptions.Loader.FORGE);
        assertEquals(List.of(), scan(forge).findings());
        // On 1.21.1 the NeoForge half is read instead (its [1.20.1] Forge half would be an error).
        assertEquals(List.of(), scan(CLIENT_1211).findings());
    }

    @Test
    void loaderPluginsAndConnectorOnForge() throws IOException {
        // Real Connector 1.20.1: a ModLauncher plug-in, its mod jar not listed as jar-in-jar.
        jar().with("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\nAutomatic-Module-Name: org.sinytra.connector\n"
                        + "Implementation-Version: 1.0.0-beta.48\n\n")
                .with("META-INF/services/cpw.mods.modlauncher.api.ITransformationService", "x")
                .writeTo(mods, "Connector-1.0.0-beta.48+1.20.1.jar");
        // Real Preloading Tricks: a plug-in that also carries fabric.mod.json.
        jar().with("fabric.mod.json", "{\"id\": \"preloading_tricks\", \"version\": \"3.3.1\"}")
                .with("META-INF/services/net.minecraftforge.forgespi.locating.IModLocator", "x")
                .writeTo(mods, "preloading-tricks-3.3.1.jar");
        jar().with("fabric.mod.json", "{\"id\": \"cinderscapes\", \"version\": \"4.0\"}").writeTo(mods, "cinderscapes.jar");
        jar().with("META-INF/mods.toml", TestJars.modsToml("fabricbridge", "1.0", dep("fabricbridge", "connectormod",
                "required", "*", "BOTH"))).writeTo(mods, "bridge.jar");
        ScanOptions forge = new ScanOptions(Side.CLIENT, "1.20.1", "47.4.4", Set.of(), ScanOptions.Loader.FORGE);
        assertEquals(List.of(), scan(forge).findings(), scan(forge).toText());
    }

    @Test
    void appleSkinIsFineOnServer() throws IOException {
        // Real AppleSkin marks its neoforge dependency CLIENT, but servers run it to sync hunger data.
        neoMod("appleskin", "3.0.9", dep("appleskin", "neoforge", "required", "*", "CLIENT"))
                .writeTo(mods, "appleskin.jar");
        ScanOptions server = new ScanOptions(Side.SERVER, "1.21.1", "21.1.251", Set.of());
        assertEquals(0, scan(server).byCode(Finding.CLIENT_ONLY_ON_SERVER).size());
    }

    @Test
    void emptyModsFolderIsNotClean() throws IOException {
        assertEquals(1, scan(CLIENT_1211).byCode(Finding.NO_MODS).size());
    }

    @Test
    void wrongLoaderJars() throws IOException {
        jar().with("fabric.mod.json", "{\"id\": \"fabricthing\", \"version\": \"1.0\", \"name\": \"Fabric Thing\",}")
                .writeTo(mods, "fabricthing.jar");
        jar().with("META-INF/mods.toml", TestJars.modsToml("forgething", "1.0", null)).writeTo(mods, "forgething.jar");
        neoMod("create", "6.0.10", null).writeTo(mods, "create.jar");
        List<Finding> wrong = scan(CLIENT_1211).byCode(Finding.WRONG_LOADER);
        assertEquals(2, wrong.size(), wrong.toString());
        // With Sinytra Connector, Fabric mods are fine.
        neoMod("connector", "2.0", null).writeTo(mods, "connector.jar");
        assertEquals(1, scan(CLIENT_1211).byCode(Finding.WRONG_LOADER).size());
        // On 1.20.1, mods.toml is the normal format, and NeoForge-only (1.20.5+) jars are the wrong ones.
        ScanOptions old = new ScanOptions(Side.CLIENT, "1.20.1", null, Set.of());
        List<Finding> legacy = scan(old).byCode(Finding.WRONG_LOADER);
        assertEquals(List.of("Mod for a newer Minecraft: Connector", "Mod for a newer Minecraft: Create"),
                legacy.stream().map(Finding::title).toList());
    }

    @Test
    void brokenJarsAndStrayFiles() throws IOException {
        Files.writeString(mods.resolve("halfdownloaded.jar"), "not a zip");
        Files.writeString(mods.resolve("shaders.zip"), "x");
        jar().with("readme.txt", "hi").writeTo(mods, "random.jar");
        Files.writeString(mods.resolve("disabled.jar.disabled"), "ignored");
        Report r = scan(CLIENT_1211);
        assertEquals(1, r.byCode(Finding.BROKEN_JAR).size());
        assertEquals(1, r.byCode(Finding.STRAY_FILE).size());
        assertEquals(1, r.byCode(Finding.NOT_A_MOD).size());
        assertEquals(2, r.jars().size());
        assertTrue(r.hasErrors());
    }

    @Test
    void readsCurseForgeInstance(@TempDir Path instance) throws IOException {
        Path instMods = Files.createDirectory(instance.resolve("mods"));
        Files.writeString(instance.resolve("minecraftinstance.json"), """
                {"gameVersion": "1.21.1", "baseModLoader": {"name": "neoforge-21.1.251"},
                 "installedAddons": [
                   {"installedFile": {"fileName": "Sodium.jar", "gameVersion": ["Client", "1.21.1", "NeoForge"]}},
                   {"installedFile": {"fileName": "create.jar", "gameVersion": ["Client", "Server", "1.21.1"]}}
                 ]}
                """);
        PackFolder pack = PackFolder.locate(instance, Side.SERVER);
        assertEquals(instMods, pack.modsFolder());
        assertEquals("1.21.1", pack.options().minecraftVersion());
        assertEquals("21.1.251", pack.options().neoforgeVersion());
        assertEquals(Set.of("sodium.jar"), pack.options().clientOnlyFiles());
        // Pointing at the mods folder itself finds the same instance info.
        assertEquals("21.1.251", PackFolder.locate(instMods, Side.SERVER).options().neoforgeVersion());
        assertFalse(PackFolder.locate(instMods, Side.SERVER).options().clientOnlyFiles().isEmpty());
    }
}
