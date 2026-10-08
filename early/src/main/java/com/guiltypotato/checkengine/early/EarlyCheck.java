package com.guiltypotato.checkengine.early;

import com.guiltypotato.checkengine.core.model.Side;
import com.guiltypotato.checkengine.core.scan.EarlyHandoff;
import com.guiltypotato.checkengine.core.scan.Finding;
import com.guiltypotato.checkengine.core.scan.IssueText;
import com.guiltypotato.checkengine.core.scan.LoadBlockers;
import com.guiltypotato.checkengine.core.scan.PackFolder;
import com.guiltypotato.checkengine.core.scan.PackScanner;
import com.guiltypotato.checkengine.core.scan.Report;
import com.guiltypotato.checkengine.core.scan.ScanOptions;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModLoadingIssue;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforgespi.locating.IDependencyLocator;
import net.neoforged.neoforgespi.locating.IDiscoveryPipeline;
import net.neoforged.neoforgespi.locating.IModFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs once NeoForge has found every mod, just before it checks their dependencies. If that check is going to fail,
 * Check Engine's plain-English explanation goes on NeoForge's error screen, above NeoForge's own messages.
 * It also starts the full pack scan in the background, which the in-game mod picks up later.
 */
public class EarlyCheck implements IDependencyLocator {
    static final Logger LOGGER = LoggerFactory.getLogger("Check Engine");
    static final String REPORT_FILE = "early-report.txt";

    @Override
    public int getPriority() {
        return -100; // after NeoForge's jar-in-jar locator (0), so mods packed inside other mods are counted
    }

    @Override
    public void scanMods(List<IModFile> mods, IDiscoveryPipeline pipeline) {
        try {
            startPackScan();
            List<Finding> blockers = LoadBlockers.explain(DependencyRules.check(mods));
            Path report = FMLPaths.GAMEDIR.get().resolve("checkengine").resolve(REPORT_FILE);
            if (blockers.isEmpty()) {
                LOGGER.info("Check Engine: {} mod files, nothing will stop loading", mods.size());
                Files.deleteIfExists(report);
                return;
            }
            LOGGER.error("Check Engine: {} problem(s) will stop this pack from loading. Details: {}",
                    blockers.size(), report);
            for (Finding f : blockers) {
                LOGGER.error("  {}", f.title());
                // "{0}" isn't in NeoForge's language file, so the screen shows the argument as is.
                pipeline.addIssue(ModLoadingIssue.error("{0}", IssueText.problem(f)));
            }
            // The pack is failing anyway, so wait for the full scan: its warnings (a duplicate mod, a broken
            // download...) go under the problems as clues.
            for (Finding f : IssueText.clues(waitForScan())) {
                pipeline.addIssue(ModLoadingIssue.warning("{0}", IssueText.clue(f)));
            }
            save(report, blockers);
        } catch (Throwable t) {
            // Never be the reason a pack doesn't start.
            LOGGER.error("Check Engine: early check failed", t);
        }
    }

    private static Report waitForScan() {
        try {
            return EarlyHandoff.scan().get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            return null;
        }
    }

    private static void save(Path report, List<Finding> blockers) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("Check Engine: why this pack didn't start (" + LocalDateTime.now().withNano(0) + ")");
        for (Finding f : blockers) {
            lines.add("");
            lines.add("PROBLEM: " + f.title());
            lines.add(f.detail());
            lines.add("FIX: " + f.fix());
        }
        Files.createDirectories(report.getParent());
        Files.write(report, lines, StandardCharsets.UTF_8);
    }

    /** The same scan the in-game popup and Check Pack.bat use, started now so it's ready by the title screen. */
    private static void startPackScan() {
        EarlyHandoff.offer(CompletableFuture.supplyAsync(() -> {
            try {
                Side side = FMLLoader.getDist() == Dist.CLIENT ? Side.CLIENT : Side.SERVER;
                Path game = FMLPaths.GAMEDIR.get();
                ScanOptions found = PackFolder.locate(game, side).options();
                ScanOptions options = new ScanOptions(side, FMLLoader.versionInfo().mcVersion(),
                        FMLLoader.versionInfo().neoForgeVersion(), found.clientOnlyFiles(),
                        ScanOptions.Loader.NEOFORGE);
                return PackScanner.scan(FMLPaths.MODSDIR.get(), options);
            } catch (IOException | RuntimeException e) {
                LOGGER.error("Check Engine: pack scan failed", e);
                return null;
            }
        }));
    }
}
