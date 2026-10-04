package com.guiltypotato.packdoctor.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guiltypotato.packdoctor.core.crash.CrashTranslator;
import com.guiltypotato.packdoctor.core.scan.Finding;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Excerpts of real crash reports from Beyond The Crank (1.21.1 NeoForge). */
class CrashTranslatorTest {

    private static List<Finding> translate(String report) {
        return CrashTranslator.translate(report).findings();
    }

    private static final String LOADING_HEAD = """
            ---- Minecraft Crash Report ----
            // Why did you do that?

            Time: 2026-10-01 21:30:20
            Description: Mod loading failures have occurred; consult the issue messages for more details

            net.neoforged.neoforge.logging.CrashReportExtender$ModLoadingCrashException: Mod loading has failed


            A detailed walkthrough of the error, its code path and all known details is as follows:
            ---------------------------------------------------------------------------------------

            """;

    @Test
    void missingDependency() {
        List<Finding> f = translate(LOADING_HEAD + """
                -- Mod loading issue for: create_submarine --
                Details:
                	Mod file: /C:/Users/Gaming/curseforge/minecraft/Instances/TheCreate/mods/create_submarine-2.2.4.jar
                	Failure message: Mod create_submarine requires aeronautics 1.1.3 or above
                		Currently, aeronautics is not installed

                	Mod version: 2.2.4
                	Mod issues URL: <No issues URL found>
                	Exception message: <No associated exception found>

                -- System Details --
                """);
        assertEquals(1, f.size());
        assertEquals(Finding.MISSING_DEPENDENCY, f.get(0).code());
        assertEquals("Missing mod: aeronautics", f.get(0).title());
        assertEquals(List.of("create_submarine-2.2.4.jar"), f.get(0).files());
    }

    @Test
    void versionRangeAndIncompatibleInOneReport() {
        List<Finding> f = translate(LOADING_HEAD + """
                -- Mod loading issue for: petrolpark --
                Details:
                	Mod file: /C:/x/mods/petrolpark-1.21.1-1.5.10.jar
                	Failure message: Mod petrolpark only supports jei 19.53.0.426 or above, and below 19.54.0.0
                		Currently, jei is 19.57.0.450
                	Mod version: 1.5.10

                -- Mod loading issue for: deployer --
                Details:
                	Mod file: /C:/x/mods/deployer-0.1.3.jar
                	Failure message: Mod deployer is incompatible with create_factory_logistics any
                		Currently, create_factory_logistics is 1.6.0
                		The reason is: Create Factory logistics is not supported.
                	Mod version: 0.1.3

                -- System Details --
                """);
        assertEquals(2, f.size());
        assertEquals(Finding.WRONG_VERSION, f.get(0).code());
        assertTrue(f.get(0).detail().contains("but you have 19.57.0.450"), f.get(0).detail());
        assertEquals(Finding.INCOMPATIBLE_MOD, f.get(1).code());
        assertTrue(f.get(1).detail().contains("Create Factory logistics is not supported."));
    }

    @Test
    void forgeJarWithNoModInfo() {
        List<Finding> f = translate(LOADING_HEAD + """
                -- Mod loading issue --
                Details:
                	Mod file: <No mod information provided>
                	Failure message: File mods\\alexsmobs-1.22.9.jar is for Minecraft Forge or an older version of NeoForge, and cannot be loaded
                	Mod version: <No mod information provided>

                -- System Details --
                """);
        assertEquals(Finding.WRONG_LOADER, f.get(0).code());
        assertEquals(List.of("alexsmobs-1.22.9.jar"), f.get(0).files());
    }

    @Test
    void knockOnConfigCrashIsNotBlamedOnTheMod() {
        List<Finding> f = translate("""
                Description: Rendering overlay

                java.lang.IllegalStateException: Cannot get config value before config is loaded.
                	at TRANSFORMER/neoforge@21.1.251/net.neoforged.neoforge.common.ModConfigSpec$ConfigValue.get(ModConfigSpec.java:1) ~[neoforge.jar%23586!/:?]
                	at TRANSFORMER/create_tweaked_controllers@1.2.6/com.getitemfromblock.Foo.bar(Foo.java:1) ~[ctc.jar%23200!/:?]

                A detailed walkthrough of the error, its code path and all known details is as follows:
                	Suspected Mods: Create Tweaked Controllers (create_tweaked_controllers), Minecraft (minecraft), NeoForge (neoforge)
                """);
        assertEquals("knock-on-crash", f.get(0).code());
        assertTrue(f.get(0).detail().contains("Create Tweaked Controllers (create_tweaked_controllers)"));
    }

    @Test
    void missingModAtStartup() {
        List<Finding> f = translate("""
                Description: Initializing game

                java.lang.IllegalStateException: Mod 'architectury' is not available!
                	at TRANSFORMER/architectury@13.0.8/dev.architectury.platform.Platform.getMod(Platform.java:1) ~[a.jar%23100!/:?]

                A detailed walkthrough of the error, its code path and all known details is as follows:
                	Suspected Mods: Minecraft (minecraft), Omega Config Architectury (omegaconfig), Architectury (architectury)
                """);
        assertEquals("Mod didn't load: architectury", f.get(0).title());
        assertTrue(f.get(0).detail().contains("Omega Config Architectury"));
    }

    @Test
    void plainCrashNamesTheModFromTheStack() {
        List<Finding> f = translate("""
                Description: Exception in server tick loop

                java.lang.RuntimeException: java.nio.file.NoSuchFileException: C:\\saves\\New World\\missions\\mission_data.nbt
                	at TRANSFORMER/missions@1.0/com.missions.Data.load(Data.java:1) ~[missions.jar%23300!/:?]
                	at TRANSFORMER/minecraft@1.21.1/net.minecraft.server.MinecraftServer.tick(MinecraftServer.java:1) ~[mc.jar%2310!/:?]

                A detailed walkthrough of the error, its code path and all known details is as follows:
                	Suspected Mods: Missions (missions), Minecraft (minecraft)
                """);
        assertEquals("Crash in Missions (missions)", f.get(0).title());
    }

    @Test
    void dependencyOnAnyVersion() {
        List<Finding> f = translate(LOADING_HEAD + """
                -- Mod loading issue for: continuity --
                Details:
                	Mod file: /C:/x/mods/continuity-3.0.0+1.21.neoforge.jar
                	Failure message: Mod continuity requires connector any
                		Currently, connector is not installed

                	Mod version: 3.0.0+1.21.neoforge

                -- System Details --
                """);
        assertEquals("Missing mod: connector", f.get(0).title());
        assertTrue(f.get(0).detail().contains("(any version)"), f.get(0).detail());
    }

    @Test
    void mixinCrashNamesTheModWhosePatchFailed() {
        // Soulrend (Forge 1.20.1 + Connector): no module tags in frames, "Suspected Mods: NONE".
        List<Finding> f = translate("""
                Description: Initializing game

                org.spongepowered.asm.mixin.transformer.throwables.MixinTransformerError: An unexpected critical error was encountered
                	at net.minecraft.client.main.Main.main(Main.java:182) ~[forge-47.4.4.jar:?] {re:mixin,pl:mixin:APP:threadtweak.mixins.json:client.MainMixin from mod threadtweak}
                Caused by: org.spongepowered.asm.mixin.injection.throwables.InjectionError: LVT in net/minecraft/client/renderer/LevelRenderer::m_109599_ does not match. [epicfight.json:client.MixinLevelRenderer from mod epicfight->@Inject::epicfight$renderLevel(...)V]

                A detailed walkthrough of the error, its code path and all known details is as follows:
                	Suspected Mods: NONE
                """);
        assertEquals("mixin-conflict", f.get(0).code());
        assertTrue(f.get(0).detail().contains("belongs to epicfight."), f.get(0).detail());
        assertTrue(f.get(0).fix().startsWith("Update epicfight."));
    }

    @Test
    void outOfMemory() {
        List<Finding> f = translate("""
                Description: Unexpected error

                java.lang.OutOfMemoryError: Java heap space
                """);
        assertEquals("out-of-memory", f.get(0).code());
    }

    @Test
    void notACrashReport() {
        assertEquals("unknown-crash", translate("hello").get(0).code());
    }
}
