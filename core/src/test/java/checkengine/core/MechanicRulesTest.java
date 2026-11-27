package checkengine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import checkengine.core.crash.CrashTranslator;
import checkengine.core.crash.CrashTranslator.Confidence;
import checkengine.core.crash.CrashTranslator.Explanation;
import checkengine.core.scan.Finding;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The 0.25 Mechanic rules. Every report here is made up (Demo Machines, Steve99), shaped like real ones. */
class MechanicRulesTest {

    private static Explanation explain(String report) {
        return CrashTranslator.translate(report);
    }

    private static final String PLAIN = """
            ---- Minecraft Crash Report ----
            // Oops.

            Time: 2026-11-25 10:00:00
            Description: Unexpected error

            java.lang.NullPointerException: Cannot invoke "Object.toString()" because "x" is null
            \tat TRANSFORMER/demomachines@1.4.0/com.demo.machines.CrusherLogic.tick(CrusherLogic.java:42)
            \tat TRANSFORMER/minecraft@1.21.1/net.minecraft.client.Minecraft.tick(Minecraft.java:1800)


            A detailed walkthrough of the error, its code path and all known details is as follows:
            ---------------------------------------------------------------------------------------
            """;

    // ---- Batch 1: report clean-up, and the confidence word ----

    @Test
    void aReportSquashedOntoOneLineByAWebFormIsReadAgain() {
        String squashed = "### Describe the bug\nIt crashes.\n### Crash report\n" + PLAIN.replace("\n\n", "  ")
                .replace("\n\t", " \t").replace("\n", "  ") + "\n### Additional context\nNone\n";
        Explanation e = explain(squashed);
        assertEquals("Unexpected error", e.description());
        assertEquals("Crash, most likely in demomachines", e.findings().get(0).title());
    }

    @Test
    void aReportWrappedInHtmlIsReadAgain() {
        String html = "<p class=\"chat-body\">" + PLAIN.replace("\"", "&quot;").replace("\n", "<br>\n") + "</p>";
        assertEquals("Crash, most likely in demomachines", explain(html).findings().get(0).title());
    }

    @Test
    void javaNamesInAnglesAreNotTakenForHtml() {
        String text = "<p>" + PLAIN.replace("CrusherLogic.tick(", "CrusherLogic.<init>(") + "</p>";
        assertTrue(explain(text).toText().contains("Crash, most likely in demomachines"));
        assertTrue(CrashTranslator.translate(PLAIN.replace("CrusherLogic.tick(", "CrusherLogic.<init>("))
                .findings().get(0).title().contains("demomachines"));
    }

    @Test
    void tablePrefixWithNonBreakingSpacesIsRemoved() {
        String table = PLAIN.lines().map(l -> "  | " + l).reduce("", (a, b) -> a + b + "\n");
        assertEquals("Crash, most likely in demomachines", explain(table).findings().get(0).title());
    }

    @Test
    void everyAnswerCarriesAConfidenceWord() {
        Explanation likely = explain(PLAIN);
        assertEquals(Confidence.LIKELY, likely.confidence());
        assertFalse(likely.sure());
        assertTrue(likely.toText().contains("How sure: Likely"));

        Explanation none = explain(PLAIN.replace("TRANSFORMER/demomachines@1.4.0/", ""));
        assertEquals(Confidence.NOT_SURE, none.confidence());
        assertTrue(none.toText().contains("How sure: Not sure"));

        Explanation sure = explain(PLAIN.replace("java.lang.NullPointerException: Cannot invoke \"Object.toString()\" "
                + "because \"x\" is null", "java.lang.OutOfMemoryError: Java heap space"));
        assertEquals(Confidence.SURE, sure.confidence());
    }

    // ---- Batch 2: native crashes and ticking crashes ----

    private static String hsErr(String frame) {
        return """
                #
                # A fatal error has been detected by the Java Runtime Environment:
                #
                #  EXCEPTION_ACCESS_VIOLATION (0xc0000005) at pc=0x00007ffb12345678, pid=1234, tid=5678
                #
                # Problematic frame:
                # C  [%s+0x1234]
                #
                """.formatted(frame);
    }

    @Test
    void driverFilesStaySureButTheWindowLayerIsNotSure() {
        Explanation driver = explain(hsErr("atio6axx.dll"));
        assertEquals(Confidence.SURE, driver.confidence());
        assertTrue(driver.findings().get(0).title().startsWith("Graphics driver crash"));

        Explanation window = explain(hsErr("glfw.dll"));
        assertEquals(Confidence.NOT_SURE, window.confidence());
        assertFalse(window.findings().get(0).fix().startsWith("Update your graphics driver"));
    }

    @Test
    void librariesThatComeWithMinecraftAreNotCalledAModsNativeCode() {
        Explanation e = explain(hsErr("jemalloc.dll"));
        assertEquals(Confidence.NOT_SURE, e.confidence());
        assertTrue(e.findings().get(0).title().contains("comes with Minecraft"), e.toText());
    }

    @Test
    void anUnnamedNativeCrashNoLongerLeadsWithTheDriver() {
        Explanation e = explain(hsErr("ntdll.dll"));
        assertEquals(Confidence.NOT_SURE, e.confidence());
        assertFalse(e.findings().get(0).fix().startsWith("Update your graphics driver"));
    }

    private static String ticking(String type, String... frames) {
        StringBuilder sb = new StringBuilder("""
                ---- Minecraft Crash Report ----
                Description: Ticking entity

                java.lang.NullPointerException: Cannot read field "level" because "owner" is null
                """);
        for (String f : frames) sb.append('\t').append(f).append('\n');
        sb.append("""


                A detailed walkthrough of the error, its code path and all known details is as follows:
                ---------------------------------------------------------------------------------------

                -- Entity being ticked --
                Details:
                \tEntity Type: %s (com.demo.Thing)
                \tEntity's Exact location: 10.5, 64.0, -3.5
                """.formatted(type));
        sb.append("""
                -- System Details --
                \tMod List:
                \t\tdemomachines-1.4.0.jar                            |Demo Machines                 |demomachines                  |1.4.0               |Manifest: NOSIGNATURE
                \t\tsteves-gadgets-forge-1.20.1-2.1.jar               |Steve's Gadgets               |stevesgadgets                 |2.1                 |Manifest: NOSIGNATURE
                """);
        return sb.toString();
    }

    @Test
    void tickingCrashInTheOwnersOwnCodeIsSure() {
        Explanation e = explain(ticking("demomachines:crusher_cart",
                "at TRANSFORMER/demomachines@1.4.0/com.demo.machines.CrusherCart.tick(CrusherCart.java:10)",
                "at TRANSFORMER/minecraft@1.21.1/net.minecraft.world.level.Level.guardEntityTick(Level.java:500)"));
        assertEquals(Confidence.SURE, e.confidence());
        assertEquals("demomachines", e.suspects().get(0).id());
    }

    @Test
    void tickingCrashInAnotherModsCodeIsMaybeWithThatModFirst() {
        Explanation e = explain(ticking("demomachines:crusher_cart",
                "at TRANSFORMER/stevesgadgets@2.1/com.steve99.gadgets.Magnet.pull(Magnet.java:7)",
                "at TRANSFORMER/demomachines@1.4.0/com.demo.machines.CrusherCart.tick(CrusherCart.java:10)"));
        assertEquals(Confidence.MAYBE, e.confidence());
        assertEquals(List.of("stevesgadgets", "demomachines"), e.suspects().stream().map(s -> s.id()).toList());
        assertTrue(e.findings().get(0).fix().startsWith("Might be stevesgadgets, or another mod"));
    }

    @Test
    void tickingHeadlineNamesTheModsInTheSuspectListsOrder() {
        // The thing's owner used to sit in the headline first: "Crusher cart (demomachines) keeps crashing the world
        // (maybe stevesgadgets)", while the suspect list put stevesgadgets first.
        Explanation e = explain(ticking("demomachines:crusher_cart",
                "at TRANSFORMER/stevesgadgets@2.1/com.steve99.gadgets.Magnet.pull(Magnet.java:7)",
                "at TRANSFORMER/demomachines@1.4.0/com.demo.machines.CrusherCart.tick(CrusherCart.java:10)"));
        String title = e.findings().get(0).title();
        assertEquals("Crusher cart keeps crashing the world (maybe stevesgadgets, or demomachines that adds it)", title);
        assertTrue(title.indexOf(e.suspects().get(0).id()) < title.indexOf(e.suspects().get(1).id()));
        // A vanilla thing: no "(minecraft)" in the headline, only the stack's mod.
        Explanation v = explain(ticking("minecraft:armor_stand",
                "at TRANSFORMER/stevesgadgets@2.1/com.steve99.gadgets.Magnet.pull(Magnet.java:7)"));
        assertEquals("Armor stand keeps crashing the world (likely stevesgadgets)", v.findings().get(0).title());
    }

    @Test
    void tickingVanillaMobWithAModInTheForgeStyleStackIsLikelyThatMod() {
        Explanation e = explain(ticking("minecraft:armor_stand",
                "at com.steve99.gadgets.Magnet.pull(Magnet.java:7) ~[steves-gadgets-forge-1.20.1-2.1.jar%23211!/:?] {}",
                "at net.minecraft.world.entity.LivingEntity.m_8119_(LivingEntity.java:850) ~[client-1.20.1-20230612.114412-srg.jar%23236!/:?] {}"));
        assertEquals(Confidence.LIKELY, e.confidence());
        assertEquals("stevesgadgets", e.suspects().get(0).id());
        assertFalse(e.findings().get(0).fix().contains("Update minecraft"));
    }

    @Test
    void tickingVanillaMobWithNoModAnywhereIsNotSureAndNeverSaysUpdateMinecraft() {
        Explanation e = explain(ticking("minecraft:armor_stand",
                "at net.minecraft.world.entity.LivingEntity.m_8119_(LivingEntity.java:850) ~[client-1.20.1-20230612.114412-srg.jar%23236!/:?] {}"));
        assertEquals(Confidence.NOT_SURE, e.confidence());
        assertFalse(e.findings().get(0).fix().toLowerCase().contains("update minecraft"));
    }

    // ---- Batch 3: Forge 1.20.1 stack frames and the "Suspected Mod(s)" block ----

    private static String forgeCrash(String suspectBlock, String... frames) {
        StringBuilder sb = new StringBuilder("""
                ---- Minecraft Crash Report ----
                Description: Unexpected error

                java.lang.IllegalStateException: Magnet has no target
                """);
        for (String f : frames) sb.append('\t').append(f).append('\n');
        sb.append("""


                A detailed walkthrough of the error, its code path and all known details is as follows:
                ---------------------------------------------------------------------------------------

                -- Head --
                Thread: Render thread
                """).append(suspectBlock).append("""
                Stacktrace:
                \tat com.steve99.gadgets.Magnet.pull(Magnet.java:7)

                -- System Details --
                \tMod List:
                \t\tdemomachines-1.4.0.jar                            |Demo Machines                 |demomachines                  |1.4.0               |Manifest: NOSIGNATURE
                \t\tsteves-gadgets-forge-1.20.1-2.1.jar               |Steve's Gadgets               |stevesgadgets                 |2.1                 |Manifest: NOSIGNATURE
                """);
        return sb.toString();
    }

    private static final String GADGET_FRAME =
            "at com.steve99.gadgets.Magnet.pull(Magnet.java:7) ~[steves-gadgets-forge-1.20.1-2.1.jar%23211!/:?] {}";
    private static final String GAME_FRAME =
            "at net.minecraft.client.Minecraft.m_91398_(Minecraft.java:1800) ~[client-1.20.1-20230612.114412-srg.jar%23236!/:?] {}";

    @Test
    void forgeJarFramesAndTheSuspectedModAgreeSoItIsSure() {
        Explanation e = explain(forgeCrash("""
                Suspected Mod:\s
                \tSteve's Gadgets (stevesgadgets), Version: 2.1
                \t\tIssue tracker URL: https://example.invalid/issues
                \t\tat TRANSFORMER/stevesgadgets@2.1/com.steve99.gadgets.Magnet.pull(Magnet.java:7)
                """, GADGET_FRAME, GAME_FRAME));
        assertEquals(Confidence.SURE, e.confidence());
        assertEquals("Crash in Steve's Gadgets (stevesgadgets)", e.findings().get(0).title());
    }

    @Test
    void onlyTheStackNamesAModSoItIsLikely() {
        Explanation e = explain(forgeCrash("", GADGET_FRAME, GAME_FRAME));
        assertEquals(Confidence.LIKELY, e.confidence());
        assertEquals("stevesgadgets", e.suspects().get(0).id());
        assertTrue(e.toText().contains("How sure: Likely"));
        assertTrue(e.toText().contains("1. stevesgadgets: its code is on top of the crash"), e.toText());
    }

    @Test
    void stackAndSuspectedModDisagreeSoItIsMaybeWithTheStackFirst() {
        Explanation e = explain(forgeCrash("Suspected Mods: Demo Machines (demomachines)\n", GADGET_FRAME, GAME_FRAME));
        assertEquals(Confidence.MAYBE, e.confidence());
        assertEquals(List.of("stevesgadgets", "demomachines"), e.suspects().stream().map(s -> s.id()).toList());
    }

    @Test
    void noModInStackOrSuspectsStaysNotSure() {
        assertEquals(Confidence.NOT_SURE, explain(forgeCrash("", GAME_FRAME)).confidence());
    }

    // ---- Batch 4: libraries, client-only, installed-but-not-ready, version mismatch ----

    @Test
    void aSharedLibraryIsSkippedForTheModThatCalledIt() {
        Explanation e = explain(forgeCrash("",
                "at software.bernie.geckolib.Anim.play(Anim.java:3) ~[geckolib-forge-1.20.1-4.4.jar%23100!/:?] {}",
                GADGET_FRAME, GAME_FRAME));
        assertEquals("stevesgadgets", e.suspects().get(0).id());
    }

    @Test
    void aSharedLibraryAloneIsOnlyAMaybe() {
        Explanation e = explain(forgeCrash("",
                "at software.bernie.geckolib.Anim.play(Anim.java:3) ~[geckolib-forge-1.20.1-4.4.jar%23100!/:?] {}",
                GAME_FRAME));
        assertEquals(Confidence.MAYBE, e.confidence());
        assertEquals("geckolib", e.suspects().get(0).id());
    }

    @Test
    void invalidDistOnAServerIsAClientOnlyMod() {
        Explanation e = explain(loadingIssue("Steve's Gadgets (stevesgadgets) encountered an error during the "
                + "common_setup event phase java.lang.RuntimeException: Attempted to load class "
                + "net/minecraft/client/gui/screens/Screen for invalid dist DEDICATED_SERVER"));
        assertEquals("client-only-on-server", e.findings().get(0).code());
        assertTrue(e.findings().get(0).title().contains("Steve's Gadgets"), e.toText());
    }

    private static String loadingIssue(String failure) {
        return "---- Minecraft Crash Report ----\nDescription: Mod loading error has occurred\n\njava.lang.Exception: "
                + "Mod Loading has failed\n\n-- MOD stevesgadgets --\nDetails:\n\tFailure message: " + failure
                + "\n\tMod version: 2.1\n";
    }

    @Test
    void aModThatIsInstalledButAskedForTooEarlyIsNotCalledMissing() {
        String report = """
                ---- Minecraft Crash Report ----
                Description: Initializing game

                java.lang.IllegalStateException: Mod 'demolib' is not available!
                \tat TRANSFORMER/demolib@3.0/com.demo.lib.Hooks.whenAvailable(Hooks.java:34)
                \tat TRANSFORMER/stevesgadgets@2.1/com.steve99.gadgets.Setup.early(Setup.java:12)
                \tat TRANSFORMER/minecraft@1.21.1/net.minecraft.client.Minecraft.<init>(Minecraft.java:636)


                A detailed walkthrough of the error, its code path and all known details is as follows:
                ---------------------------------------------------------------------------------------
                """;
        Explanation e = explain(report);
        assertTrue(e.findings().get(0).title().startsWith("demolib is installed, but wasn't ready"),
                e.toText());
        assertEquals(Confidence.LIKELY, e.confidence());
        assertEquals("stevesgadgets", e.suspects().get(0).id());
        assertFalse(e.findings().get(0).fix().contains("Install demolib"));
    }

    @Test
    void aMissingClientClassInAPlainCrashIsALikelyClientOnlyMod() {
        Explanation e = explain(PLAIN.replace("java.lang.NullPointerException: Cannot invoke \"Object.toString()\" "
                + "because \"x\" is null", "java.lang.NoClassDefFoundError: net/minecraft/client/gui/screens/Screen"));
        assertEquals("client-only-on-server", e.findings().get(0).code());
        assertEquals(Confidence.LIKELY, e.confidence());
        assertEquals("demomachines", e.suspects().get(0).id());
    }

    @Test
    void noSuchMethodBetweenTwoModsIsAVersionMismatch() {
        String report = PLAIN.replace("java.lang.NullPointerException: Cannot invoke \"Object.toString()\" because \"x\" "
                + "is null", "java.lang.NoSuchMethodError: 'void com.steve99.gadgets.Magnet.pull(int)'");
        Explanation e = explain(report);
        assertEquals("wrong-version", e.findings().get(0).code());
        assertTrue(e.findings().get(0).title().startsWith("demomachines doesn't match the version of"), e.toText());
        assertEquals("demomachines", e.suspects().get(0).id());
    }

    @Test
    void missingGameCodeMeansMadeForAnotherMinecraftVersion() {
        String report = PLAIN.replace("java.lang.NullPointerException: Cannot invoke \"Object.toString()\" because \"x\" "
                + "is null", "java.lang.NoSuchFieldError: net/minecraft/world/level/Level.f_46443_");
        assertEquals("demomachines is made for a different Minecraft or loader version",
                explain(report).findings().get(0).title());
    }

    // ---- Batch 5: small exact rules ----

    @Test
    void aBrokenServerConfigNamesTheFileAndTheWorldFolder() {
        Explanation e = explain(PLAIN.replace("java.lang.NullPointerException: Cannot invoke \"Object.toString()\" "
                + "because \"x\" is null", "com.electronwill.nightconfig.core.io.ParsingException: Not enough data "
                + "available\nCaused by: java.lang.RuntimeException: Failed loading config file "
                + "demomachines-server.toml of type SERVER for modid demomachines"));
        Finding f = e.findings().get(0);
        assertEquals("Broken settings file: demomachines-server.toml", f.title());
        assertTrue(f.fix().contains("serverconfig/demomachines-server.toml"), f.fix());
        assertEquals(Confidence.SURE, e.confidence());

        Finding common = explain(PLAIN.replace("java.lang.NullPointerException", "java.lang.RuntimeException: Failed "
                + "loading config file demomachines-common.toml of type COMMON for modid demomachines\n"
                + "java.lang.NullPointerException")).findings().get(0);
        assertTrue(common.fix().startsWith("Rename config/demomachines-common.toml"), common.fix());
    }

    @Test
    void anUnknownParticleFromAModThatIsNotInstalledIsAMissingMod() {
        Explanation e = explain(ticking("demomachines:crusher_cart",
                "at TRANSFORMER/demomachines@1.4.0/com.demo.machines.CrusherCart.tick(CrusherCart.java:10)")
                .replace("java.lang.NullPointerException: Cannot read field \"level\" because \"owner\" is null",
                        "com.mojang.brigadier.exceptions.CommandSyntaxException: Unknown particle: demoaddon:spark"));
        assertEquals("Missing mod: demoaddon", e.findings().get(0).title());
        assertEquals("demoaddon", e.suspects().get(0).id());
    }

    @Test
    void missingModelLayersNameTheirMod() {
        Explanation e = explain(PLAIN.replace("java.lang.NullPointerException: Cannot invoke \"Object.toString()\" "
                + "because \"x\" is null", "java.lang.IllegalArgumentException: No model for layer "
                + "stevesgadgets:magnet#main"));
        assertEquals(Confidence.LIKELY, e.confidence());
        assertEquals("stevesgadgets", e.suspects().get(0).id());
    }

    @Test
    void incompatibleWithoutTheCurrentlyPartIsStillRead() {
        Explanation e = explain(loadingIssue("Mod demo\\_machines is incompatible with stevesgadgets 0 or above"));
        assertEquals("demo_machines doesn't work with stevesgadgets", e.findings().get(0).title());
    }

    @Test
    void theLoadingScreenInfoDumpIsNotACrash() {
        String dump = "---- Minecraft Crash Report ----\nDescription: Loading screen debug info\n\n"
                + "This is just a prompt for computer specs to be printed. THIS IS NOT A ERROR\n";
        assertEquals("debug-crash", explain(dump).findings().get(0).code());
        // ...but a real crash pasted under it is read.
        Explanation both = explain(dump + "\n" + PLAIN);
        assertEquals("Unexpected error", both.description());
    }

    @Test
    void waylandAndOldGlibcAreNamed() {
        assertEquals("window-system", explain(PLAIN.replace("java.lang.NullPointerException: Cannot invoke "
                + "\"Object.toString()\" because \"x\" is null", "java.lang.IllegalStateException: GLFW error before "
                + "init: [0x1000C]Wayland: The platform does not provide the window position")).findings().get(0).code());
        assertEquals("os-library", explain(PLAIN.replace("java.lang.NullPointerException: Cannot invoke "
                + "\"Object.toString()\" because \"x\" is null", "java.lang.UnsatisfiedLinkError: /tmp/libdemo.so: "
                + "/lib/libm.so.6: version `GLIBC_2.27' not found")).findings().get(0).code());
    }

    @Test
    void watchdogStackOverflowAndMissingFileSayWhatHappened() {
        Explanation dog = explain(PLAIN.replace("Description: Unexpected error", "Description: Watching Server")
                .replace("java.lang.NullPointerException: Cannot invoke \"Object.toString()\" because \"x\" is null",
                        "java.lang.Error: ServerHangWatchdog detected that a single server tick took 60.00 seconds"));
        assertTrue(dog.findings().get(0).title().startsWith("The server froze, most likely in demomachines"),
                dog.toText());
        assertTrue(dog.findings().get(0).fix().contains("max-tick-time=-1"));

        Explanation loop = explain(PLAIN.replace("java.lang.NullPointerException: Cannot invoke \"Object.toString()\" "
                + "because \"x\" is null", "java.lang.StackOverflowError: null"));
        assertEquals("Endless loop in demomachines (stack overflow)", loop.findings().get(0).title());

        Explanation file = explain(PLAIN.replace("java.lang.NullPointerException: Cannot invoke \"Object.toString()\" "
                + "because \"x\" is null", "java.nio.file.NoSuchFileException: C:/Users/Alex/demo/config/crusher.json"));
        assertEquals("demomachines couldn't find a file: crusher.json", file.findings().get(0).title());
    }

    // ---- 2026-11-27: mixin owner first, walk past render mods and into add-ons ----

    private static String crashWith(String... frames) {
        StringBuilder sb = new StringBuilder("""
                ---- Minecraft Crash Report ----
                Description: Unexpected error

                java.lang.ClassCastException: class com.demo.machines.CrusherCart cannot be cast to class net.minecraft.world.entity.player.Player
                """);
        for (String f : frames) sb.append('\t').append(f).append('\n');
        sb.append("""


                A detailed walkthrough of the error, its code path and all known details is as follows:
                ---------------------------------------------------------------------------------------
                """);
        return sb.toString();
    }

    @Test
    void aJarNameWithLoaderAndVersionWordsGivesTheBareModName() {
        // No mod list in the report: the name comes from the jar. "DemoMachines_FORGE_v1.4.0_mc1.20.1.jar" used to
        // become "demomachines_forge_v".
        Explanation e = explain(crashWith(
                "at com.demo.machines.CrusherCart.tick(CrusherCart.java:20) ~[DemoMachines_FORGE_v1.4.0_mc1.20.1.jar%23211!/:?] {}",
                "at net.minecraft.world.entity.Entity.m_8119_(Entity.java:850) ~[client-1.20.1-20230612.114412-srg.jar%23236!/:?] {}"));
        assertEquals("demomachines", e.suspects().get(0).id());
        Explanation f = explain(crashWith(
                "at com.steve99.gadgets.Magnet.pull(Magnet.java:7) ~[steves-gadgets-forge-1.20.1-2.1.jar%23211!/:?] {}"));
        assertEquals("steves-gadgets", f.suspects().get(0).id());
    }

    @Test
    void digitsAndUnderscoresInsideAModNameSurviveTheJarName() {
        // Only versions, loader words and non-Latin labels go: "demo2.jar" used to become "demo",
        // "demo_3d_trees" became "demo".
        String[][] cases = {
                {"demo2.jar", "demo2"},
                {"demo2-forge-1.20.1-4.0.1.jar", "demo2"},
                {"steves_gadgets-1.20.1-0.8.9.jar", "steves_gadgets"},
                {"4dmachines-neoforge-2.1.jar", "4dmachines"},
                {"demo_3d_trees-1.0.jar", "demo_3d_trees"},
                {"demogadgets-1.20.1-0.8.9.jar", "demogadgets"},
                {"演示-demogadgets-0.8.9.jar", "demogadgets"},
                {"demomod1.2.jar", "demomod"},
        };
        for (String[] c : cases) {
            Explanation e = explain(crashWith(
                    "at com.demo.gadgets.Magnet.pull(Magnet.java:7) ~[" + c[0] + "%23211!/:?] {}"));
            assertEquals(c[1], e.suspects().get(0).id(), c[0]);
        }
    }

    @Test
    void aLauncherRenamedGameJarIsNeverAMod() {
        // Some launchers rename the game jar after the modpack ("Steve99 Modpack HD.jar"). The game's own code in it
        // is not a mod.
        Explanation e = explain(crashWith(
                "at net.minecraft.client.main.Main.main(Main.java:218) ~[Steve99-Modpack-HD.jar:?] {}",
                "at cpw.mods.modlauncher.Launcher.run(Launcher.java:108) ~[modlauncher-10.0.9.jar:?] {}"));
        assertTrue(e.suspects().isEmpty(), "named " + e.suspects());
        assertEquals(Confidence.NOT_SURE, e.confidence());
    }

    @Test
    void aModsMixinWhereItCrashedGoesFirstButNeverSure() {
        Explanation e = explain(crashWith(
                "at TRANSFORMER/minecraft@1.21.1/net.minecraft.client.model.PlayerModel.handler$abc000$stevesgadgets$setupAnim(PlayerModel.java:10) ~[client-1.21.1-srg.jar%23300!/:?] {re:mixin}",
                "at TRANSFORMER/demomachines@1.4.0/com.demo.machines.CrusherCartModel.setupAnim(CrusherCartModel.java:20)"));
        assertEquals("stevesgadgets", e.suspects().get(0).id());
        assertEquals("demomachines", e.suspects().get(1).id());
        assertEquals(Confidence.LIKELY, e.confidence());
        assertTrue(e.findings().get(0).title().contains("stevesgadgets"));
    }

    @Test
    void aModsOwnCodeOnTopIsNotBlamedOnAMixinFurtherDown() {
        Explanation e = explain(crashWith(
                "at TRANSFORMER/demomachines@1.4.0/com.demo.machines.CrusherCart.target(CrusherCart.java:20)",
                "at TRANSFORMER/minecraft@1.21.1/net.minecraft.world.entity.ai.goal.TargetGoal.modifyReturnValue$zeo000$stevesgadgets$range(TargetGoal.java:5)"));
        assertEquals("demomachines", e.suspects().get(0).id());
    }

    @Test
    void anAddOnBelowItsHostModIsNamedFirst() {
        Explanation e = explain(crashWith(
                "at TRANSFORMER/demomachines@1.4.0/com.demo.machines.api.ModelLoader.load(ModelLoader.java:20)",
                "at TRANSFORMER/demomachines_addon@1.0/com.demo.addon.ExtraCarts.register(ExtraCarts.java:9)"));
        assertEquals(List.of("demomachines_addon", "demomachines"), e.suspects().stream().map(s -> s.id()).toList());
        assertEquals(Confidence.LIKELY, e.confidence());
    }

    @Test
    void aRenderModOnTopPointsAtTheModBelowIt() {
        Explanation e = explain(crashWith(
                "at TRANSFORMER/sodium@0.6.0/net.caffeinemc.mods.sodium.client.render.Thing.draw(Thing.java:20)",
                "at TRANSFORMER/demomachines@1.4.0/com.demo.machines.CrusherRenderer.render(CrusherRenderer.java:9)"));
        assertEquals("demomachines", e.suspects().get(0).id());
        assertEquals(Confidence.LIKELY, e.confidence());
    }
}
