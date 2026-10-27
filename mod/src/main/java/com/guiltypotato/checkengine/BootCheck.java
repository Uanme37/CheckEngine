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

    /** The previous game's crash, explained, if it hasn't been shown yet (Crash Doctor). */
    public static LastCrash lastCrash() {
        return lastCrash;
    }

    static void start() {
        // Never be the reason a pack doesn't start: anything unexpected is logged and loading goes on.
        try {
            startChecks();
        } catch (Throwable t) {
            CheckEngine.LOGGER.error("Check Engine: startup check failed, carrying on without it", t);
        }
    }

    private static void startChecks() {
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
        // Not during Strip Mine: mods are switched off on purpose then.
        worlds = result.thenApplyAsync(r -> {
            if (r == null || com.guiltypotato.checkengine.core.strip.StripMine.running(outputFolder()) != null) {
                return java.util.List.<com.guiltypotato.checkengine.core.world.SafeRemove.WorldHit>of();
            }
            var removed = com.guiltypotato.checkengine.core.world.SafeRemove.removed(r.changes());
            var hits = com.guiltypotato.checkengine.core.world.SafeRemove.check(FMLPaths.GAMEDIR.get(), removed);
            if (!hits.isEmpty()) {
                CheckEngine.LOGGER.warn("Check Engine: worlds still use removed mods:\n{}",
                        com.guiltypotato.checkengine.core.world.SafeRemove.toText(hits));
            }
            return hits;
        });
    }

    /** Worlds that still use a removed mod; empty if none, or the count isn't done after a few seconds. */
    public static java.util.List<com.guiltypotato.checkengine.core.world.SafeRemove.WorldHit> worldsUsingRemovedMods() {
        if (worlds == null) return java.util.List.of();
        try {
            return worlds.get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            return java.util.List.of();
        }
    }

    /** The finished report, or null if the scan failed or isn't done after a few seconds. */
    public static Report get() {
        if (result == null) return null;
        try {
            return result.get(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            return null;
        }
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
        Report report = get();
        if (report == null) return;
        // During a Strip Mine search half the pack is switched off on purpose: that's not "the pack that worked".
        if (com.guiltypotato.checkengine.core.strip.StripMine.running(outputFolder()) != null) return;
        try {
            // What changed since the last launch that worked goes in the pack journal first.
            com.guiltypotato.checkengine.core.scan.PackJournal.record(outputFolder(), report.changes(),
                    lastCrash() == null);
            PackSnapshot.of(report.jars(), report.options()).save(outputFolder());
            // The configs too, so a config edit that breaks the pack can be found and undone. Not on a launch that
            // explains a crash: the changed configs may be why, and Crash Doctor offers to undo them.
            if (lastCrash() == null) {
                com.guiltypotato.checkengine.core.scan.ConfigCheckpoint.save(outputFolder().getParent(), outputFolder());
            }
        } catch (IOException | RuntimeException e) {
            CheckEngine.LOGGER.warn("Check Engine: couldn't save the last good launch", e);
        }
        // The log noise of a launch that worked is normal for this pack: the log checks only name what's new.
        // Reading the log takes a moment in big packs, so not on the game's thread.
        Thread t = new Thread(() -> {
            try {
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
        try {
            Runtime rt = Runtime.getRuntime();
            System.gc(); // count what's really kept, not garbage waiting to be cleaned
            long used = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
            long max = rt.maxMemory() / (1024 * 1024);
            java.util.Properties p = new java.util.Properties();
            p.setProperty("used_after_load_mb", Long.toString(used));
            p.setProperty("max_mb", Long.toString(max));
            p.setProperty("measured", java.time.LocalDateTime.now().withNano(0).toString());
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
            Files.writeString(reportFile(), report.toText(), StandardCharsets.UTF_8);
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
