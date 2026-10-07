package com.guiltypotato.checkengine;

import com.guiltypotato.checkengine.core.model.Side;
import com.guiltypotato.checkengine.core.scan.Finding;
import com.guiltypotato.checkengine.core.scan.PackFolder;
import com.guiltypotato.checkengine.core.scan.PackScanner;
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

    private BootCheck() {}

    /** Folder for Check Engine's reports, next to mods/ and config/. */
    public static Path outputFolder() {
        return FMLPaths.GAMEDIR.get().resolve(CheckEngine.MOD_ID);
    }

    public static Path reportFile() {
        return outputFolder().resolve("boot-report.txt");
    }

    static void start() {
        result = CompletableFuture.supplyAsync(BootCheck::run);
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
            Side side = FMLEnvironment.getDist().isClient() ? Side.CLIENT : Side.SERVER;
            // Picks up CurseForge's client-only tags when the game runs from a CurseForge instance.
            ScanOptions found = PackFolder.locate(FMLPaths.GAMEDIR.get(), side).options();
            ScanOptions options = new ScanOptions(side, FMLLoader.getCurrent().getVersionInfo().mcVersion(),
                    FMLLoader.getCurrent().getVersionInfo().neoForgeVersion(), found.clientOnlyFiles(), ScanOptions.Loader.NEOFORGE);
            Report report = PackScanner.scan(FMLPaths.MODSDIR.get(), options);
            Files.createDirectories(outputFolder());
            Files.writeString(reportFile(), report.toText(), StandardCharsets.UTF_8);
            List<Finding> shown = worthShowing(report);
            if (shown.isEmpty()) {
                CheckEngine.LOGGER.info("Check Engine: no problems found in {} jars", report.jars().size());
            } else {
                CheckEngine.LOGGER.warn("Check Engine: {}. Details: {}", Report.summary(shown), reportFile());
                for (Finding f : shown) CheckEngine.LOGGER.warn("  [{}] {}", f.severity(), f.title());
            }
            return report;
        } catch (IOException | RuntimeException e) {
            CheckEngine.LOGGER.error("Check Engine: boot check failed", e);
            return null;
        }
    }
}
