package com.guiltypotato.checkengine.core;

import static com.guiltypotato.checkengine.core.TestJars.dep;
import static com.guiltypotato.checkengine.core.TestJars.jar;
import static com.guiltypotato.checkengine.core.TestJars.neoMod;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guiltypotato.checkengine.core.model.Side;
import com.guiltypotato.checkengine.core.scan.LoadBlockers;
import com.guiltypotato.checkengine.core.scan.LoadBlockers.Blocker;
import com.guiltypotato.checkengine.core.scan.LoadBlockers.Kind;
import com.guiltypotato.checkengine.core.scan.PackScanner;
import com.guiltypotato.checkengine.core.scan.Report;
import com.guiltypotato.checkengine.core.scan.RootProblem;
import com.guiltypotato.checkengine.core.scan.ScanOptions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LoadBlockersTest {
    @TempDir
    Path mods;

    private static final ScanOptions CLIENT_1211 = new ScanOptions(Side.CLIENT, "1.21.1", "21.1.251", Set.of());

    private Report scan() throws IOException {
        return PackScanner.scan(mods, CLIENT_1211);
    }

    @Test
    void oneMissingModIsOneRootProblemWithEverythingItStops() throws IOException {
        neoMod("addona", "1.0", dep("addona", "create", "required", "[6.0,)", "BOTH")).writeTo(mods, "addona.jar");
        neoMod("addonb", "1.0", dep("addonb", "create", "required", "[6.0,)", "BOTH")).writeTo(mods, "addonb.jar");
        // Needs addona, so it can't load either, even though nothing is wrong with it.
        neoMod("addonc", "1.0", dep("addonc", "addona", "required", "*", "BOTH")).writeTo(mods, "addonc.jar");
        Report r = scan();
        assertEquals(1, r.roots().size(), r.toText());
        RootProblem p = r.roots().get(0);
        assertEquals("create is missing", p.title());
        assertEquals(List.of("Addona", "Addonb", "Addonc"), p.affected());
        assertTrue(p.detail().contains("1 more mod can't load"), p.detail());
        assertTrue(p.detail().contains("Addonc (needs Addona)"), p.detail());
        assertNull(p.certainty());
        assertTrue(r.toText().contains("FIX THESE FIRST (1 root problem)"), r.toText());
        assertTrue(r.health().endsWith("0 OK, 0 with warnings, 3 can't load"), r.health());
    }

    @Test
    void missingModThatIsReallyTheFabricBuild() throws IOException {
        neoMod("addona", "1.0", dep("addona", "create", "required", "[6.0,)", "BOTH")).writeTo(mods, "addona.jar");
        jar().with("fabric.mod.json", "{\"schemaVersion\":1,\"id\":\"create\",\"version\":\"6.0.4\",\"name\":\"Create\"}")
                .writeTo(mods, "create-fabric-6.0.4.jar");
        RootProblem p = scan().roots().get(0);
        assertEquals("Create is the Fabric version", p.title(), p.detail());
        assertEquals("very likely", p.certainty());
        assertTrue(p.fix().startsWith("Swap create-fabric-6.0.4.jar for the NeoForge or Forge version"), p.fix());
    }

    @Test
    void missingModThatIsSwitchedOff() throws IOException {
        neoMod("addona", "1.0", dep("addona", "create", "required", "[6.0,)", "BOTH")).writeTo(mods, "addona.jar");
        Files.writeString(mods.resolve("create-1.21.1-6.0.4.jar.disabled"), "x");
        Files.writeString(mods.resolve("createaddition-1.0.jar.disabled"), "x");
        RootProblem p = scan().roots().get(0);
        assertEquals("create is switched off", p.title());
        assertTrue(p.fix().startsWith("Rename create-1.21.1-6.0.4.jar.disabled back"), p.fix());
    }

    @Test
    void missingModWhoseDownloadBroke() throws IOException {
        neoMod("addona", "1.0", dep("addona", "create", "required", "[6.0,)", "BOTH")).writeTo(mods, "addona.jar");
        Files.writeString(mods.resolve("create-1.21.1-6.0.4.jar"), "not a zip");
        RootProblem p = scan().roots().get(0);
        assertEquals("create's file is broken", p.title());
        assertEquals("likely", p.certainty());
    }

    @Test
    void tooOldTooNewAndNoSingleVersion() {
        RootProblem old = analyze(new Blocker(Kind.WRONG_VERSION, "a", "Addon A", "create", "Create", "[6.0,)", "5.1.0",
                "needs the new API")).get(0);
        assertEquals("Create is too old", old.title());
        assertEquals("Update Create to 6.0 or newer.", old.fix());
        assertTrue(old.detail().contains("The mod says: \"needs the new API\""), old.detail());

        RootProblem tooNew = analyze(new Blocker(Kind.WRONG_VERSION, "a", "Addon A", "create", "Create", "[5.0,6.0)",
                "6.0.4", null)).get(0);
        assertEquals("Create is too new for Addon A", tooNew.title());

        RootProblem split = analyze(
                new Blocker(Kind.WRONG_VERSION, "a", "Addon A", "create", "Create", "[6.1,)", "6.0.4", null),
                new Blocker(Kind.WRONG_VERSION, "b", "Addon B", "create", "Create", "[5.0,6.0)", "6.0.4", null)).get(0);
        assertEquals("No single version of Create works for these mods", split.title());
    }

    @Test
    void modsForAnotherMinecraftAreOneProblem() {
        List<RootProblem> p = analyze(
                new Blocker(Kind.WRONG_VERSION, "a", "Old A", "minecraft", "Minecraft", "[1.20,1.21)", "1.21.1", null),
                new Blocker(Kind.WRONG_VERSION, "b", "Old B", "minecraft", "Minecraft", "[1.20.1]", "1.21.1", null));
        assertEquals(1, p.size());
        assertEquals("2 mods are made for a different Minecraft version", p.get(0).title());
        assertEquals("Get the versions of these mods made for Minecraft 1.21.1, or remove them.", p.get(0).fix());
    }

    @Test
    void biggestProblemComesFirst() {
        List<RootProblem> p = analyze(
                new Blocker(Kind.MISSING, "a", "Addon A", "jei", null, "*", null, null),
                new Blocker(Kind.MISSING, "b", "Addon B", "create", null, "*", null, null),
                new Blocker(Kind.MISSING, "c", "Addon C", "create", null, "*", null, null));
        assertEquals("create is missing", p.get(0).title());
    }

    @Test
    void incompatibleNamesBoth() {
        RootProblem p = analyze(new Blocker(Kind.INCOMPATIBLE, "sodium", "Sodium", "embeddium", "Embeddium", "*",
                "1.0", null)).get(0);
        assertEquals("Sodium doesn't work with Embeddium", p.title());
        assertEquals("Remove Sodium or Embeddium.", p.fix());
    }

    private static List<RootProblem> analyze(Blocker... blockers) {
        return LoadBlockers.analyze(List.of(blockers), List.of(), List.of(), null);
    }
}
