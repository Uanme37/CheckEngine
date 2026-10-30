package com.guiltypotato.checkengine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guiltypotato.checkengine.core.model.ModInfo;
import com.guiltypotato.checkengine.core.model.ModJar;
import com.guiltypotato.checkengine.core.scan.Changes;
import com.guiltypotato.checkengine.core.scan.Finding;
import com.guiltypotato.checkengine.core.scan.LaunchHistory;
import com.guiltypotato.checkengine.core.scan.LaunchHistory.Launch;
import com.guiltypotato.checkengine.core.scan.ModRows;
import com.guiltypotato.checkengine.core.scan.PackSnapshot;
import com.guiltypotato.checkengine.core.scan.Quiet;
import com.guiltypotato.checkengine.core.scan.TitleButtonState;
import com.guiltypotato.checkengine.core.scan.WarningLog;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Pit Stop: start time history, the usual time, "clearly slower", the title button and the warning log. */
class PitStopTest {
    @TempDir
    Path ce;

    private static Launch run(long seconds) {
        return new Launch("2026-10-28T10:00", seconds * 1000, 0, "", false);
    }

    @Test
    void usualIsTheMedianOfFairLaunchesSkippingTheFirstAndPackUpdates() {
        List<Launch> l = List.of(
                new Launch("t0", 300_000, 0, "", true), // first launch: cold, not fair
                run(100), run(90), run(110),
                new Launch("t4", 240_000, 5, "added Create +4 more", false)); // pack update, not fair
        assertEquals(100_000, LaunchHistory.usual(l));
        assertEquals(-1, LaunchHistory.usual(List.of(run(100), run(90))), "fewer than 3: no usual yet");
        assertEquals(95_000, LaunchHistory.usual(List.of(run(80), run(90), run(100), run(110))));
    }

    @Test
    void clearlySlowerNeedsTwentySecondsAndAQuarterMore() {
        // 4 normal starts are too few to call one slower
        assertNull(LaunchHistory.slower(List.of(run(100), run(100), run(100), run(100), run(200))));
        List<Launch> base = new ArrayList<>(List.of(run(100), run(100), run(100), run(100), run(100)));
        base.add(run(118)); // 18 s more: not clearly slower
        assertNull(LaunchHistory.slower(base));
        base.set(5, run(124)); // 24 s and 24%: not a quarter more
        assertNull(LaunchHistory.slower(base));
        base.set(5, new Launch("t5", 140_000, 1, "added Create", false));
        LaunchHistory.Slower s = LaunchHistory.slower(base);
        assertNotNull(s);
        assertEquals(40_000, s.extraMs());
        assertEquals("Started 40s slower than usual. Changed since then: added Create.", s.text());
        // a big pack (5 min usual): 30 s more is not a quarter
        assertNull(LaunchHistory.slower(List.of(run(300), run(300), run(300), run(300), run(300), run(330))));
    }

    @Test
    void historyKeepsTenLaunchesSaysWhatChangedAndNoticesASlowdownOnce() throws Exception {
        Map<String, PackSnapshot.Entry> mods = new TreeMap<>(Map.of(
                "jade", new PackSnapshot.Entry("Jade", "1.0", "jade.jar")));
        PackSnapshot pack = new PackSnapshot("t", "1.21.1", "21.1.251", mods);
        for (int i = 0; i < 12; i++) LaunchHistory.record(ce, pack, 100_000, "2026-10-28T10:" + (10 + i));
        List<Launch> all = LaunchHistory.load(ce);
        assertEquals(10, all.size());
        assertEquals("2026-10-28T10:21", all.get(9).when());
        assertFalse(all.get(0).first(), "the first launch fell out");

        Map<String, PackSnapshot.Entry> more = new TreeMap<>(mods);
        more.put("create", new PackSnapshot.Entry("Create", "6.0.4", "create.jar"));
        more.put("ponder", new PackSnapshot.Entry("Ponder", "1.0", "ponder.jar"));
        PackSnapshot bigger = new PackSnapshot("t", "1.21.1", "21.1.251", more);
        all = LaunchHistory.record(ce, bigger, 150_000, "2026-10-28T11:00");
        Launch last = all.get(all.size() - 1);
        assertEquals(2, last.changes());
        assertEquals("added Create +1 more", last.changed());
        LaunchHistory.Slower s = LaunchHistory.noticeOnce(ce);
        assertNotNull(s);
        assertEquals("Started 50s slower than usual. Changed since then: added Create +1 more.", s.text());
        assertNull(LaunchHistory.noticeOnce(ce), "once per slowdown");
        // still slow next launch, nothing changed: the same slowdown, no second notice
        LaunchHistory.record(ce, bigger, 150_000, "2026-10-28T11:10");
        assertNotNull(LaunchHistory.slower(LaunchHistory.load(ce)));
        assertNull(LaunchHistory.noticeOnce(ce));
        // back to normal: no notice, and a later slowdown is new again
        LaunchHistory.record(ce, bigger, 100_000, "2026-10-28T11:20");
        assertNull(LaunchHistory.slower(LaunchHistory.load(ce)));
        assertNull(LaunchHistory.noticeOnce(ce));
        LaunchHistory.record(ce, bigger, 160_000, "2026-10-28T11:30");
        assertNotNull(LaunchHistory.noticeOnce(ce));
    }

    @Test
    void startBreakdownNamesTheThreeParts() {
        com.guiltypotato.checkengine.core.scan.StartupTimes.Data d = new com.guiltypotato.checkengine.core.scan
                .StartupTimes.Data(83_500, 27_600, 42_300, 13_600, List.of(), "2026-10-28T18:00");
        assertEquals("About: Mod setup 42s · Textures and models 14s · Rest 28s",
                com.guiltypotato.checkengine.core.scan.StartupTimes.breakdown(d));
    }

    @Test
    void oneToastForFixedAndSlower() {
        assertNull(com.guiltypotato.checkengine.core.scan.StartNotice.of(List.of(), null));
        String[] both = com.guiltypotato.checkengine.core.scan.StartNotice.of(List.of(
                "Zip file in mods folder: ce-test-1.zip", "Zip file in mods folder: ce-test-2.zip"),
                "Started 20s slower than usual.");
        assertEquals("Check Engine", both[0]);
        assertEquals("Fixed: zip file in mods folder (ce-test-1.zip +1)\nStarted 20s slower than usual.", both[1]);
        assertEquals("Fixed: duplicate mod (AppleSkin) +1 more", com.guiltypotato.checkengine.core.scan.StartNotice.of(
                List.of("Duplicate mod: AppleSkin", "Two map mods"), null)[1]);
        assertEquals("Started 41s slower than usual.",
                com.guiltypotato.checkengine.core.scan.StartNotice.of(null, "Started 41s slower than usual.")[1]);
    }

    @Test
    void titleButtonPositionSetting() throws Exception {
        Path game = ce.resolve("pack");
        Files.createDirectories(game.resolve("config"));
        assertEquals("auto", com.guiltypotato.checkengine.core.scan.PackInfo.titleButtonPos(game));
        Files.writeString(game.resolve("config/checkengine-pack.properties"), "title_button_pos=bottom_right\n");
        assertEquals("bottom_right", com.guiltypotato.checkengine.core.scan.PackInfo.titleButtonPos(game));
    }

    @Test
    void buttonStates() {
        TitleButtonState normal = TitleButtonState.of(161_000, false, 0, 0, false, false);
        assertEquals("2:41", normal.caption());
        assertFalse(normal.alert());
        assertEquals("4:06 ↑", TitleButtonState.of(246_000, true, 0, 0, false, false).caption());
        assertTrue(TitleButtonState.of(161_000, false, 1, 0, false, false).alert(), "a warning without Got it");
        TitleButtonState both = TitleButtonState.of(246_000, true, 0, 0, true, false);
        assertTrue(both.slower() && both.alert(), "up arrow and ! together");
        // Only tell me when it breaks: warnings don't count, crashes and game-stopping problems do
        assertFalse(TitleButtonState.of(161_000, false, 2, 0, false, true).alert());
        assertTrue(TitleButtonState.of(161_000, false, 0, 1, false, true).alert());
        assertTrue(TitleButtonState.of(161_000, false, 0, 0, true, true).alert());
        assertEquals("", TitleButtonState.of(-1, false, 0, 0, false, false).caption(), "nothing measured yet");
        assertEquals("1:02:05", TitleButtonState.of(3_725_000, false, 0, 0, false, false).caption());
    }

    private static Finding warn(String title) {
        return new Finding(Finding.Severity.WARNING, "x", title, "", "", List.of());
    }

    @Test
    void warningLogSaysStillThereOrFixedAndGotItNeverFixesOne() throws Exception {
        WarningLog.update(ce, List.of(warn("Zip file in mods folder"), warn("Two map mods")), "2026-10-27T10:00");
        Quiet.gotIt(ce, List.of(warn("Two map mods"))); // hidden, not fixed
        List<WarningLog.Entry> e = WarningLog.update(ce, List.of(warn("Two map mods")), "2026-10-28T10:00");
        assertEquals(2, e.size());
        assertEquals("Two map mods", e.get(0).title());
        assertTrue(e.get(0).stillThere());
        assertEquals("2026-10-27T10:00", e.get(0).firstSeen());
        assertFalse(e.get(1).stillThere());
        assertEquals("2026-10-28T10:00", e.get(1).fixedAt());
        // it comes back: still there again
        e = WarningLog.update(ce, List.of(warn("Two map mods"), warn("Zip file in mods folder")), "2026-10-29T10:00");
        assertTrue(e.stream().allMatch(WarningLog.Entry::stillThere));
    }

    @Test
    void blocksAndItemsCountByModIdAndRowsCarryTheirTags() throws Exception {
        Map<String, Integer> blocks = ModRows.byNamespace(List.of("create:cogwheel", "create:shaft", "minecraft:stone",
                "jade:none"));
        assertEquals(2, blocks.get("create"));
        assertEquals(1, blocks.get("minecraft"));
        Path jar = Files.write(ce.resolve("create-6.0.4.jar"), new byte[2 * 1024 * 1024]);
        List<ModRows> rows = ModRows.of(List.of(new ModJar(jar, ModJar.Kind.NEOFORGE,
                        List.of(new ModInfo("create", "6.0.4", "Create", List.of(), null),
                                new ModInfo("ponder", "1.0", "Ponder", List.of(), null)), List.of(), null, 0)),
                Map.of("create", 1200L), blocks, Map.of("create", 3), new Changes("t", List.of(
                        new Changes.Change(Changes.Type.UPDATED, "create", "Create", "6.0.3", "6.0.4", "x"),
                        new Changes.Change(Changes.Type.ADDED, "ponder", "Ponder", null, "1.0", "x"))));
        assertEquals("1.2 s · 5 blocks + items · 2.0 MB · Updated", rows.get(0).details(true));
        assertEquals("- · 0 blocks + items · <0.1 MB · New", rows.get(1).details(true),
                "the file's size counts once");
        assertEquals("5 blocks + items · 2.0 MB · Updated", rows.get(0).details(false),
                "no times on a loader that can't measure them");
    }

    @Test
    void titleButtonSettingFollowsThePackMakerUntilThePlayerChooses() throws Exception {
        Path game = ce.resolve("game");
        Path folder = game.resolve("checkengine");
        assertTrue(Quiet.titleButton(folder, game));
        Files.createDirectories(game.resolve("config"));
        assertTrue(Quiet.titleButton(folder, game), "the new pack file says show");
        assertTrue(Files.readString(game.resolve("config/checkengine-pack.properties")).contains("title_button_pos=auto"));
        Files.writeString(game.resolve("config/checkengine-pack.properties"), "title_button=false\n");
        assertFalse(Quiet.titleButton(folder, game), "false works like hide");
        Files.writeString(game.resolve("config/checkengine-pack.properties"), "title_button=hide\n");
        assertFalse(Quiet.titleButton(folder, game));
        Quiet.setTitleButton(folder, true);
        assertTrue(Quiet.titleButton(folder, game));
        assertFalse(Quiet.onlyWhenItBreaks(folder), "the other choice is untouched");
    }
}
