package com.guiltypotato.checkengine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guiltypotato.checkengine.core.crash.CrashTranslator;
import com.guiltypotato.checkengine.core.scan.Finding;
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

    /** A crash that names no mod points the player at Strip Mine. */
    @Test
    void aCrashWithNoModNamedSuggestsStripMine() {
        String report = "Description: Rendering overlay\n\njava.lang.NullPointerException: boom\n"
                + "\tat net.minecraft.client.Minecraft.run(Minecraft.java:1)\n";
        var f = com.guiltypotato.checkengine.core.crash.CrashTranslator.translate(report).findings().get(0);
        org.junit.jupiter.api.Assertions.assertTrue(f.fix().contains("Strip Mine"), f.fix());
    }

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
    void forgeLogWithMissingAndWrongVersionMods() {
        // Forge 1.20.1 latest.log format (no crash report is written for these).
        CrashTranslator.Explanation e = CrashTranslator.translateLog("""
                [12:00:01] [main/ERROR] [net.minecraftforge.fml.loading.ModSorter/LOADING]: Missing or unsupported mandatory dependencies:
                	Mod ID: 'architectury', Requested by: 'appleskin', Expected range: '[9.1.12,)', Actual version: '[MISSING]'
                	Mod ID: 'architectury', Requested by: 'jade', Expected range: '[9.1.12,)', Actual version: '[MISSING]'
                	Mod ID: 'geckolib', Requested by: 'mowziesmobs', Expected range: '[4.2,)', Actual version: '4.1.0'
                """);
        List<String> titles = e.findings().stream().map(Finding::title).toList();
        assertEquals(List.of("Missing mod: architectury (2 mods need it)", "Wrong version of geckolib for mowziesmobs"),
                titles);
        assertEquals(null, CrashTranslator.translateLog("[12:00] all good"));
    }

    @Test
    void forgeCrashReportModBlock() {
        List<Finding> f = translate("""
                Description: Mod loading error has occurred

                java.lang.Exception: Mod Loading has failed

                A detailed walkthrough of the error, its code path and all known details is as follows:
                -- MOD mowziesmobs --
                Details:
                	Mod File: /C:/x/mods/mowziesmobs-1.6.4.jar
                	Failure message: Mod mowziesmobs requires geckolib 4.2 or above
                		Currently, geckolib is not installed
                	Mod Version: 1.6.4
                	Mod Issue URL: https://example.org
                	Exception message: <No associated exception found>

                -- System Details --
                """);
        assertEquals("Missing mod: geckolib", f.get(0).title());
        assertEquals(List.of("mowziesmobs-1.6.4.jar"), f.get(0).files());
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
    void sameModRegisteringTwiceSaysInstalledTwiceOrSetupRanTwice() {
        // Beyond the Crank, 2026-10-08 (Check Engine 0.9.1 made Create's setup run twice).
        List<Finding> f = translate("""
                Description: Initializing game

                net.neoforged.fml.ModLoadingException: Loading errors encountered:
                	- Create (create) encountered an error while dispatching the net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent event
                	  java.lang.IllegalStateException: Duplicate client extensions registration for Block{create:belt} (old: com.simibubi.create.content.kinetics.belt.BeltBlock$RenderProperties@3a1a08af, new: com.simibubi.create.content.kinetics.belt.BeltBlock$RenderProperties@578f4687)

                """);
        assertEquals("duplicate-registration", f.get(0).code());
        assertEquals("Registered twice: create:belt", f.get(0).title());
        assertTrue(f.get(0).detail().startsWith("Create tried to add create:belt a second time."), f.get(0).detail());
        assertTrue(f.get(0).fix().contains("two copies of Create"), f.get(0).fix());
    }

    @Test
    void addonRegisteringAnotherModsNameIsBlamed() {
        List<Finding> f = translate("""
                Description: Initializing game

                net.neoforged.fml.ModLoadingException: Loading errors encountered:
                	- Old Belts Addon (oldbelts) has failed to load correctly
                	  java.lang.IllegalStateException: Adding duplicate key 'ResourceKey[minecraft:block / create:belt]' to registry

                """);
        assertEquals("Registered twice: create:belt", f.get(0).title());
        assertTrue(f.get(0).detail().startsWith("Old Belts Addon tried to add create:belt, but that name belongs to create"),
                f.get(0).detail());
        assertTrue(f.get(0).fix().startsWith("Update Old Belts Addon"), f.get(0).fix());
    }

    @Test
    void mixinCrashNamesBothModsWhenTheOtherIsKnown() {
        List<Finding> f = translate("""
                Description: Initializing game

                org.spongepowered.asm.mixin.transformer.throwables.MixinTransformerError: An unexpected critical error was encountered
                	at net.minecraft.client.renderer.LevelRenderer.renderLevel(LevelRenderer.java:1000) ~[client.jar:?] {re:mixin,pl:mixin:APP:sodium-common.mixins.json:core.LevelRendererMixin from mod sodium,pl:mixin:APP:betterfpsdist.mixins.json:LevelRendererMixin from mod betterfpsdist}
                Caused by: org.spongepowered.asm.mixin.injection.throwables.InvalidInjectionException: @At("INVOKE") cannot inject into net/minecraft/client/renderer/LevelRenderer::renderLevel [betterfpsdist.mixins.json:LevelRendererMixin from mod betterfpsdist]

                A detailed walkthrough of the error, its code path and all known details is as follows:
                	Suspected Mods: Sodium (sodium), BetterFpsDist (betterfpsdist)
                """);
        assertEquals("mixin-conflict", f.get(0).code());
        assertEquals("Two mods changed the same game code: BetterFpsDist (betterfpsdist) and Sodium (sodium)",
                f.get(0).title());
        assertTrue(f.get(0).fix().startsWith("Update both"), f.get(0).fix());
    }

    @Test
    void listBrokenByTwoModsAtOnceIsARandomTimingCrash() {
        // Craft to Exile 2 (Forge 1.20.1), 2026-10-11: the next launch loaded fine.
        List<Finding> f = translate("""
                Description: Mod loading error has occurred

                java.lang.Exception: Mod Loading has failed

                -- MOD chillout --
                Details:
                	Mod File: /C:/Users/Gaming/curseforge/minecraft/Instances/Craft to Exile 2 (VR Support)/mods/SitChill 1.0.3.jar
                	Failure message: ChillOut (chillout) encountered an error during the sided_setup event phase
                		java.lang.ArrayIndexOutOfBoundsException: Index 1 out of bounds for length 0
                	Mod Version: 1.0.0
                	Exception message: java.lang.ArrayIndexOutOfBoundsException: Index 1 out of bounds for length 0
                Stacktrace:
                	at java.util.ArrayList.add(Unknown Source) ~[?:?] {re:mixin}
                	at java.util.ArrayList.add(Unknown Source) ~[?:?] {re:mixin}
                	at dev.kosmx.playerAnim.minecraftApi.PlayerAnimationFactory$FactoryHolder.registerFactory(PlayerAnimationFactory.java:38) ~[player-animation-lib-forge-1.0.2-rc1+1.20.jar%23838!/:?] {re:mixin,re:classloading}
                	at net.mcreator.chillout.procedures.ANIMATIONSProcedure.onClientSetup(ANIMATIONSProcedure.java:21) ~[SitChill%201.0.3.jar%23875!/:?] {re:classloading}
                	at java.util.concurrent.ForkJoinWorkerThread.run(Unknown Source) ~[?:?] {re:mixin}
                """);
        assertEquals(Finding.TIMING_CRASH, f.get(0).code());
        assertEquals("Random timing crash while loading (ChillOut)", f.get(0).title());
        assertTrue(f.get(0).detail().contains("player-animation-lib-forge-1.0.2-rc1+1.20.jar"), f.get(0).detail());
        assertTrue(f.get(0).fix().startsWith("Launch again."), f.get(0).fix());
    }

    @Test
    void indexErrorOnTheMainThreadIsNotATimingCrash() {
        List<Finding> f = translate("""
                Description: Ticking entity

                java.lang.ArrayIndexOutOfBoundsException: Index 5 out of bounds for length 5
                	at java.util.ArrayList.add(Unknown Source) ~[?:?]
                	at com.example.Foo.tick(Foo.java:1) ~[foo-1.0.jar%2312!/:?]
                	at net.minecraft.server.MinecraftServer.run(MinecraftServer.java:1) ~[?:?]
                """);
        assertTrue(!Finding.TIMING_CRASH.equals(f.get(0).code()), f.get(0).code());
    }

    @Test
    void mixinRefsBecomeModIds() {
        assertEquals("supplementaries", CrashTranslator.modFromMixinRef("supplementaries.mixins.json:LevelChunkMixin->@Redirect"));
        assertEquals("oculus", CrashTranslator.modFromMixinRef("mixins.oculus.vertexformat.json:block_rendering.Foo"));
        assertEquals("immersive_weathering", CrashTranslator.modFromMixinRef("immersive_weathering-common.mixins.json:X"));
        assertEquals("sodium", CrashTranslator.modFromMixinRef("me.jellysquid.mods.sodium.mixin.features.Foo"));
        assertEquals("starlight", CrashTranslator.modFromMixinRef("ca.spottedleaf.starlight.mixin.common.Bar"));
    }

    @Test
    void notACrashReport() {
        assertEquals("unknown-crash", translate("hello").get(0).code());
    }
}
