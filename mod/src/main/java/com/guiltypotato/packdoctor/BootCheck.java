package com.guiltypotato.packdoctor;

import com.guiltypotato.packdoctor.core.model.Side;
import com.guiltypotato.packdoctor.core.scan.Finding;
import com.guiltypotato.packdoctor.core.scan.PackFolder;
import com.guiltypotato.packdoctor.core.scan.PackScanner;
import com.guiltypotato.packdoctor.core.scan.Report;
import com.guiltypotato.packdoctor.core.scan.ScanOptions;
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
 * packdoctor/boot-report.txt. The client shows a warning screen from the result; servers log it.
 */
public final class BootCheck {
    private static CompletableFuture<Report> result;

    private BootCheck() {}

    /** Folder for Pack Doctor's reports, next to mods/ and config/. */
    public static Path outputFolder() {
        return FMLPaths.GAMEDIR.get().resolve(PackDoctor.MOD_ID);
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

    private static Report run() {
        try {
            Side side = FMLEnvironment.dist.isClient() ? Side.CLIENT : Side.SERVER;
            // Picks up CurseForge's client-only tags when the game runs from a CurseForge instance.
            ScanOptions found = PackFolder.locate(FMLPaths.GAMEDIR.get(), side).options();
            ScanOptions options = new ScanOptions(side, FMLLoader.versionInfo().mcVersion(),
                    FMLLoader.versionInfo().neoForgeVersion(), found.clientOnlyFiles());
            Report report = PackScanner.scan(FMLPaths.MODSDIR.get(), options);
            Files.createDirectories(outputFolder());
            Files.writeString(reportFile(), report.toText(), StandardCharsets.UTF_8);
            List<Finding> shown = worthShowing(report);
            if (shown.isEmpty()) {
                PackDoctor.LOGGER.info("Pack Doctor: no problems found in {} jars", report.jars().size());
            } else {
                PackDoctor.LOGGER.warn("Pack Doctor: {}. Details: {}", Report.summary(shown), reportFile());
                for (Finding f : shown) PackDoctor.LOGGER.warn("  [{}] {}", f.severity(), f.title());
            }
            return report;
        } catch (IOException | RuntimeException e) {
            PackDoctor.LOGGER.error("Pack Doctor: boot check failed", e);
            return null;
        }
    }
}
