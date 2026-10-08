package com.guiltypotato.checkengine.early;

import com.guiltypotato.checkengine.core.intercept.LaunchIntercept;
import com.guiltypotato.checkengine.core.model.Side;
import com.guiltypotato.checkengine.core.scan.EarlyHandoff;
import com.guiltypotato.checkengine.core.scan.Finding;
import com.guiltypotato.checkengine.core.scan.IssueText;
import com.guiltypotato.checkengine.core.scan.LoadBlockers;
import com.guiltypotato.checkengine.core.scan.PackFolder;
import com.guiltypotato.checkengine.core.scan.PackScanner;
import com.guiltypotato.checkengine.core.scan.PackSnapshot;
import com.guiltypotato.checkengine.core.scan.Report;
import com.guiltypotato.checkengine.core.scan.RootProblem;
import com.guiltypotato.checkengine.core.scan.ScanOptions;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModLoadingIssue;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforgespi.language.IModInfo;
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
            List<LoadBlockers.Blocker> rules = DependencyRules.check(mods);
            Path folder = FMLPaths.GAMEDIR.get().resolve("checkengine");
            Path report = folder.resolve(REPORT_FILE);
            LaunchIntercept.Message repeat = LaunchIntercept.repeatCrash(FMLPaths.GAMEDIR.get(), folder, snapshot(mods));
            if (rules.isEmpty()) {
                LOGGER.info("Check Engine: {} mod files, nothing will stop loading", mods.size());
                Files.deleteIfExists(report);
                intercept(repeat, folder); // the last launch crashed and nothing changed: it will again
                return;
            }
            // The pack is failing anyway, so wait for the full scan: it shows why a mod looks missing (a Fabric
            // build, a broken download...), and its warnings go under the problems as clues.
            Report scan = waitForScan();
            List<RootProblem> problems = LoadBlockers.analyze(rules, DependencyRules.coreMods(mods),
                    scan == null ? List.of() : scan.jars(), FMLPaths.MODSDIR.get());
            if (scan != null && scan.changes() != null) problems = scan.changes().annotate(problems);
            List<Finding> roots = problems.stream().map(RootProblem::toFinding).toList();
            LOGGER.error("Check Engine: {} root problem(s) will stop this pack from loading. Details: {}",
                    roots.size(), report);
            for (Finding f : roots) {
                LOGGER.error("  {}", f.title());
                // "{0}" isn't in NeoForge's language file, so the screen shows the argument as is.
                pipeline.addIssue(ModLoadingIssue.error("{0}", IssueText.problem(f)));
            }
            for (Finding f : IssueText.clues(scan, problems)) {
                pipeline.addIssue(ModLoadingIssue.warning("{0}", IssueText.clue(f)));
            }
            save(report, roots);
            intercept(LaunchIntercept.willFail(problems, scan == null ? null : scan.changes(), report), folder);
        } catch (Throwable t) {
            // Never be the reason a pack doesn't start.
            LOGGER.error("Check Engine: early check failed", t);
        }
    }

    /**
     * Pre-Launch Failure Intercept: on a player's game, show the Check Engine window now, before the loading screen.
     * Quit closes the game right away instead of waiting for the whole pack to load just to see the error.
     */
    private static void intercept(LaunchIntercept.Message message, Path folder) {
        if (message == null || FMLLoader.getCurrent().getDist() != Dist.CLIENT || !LaunchIntercept.enabled(folder)) return;
        LOGGER.warn("Check Engine: {}", message.toText().replace('\n', ' '));
        if (LaunchIntercept.ask(message) == LaunchIntercept.Choice.QUIT) {
            LOGGER.info("Check Engine: the player chose Quit before loading");
            System.exit(0);
        }
    }

    /** The mods NeoForge found, for "did anything change since the last launch?". */
    private static PackSnapshot snapshot(List<IModFile> mods) {
        Map<String, PackSnapshot.Entry> out = new TreeMap<>();
        for (IModFile file : mods) {
            for (IModInfo m : file.getModInfos()) {
                out.put(m.getModId(), new PackSnapshot.Entry(m.getDisplayName(), m.getVersion().toString(),
                        file.getFileName()));
            }
        }
        return new PackSnapshot(LocalDateTime.now().withNano(0).toString(), null, null, out);
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
                Side side = FMLLoader.getCurrent().getDist() == Dist.CLIENT ? Side.CLIENT : Side.SERVER;
                Path game = FMLPaths.GAMEDIR.get();
                ScanOptions found = PackFolder.locate(game, side).options();
                ScanOptions options = new ScanOptions(side, FMLLoader.getCurrent().getVersionInfo().mcVersion(),
                        FMLLoader.getCurrent().getVersionInfo().neoForgeVersion(), found.clientOnlyFiles(),
                        ScanOptions.Loader.NEOFORGE);
                return PackScanner.scan(FMLPaths.MODSDIR.get(), options);
            } catch (IOException | RuntimeException e) {
                LOGGER.error("Check Engine: pack scan failed", e);
                return null;
            }
        }));
    }
}
