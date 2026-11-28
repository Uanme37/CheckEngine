package checkengine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import checkengine.core.crash.CrashDoctor;
import checkengine.core.crash.CrashDoctor.LastCrash;
import checkengine.core.crash.CrashHelp;
import checkengine.core.crash.CrashTranslator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CrashDoctorTest {
    @TempDir
    Path game;

    private static final String CRASH = """
            ---- Minecraft Crash Report ----
            Time: 2026-10-08 12:30:00
            Description: Mod loading failures have occurred; consult the issue messages for more details

            net.neoforged.neoforge.logging.CrashReportExtender$ModLoadingCrashException: Mod loading has failed

            -- Mod loading issue for: create_submarine --
            Details:
            	Mod file: /C:/x/mods/create_submarine-2.2.4.jar
            	Failure message: Mod create_submarine requires aeronautics 1.1.3 or above
            		Currently, aeronautics is not installed

            -- System Details --
            """;

    private Path ce() {
        return game.resolve("checkengine");
    }

    private Path crash(String name, long ageMillis) throws IOException {
        Path dir = Files.createDirectories(game.resolve("crash-reports"));
        Path f = Files.writeString(dir.resolve(name), CRASH);
        Files.setLastModifiedTime(f, FileTime.fromMillis(System.currentTimeMillis() - ageMillis));
        return f;
    }

    @Test
    void explainsTheLastSessionsCrashOnce() throws IOException {
        assertNull(CrashDoctor.checkAndStartSession(game, ce())); // first launch with Check Engine
        crash("crash-2026-10-08_12.30.00-fml.txt", 0);

        LastCrash c = CrashDoctor.checkAndStartSession(game, ce());
        assertNotNull(c);
        assertEquals("Missing mod: aeronautics", c.main().title());
        assertTrue(c.save(ce()).toFile().isFile());
        assertTrue(Files.readString(ce().resolve(CrashDoctor.REPORT_FILE)).contains("your last game crashed"));

        assertNull(CrashDoctor.checkAndStartSession(game, ce()), "already shown");
    }

    /** The Game log button's copy is the file players paste to others. Made-up names and addresses. */
    @Test
    void savedGameLogHasPersonalBitsBlanked() throws IOException {
        assertNull(CrashDoctor.checkAndStartSession(game, ce())); // first launch with Check Engine
        String name = "crash-2026-10-08_12.30.00-client.txt";
        crash(name, 0);
        String token = "eyJhbGciOiJIUzI1NiJ9.FAKEFAKEFAKEFAKEFAKE.FAKEFAKEFAKE";
        String original = String.join("\n",
                "[12:00:01] [main/INFO]: Setting user: Steve99",
                "[12:00:02] [main/INFO]: Launcher --username Steve99 --accessToken " + token,
                "[12:00:03] [main/INFO]: Mod file C:\\Users\\Alex\\curseforge\\Instances\\Demo Machines\\mods\\create-6.0.4.jar",
                "[12:10:00] [Render thread/INFO]: Connecting to play.demo-server.example, 25565",
                "[12:10:00] [Netty Client IO #1/INFO]: mc.demo-realm.example/203.0.113.8:25565",
                "[12:10:00] [Render thread/ERROR]: Couldn't connect, server address: join.demo-hub.example:25570",
                "\t   |-- demo_lib 0.1.5.1",
                "[12:10:01] [Server thread/INFO]: Bob_42[/203.0.113.7:51234] logged in with entity id 12",
                "[12:10:02] [Render thread/INFO]: [CHAT] <Bob_42> hi, Dana_7 here too",
                "[12:29:59] [Render thread/ERROR]: java.lang.IllegalStateException: Create 6.0.4 belt went wrong",
                "[12:30:00] [Render thread/FATAL]: #@!@# Game crashed! Crash report saved to: #@!@# "
                        + "C:\\Users\\Alex\\curseforge\\Instances\\Demo Machines\\crash-reports\\" + name, "");
        Path logs = Files.createDirectories(game.resolve("logs"));
        Files.writeString(logs.resolve("latest.log"), original);
        LastCrash c = CrashDoctor.peek(game, ce());
        assertNotNull(c);

        Path saved = CrashDoctor.saveLog(game, c, ce(), "Dana_7");

        assertEquals(ce().resolve(CrashDoctor.LOG_FILE), saved);
        String text = Files.readString(saved);
        for (String leak : new String[] {"Steve99", "Alex", token, "203.0.113.7", "203.0.113.8", "demo-server",
                "demo-realm", "demo-hub", "Bob_42", "Dana_7"}) {
            assertTrue(!text.contains(leak), leak + " left in:\n" + text);
        }
        assertTrue(text.contains("Connecting to <server>, 25565"), text);
        assertTrue(text.contains("|-- demo_lib 0.1.5.1"), text);
        // what a helper needs is still there
        assertTrue(text.contains("IllegalStateException: Create 6.0.4 belt went wrong"), text);
        assertTrue(text.contains("create-6.0.4.jar"), text);
        assertTrue(text.contains(name), text);
        assertEquals(original, Files.readString(logs.resolve("latest.log")), "the game's own log is not touched");
        // without a name from the game, the names the log writes are still blanked
        String again = Files.readString(CrashDoctor.saveLog(game, c, ce()));
        assertTrue(!again.contains("Steve99") && !again.contains("Bob_42") && !again.contains("Alex"), again);
    }

    /** From a real test run: a mod that throws while loading, with Check Engine's own clues in the same report. */
    @Test
    void modThatCrashedWhileLoadingIsNamedAndOurOwnNotesAreSkipped() {
        String report = """
                ---- Minecraft Crash Report ----
                Description: Mod loading failures have occurred; consult the issue messages for more details

                net.neoforged.neoforge.logging.CrashReportExtender$ModLoadingCrashException: Mod loading has failed

                -- Mod loading issue --
                Details:
                	Mod file: <No mod information provided>
                	Failure message: Check Engine clue: Duplicate mod: AppleSkin
                		Fix: Keep appleskin-neoforge-mc1.21-3.0.9.jar (the newest) and remove the others.
                	Mod version: <No mod information provided>

                -- Mod loading issue for: cecrash --
                Details:
                	Mod file: /C:/x/mods/cecrash-1.0.0.jar
                	Failure message: CE Test Crash Mod (cecrash) has failed to load correctly
                		java.lang.IllegalStateException: CE test: this mod crashes on purpose
                	Mod version: 1.0.0
                	Exception message: java.lang.IllegalStateException: CE test: this mod crashes on purpose
                """;
        var f = checkengine.core.crash.CrashTranslator.translate(report).findings();
        assertEquals(1, f.size(), f.toString());
        assertEquals("CE Test Crash Mod crashed while loading", f.get(0).title());
        assertTrue(f.get(0).detail().endsWith("Its error: IllegalStateException: CE test: this mod crashes on purpose"),
                f.get(0).detail());
        assertEquals(java.util.List.of("cecrash-1.0.0.jar"), f.get(0).files());
    }

    /** NeoForge 26.1 lists loading errors under the exception, without "-- Mod loading issue --" blocks. */
    @Test
    void readsTheNeoForge261LoadingErrorList() {
        String report = """
                ---- Minecraft Crash Report ----
                Description: Bootstrap

                net.neoforged.fml.ModLoadingException: Loading errors encountered:
                	- CE Test Crash Mod (cecrash) has failed to load correctly
                	  java.lang.IllegalStateException: CE test: this mod crashes on purpose
                Loading warnings encountered:
                	- Check Engine clue: Duplicate mod: CE Test Dupe
                	  Fix: Keep cedupe-1.1.0.jar (the newest) and remove the others.

                	at net.neoforged.fml.ModLoader.waitForFuture(ModLoader.java:253) ~[loader-11.0.15.jar:11.0] {}
                """;
        var f = checkengine.core.crash.CrashTranslator.translate(report).findings();
        assertEquals(1, f.size(), f.toString());
        assertEquals("CE Test Crash Mod crashed while loading", f.get(0).title());
    }

    @Test
    void ignoresCrashesFromBeforeCheckEngine() throws IOException {
        crash("crash-2025-01-01_10.00.00-client.txt", 86_400_000L * 30);
        assertNull(CrashDoctor.checkAndStartSession(game, ce()));
        assertNull(CrashDoctor.checkAndStartSession(game, ce()));
    }

    /** A mod fails to load, then architectury trips over it: the report blames architectury, the log knows better. */
    @Test
    void theGameLogNamesTheModThatFailedFirst() throws IOException {
        assertNull(CrashDoctor.checkAndStartSession(game, ce()));
        Path dir = Files.createDirectories(game.resolve("crash-reports"));
        Files.writeString(dir.resolve("crash-2026-10-14_01.27.44-client.txt"), """
                ---- Minecraft Crash Report ----
                Description: Initializing game

                java.lang.IllegalStateException: Mod 'architectury' is not available!
                	at TRANSFORMER/architectury@13.0.11/dev.architectury.platform.hooks.EventBusesHooks.whenAvailable(EventBusesHooks.java:34) ~[architectury-13.0.11-neoforge.jar%23607!/:?] {}
                """);
        // Linux stamps files with a coarser clock than currentTimeMillis: make "after the session started" certain
        Files.setLastModifiedTime(dir.resolve("crash-2026-10-14_01.27.44-client.txt"),
                FileTime.fromMillis(System.currentTimeMillis() + 2000));
        Path logs = Files.createDirectories(game.resolve("logs"));
        String log = """
                [14Oct2026 01:27:30.001] [main/INFO] [LOADING]: Mod list:
                		Architectury 13.0.11 (architectury)
                		CE Test Crash Mod 1.0.0 (cecrash)
                [14Oct2026 01:27:40.182] [modloading-worker-0/ERROR] [net.neoforged.fml.javafmlmod.FMLModContainer/LOADING]: Failed to create mod instance. ModID: cecrash, class ce.crash.CeCrash
                java.lang.reflect.InvocationTargetException: null
                	at TRANSFORMER/cecrash@1.0.0/ce.crash.CeCrash.<init>(CeCrash.java:6) ~[cecrash-1.0.0.jar%23147!/:?]
                Caused by: java.lang.IllegalStateException: CE test: this mod crashes on purpose
                	at TRANSFORMER/cecrash@1.0.0/ce.crash.CeCrash.<init>(CeCrash.java:6) ~[cecrash-1.0.0.jar%23147!/:?]
                [14Oct2026 01:27:46.300] [Render thread/FATAL] [net.minecraft.client.Minecraft/]: Crash report saved to: #@!@# C:\\pack\\crash-reports\\crash-2026-10-14_01.27.44-client.txt
                """;
        try (var out = new java.util.zip.GZIPOutputStream(Files.newOutputStream(logs.resolve("2026-10-14-1.log.gz")))) {
            out.write(log.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        Files.writeString(logs.resolve("latest.log"), "[14Oct2026 01:30:00.000] [main/INFO] [x/]: a new session\n");

        LastCrash c = CrashDoctor.checkAndStartSession(game, ce());
        assertNotNull(c);
        assertEquals("CE Test Crash Mod crashed while loading", c.main().title());
        assertTrue(c.main().detail().contains("IllegalStateException: CE test"), c.main().detail());
        assertTrue(c.main().detail().contains("failed first"), c.main().detail());
        assertEquals(java.util.List.of("cecrash-1.0.0.jar"), c.main().files());
        assertEquals("architectury is installed, but wasn't ready when a mod asked for it",
                c.explained().findings().get(1).title());
    }

    /** One broken mixin makes many mods fail with the same error: that's not each mod's fault, leave the report be. */
    @Test
    void manyModsWithTheSameErrorAreNotBlamed() {
        var report = checkengine.core.crash.CrashTranslator.translate("""
                Description: Initializing game

                java.lang.IllegalStateException: Mod 'architectury' is not available!
                """);
        StringBuilder log = new StringBuilder();
        for (String id : new String[] {"citadel", "xaerominimap", "colorwheel"}) {
            log.append("[18:09:40] [modloading-worker-0/ERROR]: Failed to create mod instance. ModID: ").append(id)
                    .append(", class x.Y\norg.spongepowered.asm.mixin.injection.throwables.InjectionError: LVT in LevelRenderer\n");
        }
        var e = checkengine.core.crash.CrashTranslator.withLog(report, log.toString());
        assertEquals(1, e.findings().size(), e.findings().toString());
    }

    /** A made-up crash in Demo Gadgets: frames, and the report's own "Suspected Mods" line when given. */
    private static String demoCrash(String frameMod, String suspected) {
        return """
                ---- Minecraft Crash Report ----
                Description: Unexpected error

                java.lang.NullPointerException: Cannot invoke "Object.toString()" because "x" is null
                """ + (frameMod == null ? "" : "\tat TRANSFORMER/" + frameMod + "@1.0/com.demo." + frameMod
                + ".Magnet.pull(Magnet.java:7) ~[" + frameMod + "-1.0.jar%23211!/:?] {}\n")
                + "\tat TRANSFORMER/minecraft@1.21.1/net.minecraft.world.entity.Entity.tick(Entity.java:850) ~[client-1.21.1.jar%23236!/:?] {}\n"
                + """


                A detailed walkthrough of the error, its code path and all known details is as follows:
                ---------------------------------------------------------------------------------------
                """ + (suspected == null ? "" : "\tSuspected Mods: " + suspected + "\n");
    }

    /**
     * The screen and Copy for help must say what the CLI says (2026-11-27: "Crash in Quark" was Sure in the CLI
     * and "not sure" in game). Fails as soon as the two disagree on any of the four words.
     */
    @Test
    void theScreenAndTheCliAlwaysAgreeOnHowSure() throws IOException {
        String[][] cases = {
                {"demogadgets", "Demo Gadgets (demogadgets)", "Sure"},
                {"demogadgets", null, "Likely"},
                {"demogadgets", "Steve Tools (stevetools)", "Maybe"},
                {null, null, "Not sure"},
        };
        int n = 0;
        for (String[] c : cases) {
            String text = demoCrash(c[0], c[1]);
            Path g = game.resolve("g" + n++), ce = g.resolve("checkengine");
            assertNull(CrashDoctor.checkAndStartSession(g, ce));
            Path dir = Files.createDirectories(g.resolve("crash-reports"));
            Path f = Files.writeString(dir.resolve("crash-2026-11-27_12.00.0" + n + "-client.txt"), text);
            Files.setLastModifiedTime(f, FileTime.fromMillis(System.currentTimeMillis() + 2000));
            LastCrash shown = CrashDoctor.checkAndStartSession(g, ce);
            assertNotNull(shown, c[2]);
            CrashTranslator.Confidence cli = CrashTranslator.translate(text).confidence();
            assertEquals(c[2], cli.word(), "the made-up crash should be " + c[2]);
            assertEquals(cli, shown.confidence(), c[2]);
            assertEquals(cli == CrashTranslator.Confidence.SURE, shown.sure(), c[2]);
            String help = CrashHelp.message(shown, null, null);
            switch (c[2]) {
                case "Sure" -> {
                    assertEquals("THIS IS IT", shown.heading());
                    assertTrue(help.contains("**Cause:** Crash in "), help);
                }
                case "Likely" -> {
                    assertEquals("PROBABLY THIS", shown.heading());
                    assertTrue(help.contains("**Cause (likely):** "), help);
                    assertTrue(shown.main().fix().startsWith("Probably demogadgets"), shown.main().fix());
                }
                case "Maybe" -> {
                    assertEquals("MIGHT BE THIS", shown.heading());
                    assertTrue(help.contains("**Cause (maybe):** "), help);
                    assertTrue(shown.main().fix().startsWith("Might be demogadgets"), shown.main().fix());
                }
                default -> {
                    assertEquals("NOT SURE WHAT CAUSED THIS", shown.heading());
                    assertTrue(help.contains("**Cause:** not sure"), help);
                }
            }
        }
    }
}
