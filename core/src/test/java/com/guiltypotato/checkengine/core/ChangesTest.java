package com.guiltypotato.checkengine.core;

import static com.guiltypotato.checkengine.core.TestJars.dep;
import static com.guiltypotato.checkengine.core.TestJars.neoMod;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guiltypotato.checkengine.core.model.Side;
import com.guiltypotato.checkengine.core.scan.Changes;
import com.guiltypotato.checkengine.core.scan.IssueText;
import com.guiltypotato.checkengine.core.scan.PackScanner;
import com.guiltypotato.checkengine.core.scan.PackSnapshot;
import com.guiltypotato.checkengine.core.scan.Report;
import com.guiltypotato.checkengine.core.scan.ScanOptions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChangesTest {
    @TempDir
    Path pack;
    Path mods;

    private static final ScanOptions CLIENT_1211 = new ScanOptions(Side.CLIENT, "1.21.1", "21.1.251", Set.of());

    @BeforeEach
    void setUp() throws IOException {
        mods = Files.createDirectories(pack.resolve("mods"));
    }

    /** What the mod does when the game reaches the title screen. */
    private void launchWorks() throws IOException {
        Report r = PackScanner.scan(mods, CLIENT_1211);
        PackSnapshot.of(r.jars(), r.options()).save(pack.resolve("checkengine"));
    }

    @Test
    void noRecordMeansNoChanges() throws IOException {
        neoMod("create", "6.0.4", null).writeTo(mods, "create.jar");
        assertNull(PackScanner.scan(mods, CLIENT_1211).changes());
    }

    @Test
    void listsAddedRemovedAndUpdatedMods() throws IOException {
        neoMod("create", "6.0.4", null).writeTo(mods, "create-6.0.4.jar");
        neoMod("jei", "19.0", null).writeTo(mods, "jei.jar");
        launchWorks();
        Files.move(mods.resolve("create-6.0.4.jar"), pack.resolve("old-create.jar"));
        neoMod("create", "6.0.6", null).writeTo(mods, "create-6.0.6.jar");
        Files.move(mods.resolve("jei.jar"), pack.resolve("old-jei.jar"));
        neoMod("sodium", "0.6", null).writeTo(mods, "sodium.jar");

        Changes c = PackScanner.scan(mods, CLIENT_1211).changes();
        assertEquals("Added: Sodium 0.6\nRemoved: Jei 19.0\nUpdated: Create 6.0.4 -> 6.0.6", c.summary());
    }

    @Test
    void removedModIsLinkedToTheProblemItCauses() throws IOException {
        neoMod("create", "6.0.4", null).writeTo(mods, "create.jar");
        neoMod("addona", "1.0", dep("addona", "create", "required", "[6.0,)", "BOTH")).writeTo(mods, "addona.jar");
        neoMod("jei", "19.0", null).writeTo(mods, "jei.jar");
        launchWorks();
        Files.move(mods.resolve("create.jar"), pack.resolve("create.jar"));

        Report r = PackScanner.scan(mods, CLIENT_1211);
        assertTrue(r.roots().get(0).detail().endsWith("): you removed Create 6.0.4."), r.roots().get(0).detail());
        assertTrue(r.toText().contains("Changed since it last worked"), r.toText());
        // On NeoForge's screen it's the first clue, with the list itself.
        String clue = IssueText.clue(IssueText.clues(r).get(0));
        assertTrue(clue.contains("Removed: Create 6.0.4"), clue);
    }

    @Test
    void addedModThatBreaksThePackGoesToTheTop() throws IOException {
        neoMod("jei", "19.0", null).writeTo(mods, "jei.jar");
        neoMod("old", "1.0", dep("old", "sodium", "required", "*", "BOTH")
                + dep("old", "iris", "required", "*", "BOTH")).writeTo(mods, "old.jar");
        launchWorks(); // pretend it worked: the old problem was there before
        neoMod("addona", "1.0", dep("addona", "create", "required", "*", "BOTH")).writeTo(mods, "addona.jar");

        Report r = PackScanner.scan(mods, CLIENT_1211);
        assertEquals("create is missing", r.roots().get(0).title(), r.toText());
        assertTrue(r.roots().get(0).detail().contains("you added Addona 1.0"), r.roots().get(0).detail());
    }

    /** Going back to an older version is a warning of its own: worlds saved with the newer one can break. */
    @Test
    void aDowngradeIsWarnedAbout() throws IOException {
        neoMod("create", "6.0.4", null).writeTo(mods, "create-6.0.4.jar");
        neoMod("jei", "19.0", null).writeTo(mods, "jei-19.0.jar");
        launchWorks();
        Files.delete(mods.resolve("create-6.0.4.jar"));
        neoMod("create", "6.0.2", null).writeTo(mods, "create-6.0.2.jar");
        Files.delete(mods.resolve("jei-19.0.jar"));
        neoMod("jei", "19.1", null).writeTo(mods, "jei-19.1.jar");

        Report r = PackScanner.scan(mods, CLIENT_1211);
        var down = r.findings().stream().filter(f -> f.code().equals(Changes.DOWNGRADE_CODE)).toList();
        assertEquals(1, down.size(), r.findings().toString());
        assertEquals("Create went back to an older version", down.get(0).title());
        assertTrue(r.changes().summary().contains("Downgraded: Create 6.0.4 -> 6.0.2"), r.changes().summary());
        assertTrue(r.changes().summary().contains("Updated: Jei 19.0 -> 19.1"), r.changes().summary());
    }

    /** A real 26.1 pack: 20 FancyMenu files after adding the mod read as one entry, a lone file stays named. */
    @Test
    void manyConfigFilesInOneFolderAreOneEntry() {
        java.util.List<Changes.Change> list = new java.util.ArrayList<>();
        for (int i = 0; i < 18; i++) list.add(new Changes.Change(Changes.Type.CONFIG, null, "config/fancymenu/ui_themes/t" + i + ".json", null, "added", null));
        list.add(new Changes.Change(Changes.Type.CONFIG, null, "config/ftbquests/quests/chapters/space.snbt", null, "changed", null));
        list.add(new Changes.Change(Changes.Type.CONFIG, null, "config/jei/options.ini", null, "changed", null));
        String summary = new Changes("2026-10-19T17:00", list).summary();
        assertEquals("Configs: config/fancymenu (18 files: 18 added), config/ftbquests/quests/chapters/space.snbt (changed), config/jei/options.ini (changed)", summary);
    }
}
