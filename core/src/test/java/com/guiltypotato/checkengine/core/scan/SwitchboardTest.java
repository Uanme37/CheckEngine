package com.guiltypotato.checkengine.core.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SwitchboardTest {
    @TempDir
    Path game;
    Path ce;

    @BeforeEach
    void folders() throws IOException {
        Files.createDirectories(game.resolve("config"));
        Files.createDirectories(game.resolve("mods"));
        ce = Files.createDirectories(game.resolve("checkengine"));
    }

    private void file(String text) throws IOException {
        Files.writeString(game.resolve("config").resolve(PackInfo.FILE), text);
    }

    private static Finding warn(String title) {
        return new Finding(Finding.Severity.WARNING, "test", title, "detail", "fix", List.of());
    }

    @Test
    void everythingIsOnWithoutAFileOrKey() throws IOException {
        for (Modules.Part p : Modules.Part.values()) assertTrue(Modules.load(game).on(p), p.key());
        assertTrue(Modules.load(null).on(Modules.Part.MECHANIC));
        file("pack_name=Demo Machines\n");
        for (Modules.Part p : Modules.Part.values()) assertTrue(Modules.load(game).on(p), p.key());
        assertTrue(Modules.load(game).stepAside());
        assertFalse(Modules.load(game).modpackMode());
    }

    @Test
    void onlyClearWordsSwitchOff() throws IOException {
        for (String off : List.of("false", "FALSE ", "off", "no", "0")) {
            file("mechanic=" + off + "\n");
            assertFalse(Modules.load(game).on(Modules.Part.MECHANIC), off);
        }
        for (String on : List.of("true", "", "maybe", "flase")) {
            file("mechanic=" + on + "\n");
            assertTrue(Modules.load(game).on(Modules.Part.MECHANIC), on);
        }
    }

    @Test
    void aBrokenFileLeavesEverythingOn() throws IOException {
        Files.write(game.resolve("config").resolve(PackInfo.FILE), new byte[] {'m', '=', '\\', 'u', '1'});
        assertTrue(Modules.load(game).on(Modules.Part.MECHANIC));
    }

    @Test
    void typosAreReportedNotIgnored() throws IOException {
        file("mechanics=false\npit_crew=false\n");
        Modules m = Modules.load(game);
        assertEquals(List.of("mechanics"), m.unknownKeys());
        String block = m.reportBlock(List.of("another crash helper mod (crash lines)"));
        assertTrue(block.contains("pit_crew: OFF"), block);
        assertTrue(block.contains("mechanic: on"), block);
        assertTrue(block.contains("mechanics"), block);
        assertTrue(block.contains("stepping aside for another crash helper mod"), block);
    }

    @Test
    void theExampleListsEveryKeyAndTheRealFileIsNeverWritten() throws IOException {
        PackInfo.load(game);
        Modules.load(game);
        assertFalse(Files.exists(game.resolve("config").resolve(PackInfo.FILE)), "blocks copy-if-missing default mods");
        PackInfo.writeExample(game);
        String example = Files.readString(game.resolve("config").resolve(PackInfo.EXAMPLE_FILE));
        for (Modules.Part p : Modules.Part.values()) assertTrue(example.contains(p.key() + "=true"), p.key());
        for (String key : Modules.OTHER_KEYS) assertTrue(example.contains("\n" + key + "="), key);
        file("mechanic=false\n");
        PackInfo.writeExample(game);
        assertEquals("mechanic=false\n", Files.readString(game.resolve("config").resolve(PackInfo.FILE)));
    }

    @Test
    void theExampleKeysAreAllUnderstood() throws IOException {
        file(PackInfo.EXAMPLE);
        Modules m = Modules.load(game);
        assertEquals(List.of(), m.unknownKeys());
        for (Modules.Part p : Modules.Part.values()) assertTrue(m.on(p), p.key());
        assertFalse(PackInfo.load(game).quiet());
    }

    @Test
    void modpackModeIsAPresetAQuietKeyBeats() throws IOException {
        file("mode=modpack\n");
        PackInfo p = PackInfo.load(game);
        assertTrue(p.quiet());
        assertTrue(p.hidesScreen("update-notes"));
        assertTrue(p.visible(List.of(warn("a warning"))).isEmpty());
        assertEquals(1, p.visible(List.of(new Finding(Finding.Severity.ERROR, "x", "stops the game", "d", "f", List.of()))).size());
        for (Modules.Part part : Modules.Part.values()) assertTrue(Modules.load(game).on(part), "mode turns no part off");
        file("mode=modpack\nquiet=false\n");
        assertFalse(PackInfo.load(game).quiet());
        file("");
        assertFalse(PackInfo.load(game).quiet(), "the file existing proves nothing");
    }

    @Test
    void quietReadsTheSameWordsAsTheSwitches() throws IOException {
        for (String on : List.of("true", "yes", "on", "1")) {
            file("quiet=" + on + "\n");
            assertTrue(PackInfo.load(game).quiet(), on);
        }
        file("quiet=no\n");
        assertFalse(PackInfo.load(game).quiet());
    }

    @Test
    void stepsAsideByModIdOrFileNameUnlessToldNot() throws IOException {
        assertFalse(StepAside.quiet(StepAside.Feature.COPY_FOR_HELP, game, null));
        assertEquals(List.of(), StepAside.active(game, null));
        Files.writeString(game.resolve("mods").resolve("CrashAssistant-neoforge-1.11.12.jar"), "x");
        assertTrue(StepAside.quiet(StepAside.Feature.COPY_FOR_HELP, game, null));
        assertTrue(StepAside.quiet(StepAside.Feature.CRASH_LINES, game, null));
        assertEquals(1, StepAside.active(game, null).size());
        assertTrue(StepAside.active(game, null).get(0).startsWith("another crash helper mod"));
        assertFalse(PackInfo.EXAMPLE.contains("Assistant"), "no other mod's name in text makers see");
        file("step_aside=false\n");
        assertFalse(StepAside.quiet(StepAside.Feature.CRASH_LINES, game, null));
        assertEquals(List.of(), StepAside.active(game, null));
    }

    @Test
    void aSwitchedOffJarDoesntCount() throws IOException {
        Files.writeString(game.resolve("mods").resolve("CrashAssistant-1.jar.disabled"), "x");
        assertFalse(StepAside.quiet(StepAside.Feature.CRASH_LINES, game, null));
    }

    @Test
    void wordingChangesOnlyTheRightWords() {
        assertEquals("This modpack has two mods", Wording.now("This pack has two mods"));
        assertEquals("The modpack's author", Wording.now("The pack's author"));
        assertEquals("MODPACK WON'T START", Wording.now("PACK WON'T START"));
        assertEquals("Mechanic and Misfire", Wording.now("Crash Doctor and Strip Mine"));
        assertEquals("MISFIRE FOUND IT", Wording.now("STRIP MINE FOUND IT"));
        for (String same : List.of("modpack", "datapack", "pack_name=x", "checkengine-pack.properties", "Check Pack.bat",
                "resource pack", "Resource Packs", "shader pack", "pack.png", "backpack", "pack-changed")) {
            assertEquals(same, Wording.now(same));
        }
        assertEquals("already new", Wording.now(Wording.now("already new")));
        assertNull(Wording.now(null));
    }

    /** A folder saved by 0.23, then the same warnings in today's words. */
    @Test
    void updateDayGivesNoFalseFixedAndKeepsWhatWasDismissed() throws IOException {
        Finding old = warn("This pack has two minimap mods");
        Finding renamed = warn("This modpack has two minimap mods");
        Files.writeString(ce.resolve(FixedProblems.FILE), old.title() + "\n");
        assertEquals(List.of(), FixedProblems.update(ce, List.of(renamed)));

        Files.writeString(ce.resolve(Quiet.SEEN), old.title() + "\n");
        assertEquals(List.of(), Quiet.toShow(ce, List.of(renamed)));

        Files.writeString(ce.resolve(WarningLog.FILE), "0.title=" + old.title() + "\n0.severity=WARNING\n0.first=2026-10-01T10\\:00\n");
        List<WarningLog.Entry> log = WarningLog.update(ce, List.of(renamed), "2026-11-01T10:00");
        assertEquals(1, log.size());
        assertEquals(renamed.title(), log.get(0).title());
        assertEquals("2026-10-01T10:00", log.get(0).firstSeen());
        assertTrue(log.get(0).stillThere());

        file("hide_warnings=This pack has two\n");
        assertTrue(PackInfo.load(game).hides(renamed), "a maker's old words still match");
    }

    @Test
    void aReallyFixedWarningStillSaysSo() throws IOException {
        Files.writeString(ce.resolve(FixedProblems.FILE), "This pack has two minimap mods\n");
        assertEquals(List.of("This modpack has two minimap mods"), FixedProblems.update(ce, List.of()));
    }

    @Test
    void ourSettingsAreNotPartOfTheConfigCheckpoint() throws IOException {
        file("mechanic=false\n");
        PackInfo.writeExample(game);
        Files.writeString(game.resolve("config").resolve("create.toml"), "a=1\n");
        assertEquals(java.util.Set.of("config/create.toml"), ConfigCheckpoint.files(game).keySet());
    }

    @Test
    void misfireHelpPointsToCopyForHelpWhenSwitchedOff() {
        String normal = com.guiltypotato.checkengine.core.ui.ScreenHelp.explain("Misfire", null, null).get(0);
        assertTrue(normal.contains("Half of the mods"), normal);
        com.guiltypotato.checkengine.core.ui.ScreenHelp.misfire(false);
        try {
            String off = com.guiltypotato.checkengine.core.ui.ScreenHelp.explain("Misfire", null, null).get(0);
            assertTrue(off.contains("Copy for help"), off);
            assertFalse(off.contains("Half of the mods"), off);
            assertTrue(com.guiltypotato.checkengine.core.ui.ScreenHelp.explain("Go back", null, null).get(0).contains("last time"));
        } finally {
            com.guiltypotato.checkengine.core.ui.ScreenHelp.misfire(true);
        }
    }

    @Test
    void theFixTextPointsToCopyForHelpWhenSwitchedOff() {
        String fix = "Update your driver. " + com.guiltypotato.checkengine.core.crash.CrashTranslator.STRIP_MINE_HINT;
        String off = com.guiltypotato.checkengine.core.crash.CrashTranslator.withoutMisfire(fix);
        assertTrue(off.contains("Copy for help"), off);
        assertFalse(off.contains("Misfire"), off);
    }

    /** Found in a real modpack: Go back offered to bring the settings file back after updating from 0.23. */
    @Test
    void anOlderCheckpointWithOurSettingsShowsNoChange() throws IOException {
        Path store = Files.createDirectories(ce.resolve(ConfigCheckpoint.FOLDER).resolve("files/config"));
        Files.writeString(store.resolve(PackInfo.FILE), "quiet=false\n");
        Files.writeString(store.resolve("create.toml"), "a=1\n");
        Files.writeString(game.resolve("config").resolve("create.toml"), "a=1\n");
        Files.writeString(ce.resolve(ConfigCheckpoint.FOLDER).resolve("index.properties"),
                "config/" + PackInfo.FILE + "=1\n");
        ConfigCheckpoint.save(game, ce);
        Files.writeString(ce.resolve(ConfigCheckpoint.FOLDER).resolve("index.properties"),
                Files.readString(ce.resolve(ConfigCheckpoint.FOLDER).resolve("index.properties"))
                        + "config/" + PackInfo.FILE + "=1\n");
        assertEquals(List.of(), ConfigCheckpoint.changes(game, ce));
    }

    @Test
    void memoryFlagsAreRead() {
        assertTrue(RamAdvisor.explicitGcWorks(List.of("-Xmx8G", "-XX:+UseG1GC")));
        assertFalse(RamAdvisor.explicitGcWorks(List.of("-XX:+DisableExplicitGC")));
        assertFalse(RamAdvisor.explicitGcWorks(List.of("-XX:+ExplicitGCInvokesConcurrent")));
        assertTrue(RamAdvisor.explicitGcWorks(List.of("-XX:-DisableExplicitGC")));
        assertTrue(RamAdvisor.usesZgc(List.of("-XX:+UseZGC")));
        assertFalse(RamAdvisor.usesZgc(List.of("-XX:+UseG1GC")));
    }

    @Test
    void aReadingWithoutARealCleanUpIsNotUsedForAWarning() throws IOException {
        Files.writeString(ce.resolve(RamAdvisor.MEASURED_FILE), "used_after_load_mb=9000\nmax_mb=8000\nexplicit_gc=false\n");
        assertEquals(0, RamAdvisor.facts(game, game.resolve("mods")).measuredMb());
        Files.writeString(ce.resolve(RamAdvisor.MEASURED_FILE), "used_after_load_mb=3000\nmax_mb=8000\n");
        assertEquals(3000, RamAdvisor.facts(game, game.resolve("mods")).measuredMb());
    }

    @Test
    void theClockAddsUpAndSeparatesWaitingFromBackground() throws Exception {
        CostClock.reset();
        try (CostClock c = CostClock.holding("held")) {
            Thread.sleep(20);
        }
        try (CostClock c = CostClock.background("scan")) {
            Thread.sleep(5);
        }
        String report = CostClock.report();
        assertTrue(report.indexOf("held") < report.indexOf("In the background"), report);
        assertTrue(report.indexOf("scan") > report.indexOf("In the background"), report);
        CostClock.save(ce);
        assertEquals(CostClock.summary(), CostClock.lastSummary(ce));
        assertNull(CostClock.lastSummary(game));
    }
}
