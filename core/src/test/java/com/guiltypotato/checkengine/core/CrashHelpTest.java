package com.guiltypotato.checkengine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guiltypotato.checkengine.core.crash.CrashDoctor;
import com.guiltypotato.checkengine.core.crash.CrashDoctor.LastCrash;
import com.guiltypotato.checkengine.core.crash.CrashHelp;
import com.guiltypotato.checkengine.core.crash.CrashLines;
import com.guiltypotato.checkengine.core.model.ModInfo;
import com.guiltypotato.checkengine.core.model.ModJar;
import com.guiltypotato.checkengine.core.model.Side;
import com.guiltypotato.checkengine.core.scan.Changes;
import com.guiltypotato.checkengine.core.scan.Report;
import com.guiltypotato.checkengine.core.scan.ScanOptions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Crash Doctor 0.19: key lines, the crash from before Check Engine, Crash Assistant, Copy for help. */
class CrashHelpTest {
    @TempDir
    Path game;

    /** A crash while playing: a mod's code throws, with a cause and the mod list at the end. */
    private static final String RUNTIME = """
            ---- Minecraft Crash Report ----
            // Who set us up the TNT?

            Time: 2026-10-20 18:02:11
            Description: Ticking player

            java.lang.NullPointerException: Cannot invoke "Object.toString()" because "stack" is null
            	at TRANSFORMER/minecraft@1.21.1/net.minecraft.world.entity.player.Player.tick(Player.java:250)
            	at TRANSFORMER/coolbags@2.1.0/com.cool.bags.BagTicker.tick(BagTicker.java:44)
            	at TRANSFORMER/minecraft@1.21.1/net.minecraft.server.level.ServerPlayer.doTick(ServerPlayer.java:500)
            Caused by: java.lang.IllegalStateException: bag slot 9 is empty
            	at TRANSFORMER/coolbags@2.1.0/com.cool.bags.BagSlot.get(BagSlot.java:12)

            -- System Details --
            	Mod List:
            		coolbags-2.1.0.jar                                |Cool Bags                     |coolbags                      |2.1.0               |Manifest: NOSIGNATURE
            		Player name: Steve99 at /C:/Users/Alex/AppData/x 192.168.1.40
            """;

    private Path ce() {
        return game.resolve("checkengine");
    }

    private Path crash(String name, String text, long ageMillis) throws IOException {
        Path dir = Files.createDirectories(game.resolve("crash-reports"));
        Path f = Files.writeString(dir.resolve(name), text);
        Files.setLastModifiedTime(f, FileTime.fromMillis(System.currentTimeMillis() - ageMillis));
        return f;
    }

    private static Report pack(String id, String version) {
        List<ModJar> jars = new ArrayList<>();
        jars.add(new ModJar(Path.of("other-1.0.jar"), ModJar.Kind.NEOFORGE,
                List.of(new ModInfo("other", "1.0", "Other", List.of())), List.of(), null));
        if (id != null) {
            jars.add(new ModJar(Path.of(id + "-" + version + ".jar"), ModJar.Kind.NEOFORGE,
                    List.of(new ModInfo(id, version, id, List.of())), List.of(), null));
        }
        return new Report(Path.of("mods"), new ScanOptions(Side.CLIENT, "1.21.1", "21.1.209", Set.of()), jars,
                List.of());
    }

    @Test
    void keyLinesAreWordForWordAndOnlyTheCulpritIsMarked() {
        List<CrashLines.KeyLine> lines = CrashLines.pick(RUNTIME);
        assertTrue(lines.size() <= CrashLines.MAX);
        assertEquals("Description: Ticking player", lines.get(0).text());
        assertTrue(lines.get(1).text().startsWith("java.lang.NullPointerException"));
        assertTrue(lines.stream().anyMatch(l -> l.text().startsWith("Caused by: java.lang.IllegalStateException")));
        List<CrashLines.KeyLine> marked = lines.stream().filter(CrashLines.KeyLine::culprit).toList();
        assertEquals(1, marked.size(), lines.toString());
        assertTrue(marked.get(0).text().contains("coolbags@2.1.0"), marked.toString());
        assertEquals("coolbags", CrashLines.culprit(RUNTIME));
        assertEquals("2.1.0", CrashLines.versionInReport(RUNTIME, "coolbags"));
    }

    @Test
    void nothingIsMarkedWhenNoModCanBeBlamed() {
        String report = """
                Description: Rendering screen

                java.lang.IllegalStateException: something odd
                	at TRANSFORMER/minecraft@1.21.1/net.minecraft.client.Minecraft.run(Minecraft.java:1)
                """;
        assertTrue(CrashLines.pick(report).stream().noneMatch(CrashLines.KeyLine::culprit));
        assertNull(CrashLines.culprit(report));
    }

    /** A knock-on crash: the mod in the stack trace didn't cause it, so it isn't marked or blamed. */
    @Test
    void knockOnCrashDoesNotPointAtTheBystander() throws IOException {
        crash("crash-2026-10-20_18.02.11-client.txt", """
                ---- Minecraft Crash Report ----
                Description: Rendering overlay

                java.lang.IllegalStateException: Cannot get config value before config is loaded.
                	at TRANSFORMER/liteminer@1.0.3/com.iamkaf.liteminer.LiteminerClient.onPostTick(LiteminerClient.java:58)
                """, 60_000);
        LastCrash c = CrashDoctor.checkAndStartSession(game, ce());
        assertEquals("knock-on-crash", c.main().code());
        assertNull(c.culprit());
        assertTrue(c.keyLines().stream().noneMatch(CrashLines.KeyLine::culprit), c.keyLines().toString());
    }

    @Test
    void firstLaunchExplainsACrashFromBeforeCheckEngineWasInstalled() throws IOException {
        crash("crash-2026-10-20_18.02.11-server.txt", RUNTIME, 2 * 60 * 60 * 1000L);
        LastCrash c = CrashDoctor.checkAndStartSession(game, ce());
        assertNotNull(c);
        assertTrue(c.beforeInstall());
        assertTrue(c.toText().contains("before Check Engine was installed"));
        assertEquals("coolbags", c.culprit());
        assertNull(CrashDoctor.checkAndStartSession(game, ce()), "shown once");
    }

    @Test
    void firstLaunchIgnoresCrashesOlderThanADay() throws IOException {
        crash("crash-2026-10-18_18.02.11-server.txt", RUNTIME, 30 * 60 * 60 * 1000L);
        assertNull(CrashDoctor.checkAndStartSession(game, ce()));
    }

    @Test
    void laterLaunchesAreNotBeforeInstall() throws IOException {
        assertNull(CrashDoctor.checkAndStartSession(game, ce()));
        crash("crash-2026-10-20_18.02.11-server.txt", RUNTIME, 0);
        LastCrash c = CrashDoctor.checkAndStartSession(game, ce());
        assertNotNull(c);
        assertFalse(c.beforeInstall());
    }

    @Test
    void blamedModGoneOrUpdatedSinceTheCrash() throws IOException {
        crash("crash-2026-10-20_18.02.11-server.txt", RUNTIME, 60_000);
        LastCrash c = CrashDoctor.checkAndStartSession(game, ce());
        assertEquals(CrashDoctor.Since.GONE, c.since(pack(null, null)));
        assertEquals(CrashDoctor.Since.CHANGED, c.since(pack("coolbags", "2.1.1")));
        assertEquals(CrashDoctor.Since.SAME, c.since(pack("coolbags", "2.1.0")));
        assertEquals(CrashDoctor.Since.UNKNOWN, c.since(null));
    }

    @Test
    void spotsCrashAssistant() throws IOException {
        Path mods = Files.createDirectories(game.resolve("mods"));
        assertFalse(CrashDoctor.crashAssistant(game));
        Files.writeString(mods.resolve("CrashAssistant-neoforge-1.20.6-1.21.4-1.11.12.jar.disabled"), "");
        assertFalse(CrashDoctor.crashAssistant(game), "switched off doesn't count");
        Files.writeString(mods.resolve("CrashAssistant-neoforge-1.20.6-1.21.4-1.11.12.jar"), "");
        assertTrue(CrashDoctor.crashAssistant(game));
    }

    @Test
    void copyForHelpFitsDiscordAndBlanksPersonalBits() throws IOException {
        crash("crash-2026-10-20_18.02.11-server.txt", RUNTIME, 60_000);
        LastCrash c = CrashDoctor.checkAndStartSession(game, ce());
        List<Changes.Change> many = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            many.add(new Changes.Change(Changes.Type.ADDED, "mod" + i, "Some Really Long Mod Name Number " + i, null,
                    "1.0." + i, "mod" + i + ".jar"));
        }
        c = c.withChanges(new Changes("2026-10-19T10:00", many));
        String msg = CrashHelp.message(c, pack("coolbags", "2.1.0"), "Steve99");
        assertTrue(msg.length() <= CrashHelp.MAX_CHARS, msg.length() + " chars");
        assertTrue(msg.endsWith("Diagnosed by Check Engine"), msg);
        assertTrue(msg.contains("```\nDescription: Ticking player"), msg);
        assertTrue(msg.contains("Minecraft 1.21.1 | NeoForge 21.1.209"), msg);
        assertFalse(msg.contains("Steve99"), msg);
        assertFalse(msg.contains("192.168.1.40"), msg);
        assertFalse(msg.contains("Java <ip>"), msg);
    }

    @Test
    void copyForHelpSaysNotSureInsteadOfGuessing() throws IOException {
        String unsure = """
                ---- Minecraft Crash Report ----
                Description: Rendering screen

                java.lang.IllegalStateException: something odd
                	at TRANSFORMER/minecraft@1.21.1/net.minecraft.client.Minecraft.run(Minecraft.java:1)
                """;
        crash("crash-2026-10-20_18.02.11-client.txt", unsure, 60_000);
        LastCrash c = CrashDoctor.checkAndStartSession(game, ce());
        assertFalse(c.sure());
        String msg = CrashHelp.message(c, null, null);
        assertTrue(msg.contains("**Cause:** not sure"), msg);
        assertFalse(msg.contains("**Fix:**"), msg);
    }
}
