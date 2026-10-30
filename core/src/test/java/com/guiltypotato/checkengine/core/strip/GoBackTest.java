package com.guiltypotato.checkengine.core.strip;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guiltypotato.checkengine.core.TestJars;
import com.guiltypotato.checkengine.core.scan.GoodLaunch;
import com.guiltypotato.checkengine.core.scan.KeptJars;
import com.guiltypotato.checkengine.core.scan.PackSnapshot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Black box: the last good launch only counts when that session didn't crash, its jars are kept as hard links, and Go
 * back puts the pack back the way it was (new mods off, old versions back, removed mods back, configs back).
 */
class GoBackTest {
    @TempDir
    Path game;

    private Path mods() throws Exception {
        return Files.createDirectories(game.resolve("mods"));
    }

    private Path ce() {
        return game.resolve("checkengine");
    }

    private static final PackSnapshot WORKED = new PackSnapshot("2026-10-28T10:00", "1.21.1", "21.1.251", Map.of(
            "create", new PackSnapshot.Entry("Create", "6.0.3", "create-6.0.3.jar"),
            "oldie", new PackSnapshot.Entry("Oldie", "1.0", "oldie-1.0.jar"),
            "gone", new PackSnapshot.Entry("Gone Mod", "2.0", "gone-2.0.jar")));

    /** The pack that worked: create 6.0.3, oldie, gone; one config. Saved the way the title screen does, then settled. */
    private void goodLaunch() throws Exception {
        Path mods = mods();
        TestJars.neoMod("create", "6.0.3", null).writeTo(mods, "create-6.0.3.jar");
        TestJars.neoMod("oldie", "1.0", null).writeTo(mods, "oldie-1.0.jar");
        TestJars.neoMod("gone", "2.0", null).writeTo(mods, "gone-2.0.jar");
        Files.createDirectories(game.resolve("config"));
        Files.writeString(game.resolve("config/create-common.toml"), "speed = 1\n");
        assertNull(GoodLaunch.save(game, ce(), WORKED));
        closed();
        assertEquals(GoodLaunch.Result.WORKED, GoodLaunch.settle(game, ce()));
    }

    /** The game closed normally after the title screen (the shutdown hook ran). */
    private void closed() throws Exception {
        long saved = Files.getLastModifiedTime(GoodLaunch.pending(ce()).resolve(PackSnapshot.FILE)).toMillis();
        Files.writeString(ce().resolve(GoodLaunch.SESSION_END), Long.toString(saved + 1000));
    }

    /** Then the player updates Create (the launcher deletes the old jar), adds a mod, removes one, edits a config. */
    private void breakIt() throws Exception {
        Path mods = mods();
        Files.delete(mods.resolve("create-6.0.3.jar"));
        TestJars.neoMod("create", "6.0.4", null).writeTo(mods, "create-6.0.4.jar");
        TestJars.neoMod("shiny", "1.0", null).writeTo(mods, "shiny-1.0.jar");
        Files.delete(mods.resolve("gone-2.0.jar"));
        Files.writeString(game.resolve("config/create-common.toml"), "speed = 999\n");
    }

    @Test
    void keptJarsAreLinksThatOutliveTheLaunchersDelete() throws Exception {
        goodLaunch();
        Path kept = ce().resolve(KeptJars.FOLDER).resolve("mods").resolve("create-6.0.3.jar");
        assertTrue(Files.isSameFile(kept, mods().resolve("create-6.0.3.jar")), "a hard link, not a copy");
        assertFalse(Files.exists(GoodLaunch.pending(ce()).resolve(PackSnapshot.FILE)));
        breakIt();
        assertNotNull(KeptJars.usable(ce(), "create-6.0.3.jar"), "old jar still there after the update");
    }

    @Test
    void aJarChangedInPlaceIsNotUsed() throws Exception {
        goodLaunch();
        // An update that writes over the same file changes the kept link too: it's not the old version any more
        Files.write(mods().resolve("oldie-1.0.jar"), new byte[] {1, 2, 3}, java.nio.file.StandardOpenOption.APPEND);
        assertNull(KeptJars.usable(ce(), "oldie-1.0.jar"));
        assertNotNull(KeptJars.usable(ce(), "create-6.0.3.jar"));
    }

    @Test
    void aSessionThatCrashedAfterTheTitleScreenDoesNotCount() throws Exception {
        goodLaunch();
        breakIt();
        PackSnapshot broken = new PackSnapshot("2026-10-28T11:00", "1.21.1", "21.1.251", Map.of(
                "create", new PackSnapshot.Entry("Create", "6.0.4", "create-6.0.4.jar")));
        GoodLaunch.save(game, ce(), broken);
        Files.createDirectories(game.resolve("crash-reports"));
        Files.writeString(game.resolve("crash-reports/crash-2026-10-28_11.05.00-client.txt"), "crash");
        assertEquals(GoodLaunch.Result.CRASHED, GoodLaunch.settle(game, ce()));
        assertEquals("6.0.3", PackSnapshot.load(ce()).mods().get("create").version(), "the last one that worked");
        assertTrue(Files.exists(ce().resolve(GoodLaunch.CRASHED)));
        assertNotNull(KeptJars.usable(ce(), "create-6.0.3.jar"), "its kept jars stay");
        assertEquals(GoodLaunch.Result.NOTHING, GoodLaunch.settle(game, ce()), "settled once");
    }

    @Test
    void aKilledSessionDoesNotCount() throws Exception {
        goodLaunch();
        // Create updated (the launcher deletes the old jar), the title screen shows, then the game is killed:
        // no session end after the title screen
        Files.delete(mods().resolve("create-6.0.3.jar"));
        TestJars.neoMod("create", "6.0.4", null).writeTo(mods(), "create-6.0.4.jar");
        PackSnapshot updated = new PackSnapshot("2026-10-28T11:00", "1.21.1", "21.1.251", Map.of(
                "create", new PackSnapshot.Entry("Create", "6.0.4", "create-6.0.4.jar")));
        GoodLaunch.save(game, ce(), updated);
        long session = Long.parseLong(Files.readString(ce().resolve(GoodLaunch.SESSION_END)).strip());
        Files.setLastModifiedTime(GoodLaunch.pending(ce()).resolve(PackSnapshot.FILE), FileTime.fromMillis(session + 60_000));
        assertEquals(GoodLaunch.Result.UNFINISHED, GoodLaunch.settle(game, ce()));
        assertEquals("6.0.3", PackSnapshot.load(ce()).mods().get("create").version(), "the last one that worked");
        assertTrue(Files.exists(ce().resolve(GoodLaunch.UNFINISHED)));
        assertNotNull(KeptJars.usable(ce(), "create-6.0.3.jar"), "its kept jars stay");
        assertTrue(GoBack.plan(game, ce()).lines().contains("Put back the old Create (create-6.0.3.jar)"),
                "Go back still offers the old Create");
        assertEquals(GoodLaunch.Result.NOTHING, GoodLaunch.settle(game, ce()), "settled once");
    }

    @Test
    void goBackNeverTouchesCheckEngineItself() throws Exception {
        Path mods = mods();
        TestJars.neoMod("checkengine", "0.20.0", null).writeTo(mods, "checkengine-neoforge-1.21.1-0.20.0.jar");
        TestJars.neoMod("create", "6.0.3", null).writeTo(mods, "create-6.0.3.jar");
        assertNull(GoodLaunch.save(game, ce(), new PackSnapshot("2026-10-28T10:00", "1.21.1", "21.1.251", Map.of(
                "checkengine", new PackSnapshot.Entry("Check Engine", "0.20.0", "checkengine-neoforge-1.21.1-0.20.0.jar"),
                "create", new PackSnapshot.Entry("Create", "6.0.3", "create-6.0.3.jar")))));
        closed();
        assertEquals(GoodLaunch.Result.WORKED, GoodLaunch.settle(game, ce()));
        // Check Engine updated (old jar deleted, so a kept copy exists), and a new mod came in
        Files.delete(mods.resolve("checkengine-neoforge-1.21.1-0.20.0.jar"));
        TestJars.neoMod("checkengine", "0.22.0", null).writeTo(mods, "checkengine-neoforge-1.21.1-0.22.0.jar");
        TestJars.neoMod("shiny", "1.0", null).writeTo(mods, "shiny-1.0.jar");
        assertNotNull(KeptJars.usable(ce(), "checkengine-neoforge-1.21.1-0.20.0.jar"));
        GoBack.Plan plan = GoBack.plan(game, ce());
        assertEquals(List.of("shiny-1.0.jar"), plan.turnOff());
        assertTrue(plan.restore().isEmpty(), "no older Check Engine: " + plan.restore());
    }

    @Test
    void anHsErrFileCountsAsACrashToo() throws Exception {
        goodLaunch();
        GoodLaunch.save(game, ce(), WORKED);
        Files.writeString(game.resolve("hs_err_pid1234.log"), "EXCEPTION_ACCESS_VIOLATION");
        assertEquals(GoodLaunch.Result.CRASHED, GoodLaunch.settle(game, ce()));
    }

    @Test
    void anOldCrashReportDoesNotSpoilANewGoodLaunch() throws Exception {
        Files.createDirectories(game.resolve("crash-reports"));
        Path old = Files.writeString(game.resolve("crash-reports/crash-2026-10-01_10.00.00-client.txt"), "old");
        Files.setLastModifiedTime(old, FileTime.fromMillis(System.currentTimeMillis() - 86_400_000L));
        goodLaunch();
    }

    @Test
    void goBackPlansAndUndoesEverythingThenPutItBackRedoesIt() throws Exception {
        goodLaunch();
        breakIt();
        GoBack.Plan plan = GoBack.plan(game, ce());
        assertEquals(List.of("shiny-1.0.jar"), plan.turnOff());
        assertEquals(2, plan.restore().size());
        assertTrue(plan.restore().contains(new GoBack.Restore("Create", "create-6.0.3.jar", "create-6.0.4.jar")));
        assertTrue(plan.restore().contains(new GoBack.Restore("Gone Mod", "gone-2.0.jar", null)));
        assertEquals(1, plan.configs().size());
        assertEquals("Turn off 1 new mod, put back the old version of 1 mod, put back 1 removed mod, put back 1 config "
                + "file", plan.summary());
        assertTrue(plan.lines().contains("Put back the old Create (create-6.0.3.jar)"), plan.lines().toString());

        assertTrue(GoBack.apply(game, ce(), plan).isEmpty());
        Path mods = mods();
        assertTrue(Files.exists(mods.resolve("create-6.0.3.jar")));
        assertTrue(Files.exists(mods.resolve("create-6.0.4.jar.disabled")));
        assertTrue(Files.exists(mods.resolve("shiny-1.0.jar.disabled")));
        assertTrue(Files.exists(mods.resolve("gone-2.0.jar")));
        assertEquals("speed = 1\n", Files.readString(game.resolve("config/create-common.toml")));
        assertTrue(GoBack.plan(game, ce()).isEmpty(), "back to when it worked");
        assertTrue(GoBack.done(ce()));
        String undo = GoBack.undoSummary(ce());
        assertTrue(undo.startsWith("Turn 2 mods back on, turn off 2 old versions again, put back your configs (Go back "
                + "ran "), undo);

        assertFalse(GoBack.putBack(game, ce()).isEmpty());
        assertTrue(Files.exists(mods.resolve("create-6.0.4.jar")));
        assertTrue(Files.exists(mods.resolve("create-6.0.3.jar.disabled")));
        assertTrue(Files.exists(mods.resolve("shiny-1.0.jar")));
        assertTrue(Files.exists(mods.resolve("gone-2.0.jar.disabled")));
        assertEquals("speed = 999\n", Files.readString(game.resolve("config/create-common.toml")));
        assertFalse(GoBack.done(ce()));
        assertNull(GoBack.undoSummary(ce()));
    }

    @Test
    void anUpdateThatKeepsTheFileNameGoesBackAndPutBackCleanly() throws Exception {
        Path mods = mods();
        TestJars.neoMod("samey", "1.0", null).writeTo(mods, "samey.jar");
        assertNull(GoodLaunch.save(game, ce(), new PackSnapshot("2026-10-28T10:00", "1.21.1", "21.1.251", Map.of(
                "samey", new PackSnapshot.Entry("Samey", "1.0", "samey.jar")))));
        closed();
        assertEquals(GoodLaunch.Result.WORKED, GoodLaunch.settle(game, ce()));
        // The launcher deletes the old jar and writes the new one under the same name
        Files.delete(mods.resolve("samey.jar"));
        TestJars.neoMod("samey", "2.0", null).writeTo(mods, "samey.jar");
        byte[] two = Files.readAllBytes(mods.resolve("samey.jar"));
        GoBack.apply(game, ce(), GoBack.plan(game, ce()));
        assertFalse(java.util.Arrays.equals(two, Files.readAllBytes(mods.resolve("samey.jar"))), "1.0 is back on");
        assertTrue(java.util.Arrays.equals(two, Files.readAllBytes(mods.resolve("samey.jar.disabled"))));
        GoBack.putBack(game, ce());
        assertTrue(java.util.Arrays.equals(two, Files.readAllBytes(mods.resolve("samey.jar"))), "2.0 is back on");
        assertFalse(Files.exists(mods.resolve("samey.jar.disabled")));
        assertFalse(GoBack.done(ce()), "all handled: the record is gone");
        assertNotNull(KeptJars.usable(ce(), "samey.jar"), "1.0 is still kept");
    }

    @Test
    void twoRoundTripsNeverLeaveBothVersionsOn() throws Exception {
        goodLaunch();
        breakIt();
        for (int round = 0; round < 2; round++) {
            GoBack.apply(game, ce(), GoBack.plan(game, ce()));
            assertTrue(Files.exists(mods().resolve("create-6.0.3.jar")));
            assertFalse(Files.exists(mods().resolve("create-6.0.4.jar")));
            GoBack.putBack(game, ce());
            assertTrue(Files.exists(mods().resolve("create-6.0.4.jar")), "round " + round);
            assertFalse(Files.exists(mods().resolve("create-6.0.3.jar")), "never both on, round " + round);
            assertFalse(GoBack.done(ce()));
        }
        assertTrue(Files.exists(mods().resolve("shiny-1.0.jar")));
        assertNotNull(KeptJars.usable(ce(), "create-6.0.3.jar"));
    }

    @Test
    void putItBackTakesItsJarsOffTheSwitchOffListAndIgnoresPathsOutOfMods() throws Exception {
        goodLaunch();
        breakIt();
        GoBack.apply(game, ce(), GoBack.plan(game, ce()));
        // shiny was locked when Go back ran (26.1): it waits on the switch-off list for the next start
        Files.writeString(ce().resolve("switch-off-pending.txt"), "shiny-1.0.jar\nother.jar\n");
        // a record someone edited by hand: a path out of mods/ is never touched
        java.util.Properties p = new java.util.Properties();
        try (var in = Files.newBufferedReader(ce().resolve(GoBack.FILE))) {
            p.load(in);
        }
        p.setProperty("off", p.getProperty("off") + "|../outside.jar");
        Files.writeString(game.resolve("outside.jar.disabled"), "x");
        try (var out = Files.newBufferedWriter(ce().resolve(GoBack.FILE))) {
            p.store(out, null);
        }
        GoBack.putBack(game, ce());
        assertEquals(List.of("other.jar"), Files.readAllLines(ce().resolve("switch-off-pending.txt")));
        assertTrue(Files.exists(game.resolve("outside.jar.disabled")), "never touched");
        assertFalse(GoBack.done(ce()));
    }

    @Test
    void modsOnlyLeavesTheConfigsAsTheyAre() throws Exception {
        goodLaunch();
        breakIt();
        GoBack.apply(game, ce(), GoBack.plan(game, ce()), false, false);
        assertTrue(Files.exists(mods().resolve("create-6.0.3.jar")));
        assertEquals("speed = 999\n", Files.readString(game.resolve("config/create-common.toml")));
        assertEquals(1, GoBack.plan(game, ce()).configs().size(), "still changed");
        GoBack.putBack(game, ce());
        assertTrue(Files.exists(mods().resolve("create-6.0.4.jar")));
        assertFalse(GoBack.done(ce()));
    }

    @Test
    void inGameGoBackPutsConfigsBackAtTheNextStartAndDropsThisSessionsSave() throws Exception {
        goodLaunch();
        breakIt();
        GoodLaunch.save(game, ce(), WORKED); // this session reached the title screen
        GoBack.apply(game, ce(), GoBack.plan(game, ce()), true, true);
        assertFalse(Files.exists(GoodLaunch.pending(ce()).resolve(PackSnapshot.FILE)), "can't become the last good one");
        Path speed = game.resolve("config/create-common.toml");
        assertEquals("speed = 999\n", Files.readString(speed), "not yet: the game is still running");
        Files.writeString(speed, "speed = 1000\n"); // a mod saves its config as the game closes
        GoBack.applyPending(mods(), ce()); // next start, before mods load
        assertEquals("speed = 1\n", Files.readString(speed));
        GoBack.applyPending(mods(), ce());
        assertEquals("speed = 1\n", Files.readString(speed), "only once");
        GoBack.putBack(game, ce());
        assertEquals("speed = 1000\n", Files.readString(speed), "what was there before it went back");
    }

    @Test
    void putItBackKeepsAConfigEditedAfterGoBack() throws Exception {
        goodLaunch();
        breakIt();
        GoBack.apply(game, ce(), GoBack.plan(game, ce()));
        Path speed = game.resolve("config/create-common.toml");
        Files.writeString(speed, "speed = 5\n"); // the player changed it again after going back
        GoBack.putBack(game, ce());
        assertEquals("speed = 999\n", Files.readString(speed));
        try (var s = Files.walk(ce())) {
            assertTrue(s.filter(Files::isRegularFile).anyMatch(f -> f.toString().contains("before-put-back")
                    && f.endsWith("create-common.toml")), "the edit after Go back is kept aside, not lost");
        }
    }

    @Test
    void aGoBackCutShortCanStillBePutBack() throws Exception {
        goodLaunch();
        breakIt();
        GoBack.apply(game, ce(), GoBack.plan(game, ce()));
        // as if Go back stopped before shiny was switched off and before Create's old jar went in
        Files.move(mods().resolve("shiny-1.0.jar.disabled"), mods().resolve("shiny-1.0.jar"));
        Files.delete(mods().resolve("create-6.0.3.jar"));
        assertTrue(GoBack.done(ce()), "the record was written first");
        GoBack.putBack(game, ce());
        assertTrue(Files.exists(mods().resolve("shiny-1.0.jar")));
        assertTrue(Files.exists(mods().resolve("create-6.0.4.jar")));
        assertFalse(Files.exists(mods().resolve("create-6.0.3.jar")));
        assertFalse(GoBack.done(ce()));
    }

    @Test
    void thePlanSaysWhenItLastWorked() throws Exception {
        goodLaunch();
        breakIt();
        assertEquals("Last worked: 28 Oct 10:00", GoBack.plan(game, ce()).lastWorked());
    }

    @Test
    void modsWithoutAKeptJarAreNamedAndTheRestStillGoesBack() throws Exception {
        goodLaunch();
        breakIt();
        // the kept set is gone (an older Check Engine never kept one)
        Files.delete(ce().resolve(KeptJars.FOLDER).resolve("mods").resolve("create-6.0.3.jar"));
        GoBack.Plan plan = GoBack.plan(game, ce());
        assertEquals(List.of("Create 6.0.3"), plan.notKept());
        assertTrue(plan.lines().contains("Create 6.0.3: old version not kept, can't put it back"));
        assertEquals(1, plan.restore().size());
        GoBack.apply(game, ce(), plan);
        assertTrue(Files.exists(mods().resolve("create-6.0.4.jar")), "left as it is");
        assertTrue(Files.exists(mods().resolve("shiny-1.0.jar.disabled")));
    }

    @Test
    void aModRemovedBySwitchingItOffComesBackOnWithoutACopy() throws Exception {
        goodLaunch();
        Files.move(mods().resolve("gone-2.0.jar"), mods().resolve("gone-2.0.jar.disabled"));
        GoBack.Plan plan = GoBack.plan(game, ce());
        GoBack.apply(game, ce(), plan);
        assertTrue(Files.exists(mods().resolve("gone-2.0.jar")));
        assertFalse(Files.exists(mods().resolve("gone-2.0.jar.disabled")));
        GoBack.putBack(game, ce());
        assertTrue(Files.exists(mods().resolve("gone-2.0.jar.disabled")));
    }

    @Test
    void nothingToGoBackToWithoutAGoodLaunch() throws Exception {
        TestJars.neoMod("shiny", "1.0", null).writeTo(mods(), "shiny-1.0.jar");
        assertTrue(GoBack.plan(game, ce()).isEmpty());
        assertEquals("Nothing to go back to.", GoBack.plan(game, ce()).summary());
    }

    @Test
    void configsChangedInGameWhileItWorkedStayButEditsAfterClosingGoBack() throws Exception {
        goodLaunch();
        // Next session: title screen saves, then a mod (or the player, in game) changes its settings
        long title = System.currentTimeMillis() - 60_000;
        GoodLaunch.save(game, ce(), WORKED);
        Files.setLastModifiedTime(GoodLaunch.pending(ce()).resolve(PackSnapshot.FILE), FileTime.fromMillis(title));
        Path map = Files.writeString(game.resolve("config/xaero-map.cfg"), "ignored_update = 191\n");
        Path speed = game.resolve("config/create-common.toml");
        Files.writeString(speed, "speed = 2\n");
        Files.setLastModifiedTime(map, FileTime.fromMillis(title + 10_000));
        Files.setLastModifiedTime(speed, FileTime.fromMillis(title + 20_000));
        Files.writeString(ce().resolve(GoodLaunch.SESSION_END), Long.toString(title + 30_000));
        // Then, with the game closed, the player edits a config: that may be what breaks it
        Files.writeString(speed, "speed = 999\n");
        Files.setLastModifiedTime(speed, FileTime.fromMillis(title + 90_000));
        assertEquals(GoodLaunch.Result.WORKED, GoodLaunch.settle(game, ce()));

        GoBack.Plan plan = GoBack.plan(game, ce());
        assertEquals(List.of("config/create-common.toml"), plan.configs().stream().map(c -> c.path()).toList());
        GoBack.apply(game, ce(), plan);
        // edited again after closing: only the title screen's copy is known, so that one comes back
        assertEquals("speed = 1\n", Files.readString(speed), "back to the last copy that's known to work");
        assertEquals("ignored_update = 191\n", Files.readString(map), "the map setting stays");
    }
    @Test
    void anUnchangedJarWhoseVersionReadsDifferentlyStaysAsItIs() throws Exception {
        goodLaunch();
        // The scan read "1.0" when it worked; another reader may see a placeholder or another string: same file
        new PackSnapshot("2026-10-28T10:00", "1.21.1", "21.1.251", Map.of(
                "create", new PackSnapshot.Entry("Create", "6.0.3", "create-6.0.3.jar"),
                "oldie", new PackSnapshot.Entry("Oldie", "1.0.0-build.7", "oldie-1.0.jar"),
                "gone", new PackSnapshot.Entry("Gone Mod", "${version}", "gone-2.0.jar"))).save(ce());
        assertTrue(GoBack.plan(game, ce()).isEmpty(), GoBack.plan(game, ce()).lines().toString());
    }

    @Test
    void aConfigWhereOnlyACommentChangedIsNotPutBack() throws Exception {
        Files.createDirectories(game.resolve("config"));
        Files.writeString(game.resolve("config/oculus.properties"), "#Wed Oct 28 17:24:56 GST 2026\nshaders=off\n");
        goodLaunch();
        // Many mods stamp the date on every start
        Files.writeString(game.resolve("config/oculus.properties"), "#Wed Oct 28 17:55:08 GST 2026\nshaders=off\n");
        assertTrue(GoBack.plan(game, ce()).configs().isEmpty());
        Files.writeString(game.resolve("config/oculus.properties"), "#Wed Oct 28 17:58:00 GST 2026\nshaders=on\n");
        assertEquals(1, GoBack.plan(game, ce()).configs().size());
    }
}
