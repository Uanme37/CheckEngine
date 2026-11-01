package com.guiltypotato.checkengine;

import com.guiltypotato.checkengine.core.crash.CrashDoctor;
import com.guiltypotato.checkengine.core.crash.CrashDoctor.LastCrash;
import com.guiltypotato.checkengine.core.model.Side;
import com.guiltypotato.checkengine.core.scan.EarlyHandoff;
import com.guiltypotato.checkengine.core.scan.Finding;
import com.guiltypotato.checkengine.core.scan.PackFolder;
import com.guiltypotato.checkengine.core.scan.PackScanner;
import com.guiltypotato.checkengine.core.scan.PackSnapshot;
import com.guiltypotato.checkengine.core.scan.RamAdvisor;
import com.guiltypotato.checkengine.core.scan.Report;
import com.guiltypotato.checkengine.core.scan.ScanOptions;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.FMLPaths;

/**
 * Scans the mods folder once at startup (in the background, while the game loads) and saves
 * checkengine/boot-report.txt. The client shows a warning screen from the result; servers log it.
 */
public final class BootCheck {
    private static CompletableFuture<Report> result;
    /** Worlds that still use a mod removed since the pack last worked (counted in the background). */
    private static CompletableFuture<java.util.List<com.guiltypotato.checkengine.core.world.SafeRemove.WorldHit>> worlds;
    private static LastCrash lastCrash;

    private BootCheck() {}

    /** Folder for Check Engine's reports, next to mods/ and config/. */
    public static Path outputFolder() {
        return FMLPaths.GAMEDIR.get().resolve(CheckEngine.MOD_ID);
    }

    public static Path reportFile() {
        return outputFolder().resolve("boot-report.txt");
    }

    /** The previous game's crash, explained, if it hasn't been shown yet (Mechanic). */
    public static LastCrash lastCrash() {
        return lastCrash;
    }

    /**
     * The crash to show on screens: none when the modpack maker switched Mechanic off. {@link #lastCrash()} stays as it
     * is for recording: a launch that follows a crash must never count as the last one that worked.
     */
    public static LastCrash crashToShow() {
        return on(com.guiltypotato.checkengine.core.scan.Modules.Part.MECHANIC) ? lastCrash : null;
    }

    /** Whether this part of Check Engine is on (the modpack maker's switches). */
    public static boolean on(com.guiltypotato.checkengine.core.scan.Modules.Part part) {
        return com.guiltypotato.checkengine.core.scan.Modules.load(FMLPaths.GAMEDIR.get()).on(part);
    }

    static void start() {
        // Never be the reason a pack doesn't start: anything unexpected is logged and loading goes on.
        try (com.guiltypotato.checkengine.core.scan.CostClock cost = com.guiltypotato.checkengine.core.scan.CostClock.holding("mod start checks")) {
            startChecks();
        } catch (Throwable t) {
            CheckEngine.LOGGER.error("Check Engine: startup check failed, carrying on without it", t);
        }
    }

    private static void startChecks() {
        com.guiltypotato.checkengine.core.scan.PackInfo.writeExample(FMLPaths.GAMEDIR.get());
        com.guiltypotato.checkengine.core.ui.ScreenHelp.misfire(on(com.guiltypotato.checkengine.core.scan.Modules.Part.MISFIRE));
        // Dev runs have no early plugin: settle whether the last session worked here (a no-op when it already ran).
        // configs an in-game Go back left for this start (normally done earlier, by the early plugin)
        com.guiltypotato.checkengine.core.strip.GoBack.applyPendingConfigs(FMLPaths.GAMEDIR.get(), outputFolder());
        com.guiltypotato.checkengine.core.scan.GoodLaunch.settle(FMLPaths.GAMEDIR.get(), outputFolder());
        // Go back's plan reads every jar: worked out now, in the background, so screens never wait for it
        com.guiltypotato.checkengine.core.strip.GoBackPlans.refresh(FMLPaths.GAMEDIR.get(), outputFolder());
        // A world rescue ran last session: the world loaded, so turn the remove-erroring setting off again.
        String rescued = com.guiltypotato.checkengine.core.crash.WorldRescue.finish(outputFolder());
        if (rescued != null) CheckEngine.LOGGER.info("Check Engine: {}", rescued);
        lastCrash = CrashDoctor.checkAndStartSession(FMLPaths.GAMEDIR.get(), outputFolder());
        if (lastCrash != null) {
            Finding f = lastCrash.main();
            CheckEngine.LOGGER.warn("Check Engine: the last game crashed ({}): {}. Fix: {}", lastCrash.when(), f.title(),
                    f.fix());
        }
        // The early plugin already started the scan before mods loaded; dev runs don't have it, so scan here.
        CompletableFuture<Report> early = EarlyHandoff.scan();
        result = early != null ? early.thenApply(BootCheck::save) : CompletableFuture.supplyAsync(BootCheck::run);
        // A mod removed since the pack last worked: count what of it the worlds still have, while the game loads.
        // Not during Misfire: mods are switched off on purpose then.
        if (!on(com.guiltypotato.checkengine.core.scan.Modules.Part.SCRAPYARD)) return; // no warning coming: don't read the worlds
        worlds = result.thenApplyAsync(r -> {
            if (r == null || com.guiltypotato.checkengine.core.strip.StripMine.running(outputFolder()) != null) {
                return java.util.List.<com.guiltypotato.checkengine.core.world.SafeRemove.WorldHit>of();
            }
            try (com.guiltypotato.checkengine.core.scan.CostClock cost = com.guiltypotato.checkengine.core.scan.CostClock.background("counting world blocks")) {
                var removed = com.guiltypotato.checkengine.core.world.SafeRemove.removed(r.changes());
                var hits = com.guiltypotato.checkengine.core.world.SafeRemove.check(FMLPaths.GAMEDIR.get(), removed);
                if (!hits.isEmpty()) {
                    CheckEngine.LOGGER.warn("Check Engine: worlds still use removed mods:\n{}",
                            com.guiltypotato.checkengine.core.world.SafeRemove.toText(hits));
                }
                return hits;
            }
        });
    }

    /** Worlds that still use a removed mod; empty if none, or the count isn't done after a few seconds. */
    public static java.util.List<com.guiltypotato.checkengine.core.world.SafeRemove.WorldHit> worldsUsingRemovedMods() {
        if (worlds == null) return java.util.List.of();
        try (com.guiltypotato.checkengine.core.scan.CostClock cost = com.guiltypotato.checkengine.core.scan.CostClock.holding("waiting for the world count")) {
            return worlds.get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            return java.util.List.of();
        }
    }

    /** The finished report, or null if the scan failed or isn't done after a few seconds. */
    public static Report get() {
        if (result == null) return null;
        try (com.guiltypotato.checkengine.core.scan.CostClock cost = com.guiltypotato.checkengine.core.scan.CostClock.holding("waiting for the scan")) {
            return result.get(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            return null;
        }
    }

    /** The report if the scan is done, else null. Never waits: for what's drawn on the game's thread. */
    public static Report ready() {
        try {
            return result == null ? null : result.getNow(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Runs {@code then} with the report once the scan is done: right away if it is, else off the game's thread. */
    public static void whenReady(java.util.function.Consumer<Report> then) {
        if (result == null) return;
        result.thenAccept(r -> {
            try {
                if (r != null) then.accept(r);
            } catch (Throwable t) {
                CheckEngine.LOGGER.warn("Check Engine: couldn't finish a step after the scan", t);
            }
        });
    }

    /** Problems and warnings (not notes): what's worth interrupting the player for. */
    public static List<Finding> worthShowing(Report report) {
        return report.findings().stream().filter(f -> f.severity() != Finding.Severity.INFO).toList();
    }

    /**
     * The game reached the title screen (or the server started). Saves the memory use and a fingerprint of the mods
     * (checkengine/last-good-launch.properties), so if the pack breaks later Check Engine can say what changed.
     */
    public static void launchSucceeded() {
        try {
            recordGoodLaunch();
        } catch (Throwable t) {
            CheckEngine.LOGGER.error("Check Engine: couldn't record the launch", t);
        }
    }

    private static void recordGoodLaunch() {
        recordMemory();
        // The scan is usually done long before the title screen; if not, the rest waits for it off the game's thread
        whenReady(BootCheck::recordGoodLaunch);
    }

    private static void recordGoodLaunch(Report report) {
        // During a Misfire search half the pack is switched off on purpose: that's not "the modpack that worked".
        if (com.guiltypotato.checkengine.core.strip.StripMine.running(outputFolder()) != null) return;
        try {
            // Startup worked, whatever happens later (the pre-launch check reads this)
            com.guiltypotato.checkengine.core.scan.GoodLaunch.reachedTitle(outputFolder());
            // What changed since the last launch that worked goes in the pack journal first.
            com.guiltypotato.checkengine.core.scan.PackJournal.record(outputFolder(), report.changes(),
                    lastCrash() == null);
        } catch (IOException | RuntimeException e) {
            CheckEngine.LOGGER.warn("Check Engine: couldn't write the modpack journal", e);
        }
        // The mods, their jars (kept as links, so Go back can put old versions back) and the configs. Only counts as
        // the last good launch if this session doesn't crash (settled next start). Not on a launch that explains a
        // crash: what changed may be why, and Mechanic offers to go back. Reading new jars takes a moment, so not
        // on the game's thread.
        if (lastCrash() == null) {
            PackSnapshot snapshot = PackSnapshot.of(report.jars(), report.options());
            Thread save = new Thread(() -> {
                try (com.guiltypotato.checkengine.core.scan.CostClock cost = com.guiltypotato.checkengine.core.scan.CostClock.background("saving what worked")) {
                    String note = com.guiltypotato.checkengine.core.scan.GoodLaunch.save(outputFolder().getParent(),
                            outputFolder(), snapshot);
                    if (note != null) CheckEngine.LOGGER.info("Check Engine: {}", note);
                } catch (IOException | RuntimeException e) {
                    CheckEngine.LOGGER.warn("Check Engine: couldn't save the last good launch", e);
                }
                com.guiltypotato.checkengine.core.scan.CostClock.save(outputFolder());
            }, "Check Engine good launch");
            save.setDaemon(true);
            save.start();
            // When the game closes: configs changed in game until then belong to the working pack
            Runtime.getRuntime().addShutdownHook(new Thread(() -> com.guiltypotato.checkengine.core.scan.GoodLaunch
                    .sessionEnded(outputFolder()), "Check Engine session end"));
        }
        // The log noise of a launch that worked is normal for this pack: the log checks only name what's new.
        // Reading the log takes a moment in big packs, so not on the game's thread.
        Thread t = new Thread(() -> {
            try (com.guiltypotato.checkengine.core.scan.CostClock cost = com.guiltypotato.checkengine.core.scan.CostClock.background("reading the log")) {
                com.guiltypotato.checkengine.core.scan.LogBaseline.save(outputFolder(), outputFolder().getParent());
            } catch (IOException | RuntimeException e) {
                CheckEngine.LOGGER.warn("Check Engine: couldn't save the log baseline", e);
            }
        }, "Check Engine log baseline");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Saves how much memory the game really uses once loading is done (checkengine/ram.properties), so the
     * checker can recommend a real number next time. Called once, when the title screen or server is ready.
     */
    public static void recordMemory() {
        List<String> args = java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments();
        if (!RamAdvisor.usesZgc(args)) {
            measureMemory(args);
            return;
        }
        // ZGC: the clean-up holds up only the thread that asks for it, for seconds: not the game's
        Thread t = new Thread(() -> measureMemory(args), "Check Engine memory");
        t.setDaemon(true);
        t.start();
    }

    private static void measureMemory(List<String> args) {
        try (com.guiltypotato.checkengine.core.scan.CostClock cost = RamAdvisor.usesZgc(args) ? com.guiltypotato.checkengine.core.scan.CostClock.background("memory clean-up") : com.guiltypotato.checkengine.core.scan.CostClock.holding("memory clean-up")) {
            Runtime rt = Runtime.getRuntime();
            System.gc(); // count what's really kept, not garbage waiting to be cleaned
            long used = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
            long max = rt.maxMemory() / (1024 * 1024);
            java.util.Properties p = new java.util.Properties();
            p.setProperty("used_after_load_mb", Long.toString(used));
            p.setProperty("max_mb", Long.toString(max));
            p.setProperty("measured", java.time.LocalDateTime.now().withNano(0).toString());
            if (!RamAdvisor.explicitGcWorks(args)) p.setProperty("explicit_gc", "false");
            Files.createDirectories(outputFolder());
            try (var out = Files.newBufferedWriter(outputFolder().resolve(RamAdvisor.MEASURED_FILE),
                    StandardCharsets.UTF_8)) {
                p.store(out, "Check Engine: memory used right after loading");
            }
            CheckEngine.LOGGER.info("Check Engine: using {} MB of {} MB after loading", used, max);
        } catch (IOException | RuntimeException e) {
            CheckEngine.LOGGER.warn("Check Engine: couldn't save memory use", e);
        }
    }

    private static Report run() {
        try {
            Side side = FMLEnvironment.dist.isClient() ? Side.CLIENT : Side.SERVER;
            // Picks up CurseForge's client-only tags when the game runs from a CurseForge instance.
            ScanOptions found = PackFolder.locate(FMLPaths.GAMEDIR.get(), side).options();
            ScanOptions options = new ScanOptions(side, FMLLoader.versionInfo().mcVersion(),
                    FMLLoader.versionInfo().neoForgeVersion(), found.clientOnlyFiles(), ScanOptions.Loader.NEOFORGE);
            return save(PackScanner.scan(FMLPaths.MODSDIR.get(), options));
        } catch (IOException | RuntimeException e) {
            CheckEngine.LOGGER.error("Check Engine: boot check failed", e);
            return null;
        }
    }

    /** Writes checkengine/boot-report.txt and logs the problems. */
    private static Report save(Report report) {
        if (report == null) return null;
        try {
            Files.createDirectories(outputFolder());
            var modules = com.guiltypotato.checkengine.core.scan.Modules.load(FMLPaths.GAMEDIR.get());
            var aside = com.guiltypotato.checkengine.core.scan.StepAside.active(FMLPaths.GAMEDIR.get(), report);
            Files.writeString(reportFile(), modules.reportBlock(aside) + report.toText(), StandardCharsets.UTF_8);
            aside.forEach(a -> CheckEngine.LOGGER.info("Check Engine: stepping aside for {}", a));
            if (!modules.unknownKeys().isEmpty()) {
                CheckEngine.LOGGER.warn("Check Engine: not understood in config/{}, check the spelling: {}",
                        com.guiltypotato.checkengine.core.scan.PackInfo.FILE, modules.unknownKeys());
            }
            List<Finding> shown = worthShowing(report);
            if (shown.isEmpty()) {
                CheckEngine.LOGGER.info("Check Engine: no problems found in {} jars", report.jars().size());
            } else {
                CheckEngine.LOGGER.warn("Check Engine: {}. Details: {}", Report.summary(shown), reportFile());
                for (Finding f : shown) CheckEngine.LOGGER.warn("  [{}] {}", f.severity(), f.title());
            }
        } catch (IOException | RuntimeException e) {
            CheckEngine.LOGGER.error("Check Engine: couldn't save the boot report", e);
        }
        return report;
    }
}
