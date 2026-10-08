package com.guiltypotato.checkengine.early;

import com.guiltypotato.checkengine.core.intercept.LaunchIntercept;
import com.guiltypotato.checkengine.core.model.ModInfo;
import com.guiltypotato.checkengine.core.model.ModJar;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.EarlyLoadingException;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.fml.loading.ModDirTransformerDiscoverer;
import net.minecraftforge.forgespi.language.IModInfo;
import net.minecraftforge.forgespi.locating.IDependencyLocator;
import net.minecraftforge.forgespi.locating.IModFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs once Forge has found every mod, just before it checks their dependencies. If that check is going to fail,
 * Check Engine's explanation goes on Forge's error screen (Forge 1.20.1 only lets a plugin add errors, so Check
 * Engine only speaks up when Forge is going to stop anyway). It also starts the full pack scan for the in-game mod.
 */
public class EarlyCheck implements IDependencyLocator {
    static final Logger LOGGER = LoggerFactory.getLogger("Check Engine");
    static final String REPORT_FILE = "early-report.txt";

    @Override
    public List<IModFile> scanMods(Iterable<IModFile> mods) {
        List<EarlyLoadingException.ExceptionData> errors;
        try {
            errors = check(mods);
        } catch (Throwable t) {
            // Never be the reason a pack doesn't start.
            LOGGER.error("Check Engine: early check failed", t);
            return List.of();
        }
        if (!errors.isEmpty()) {
            throw new EarlyLoadingException("Check Engine: this pack can't start", null, errors);
        }
        return List.of();
    }

    private static List<EarlyLoadingException.ExceptionData> check(Iterable<IModFile> mods) throws IOException {
        startPackScan();
        Path folder = FMLPaths.GAMEDIR.get().resolve("checkengine");
        Path report = folder.resolve(REPORT_FILE);
        LaunchIntercept.Message repeat = LaunchIntercept.repeatCrash(FMLPaths.GAMEDIR.get(), folder, snapshot(mods));
        List<LoadBlockers.Blocker> rules = DependencyRules.check(mods, Map.of());
        Report scan = null;
        if (!rules.isEmpty()) {
            // Forge may not have added the mods packed inside other jars yet: count them too before saying anything.
            scan = waitForScan();
            if (scan != null) rules = DependencyRules.check(mods, nestedMods(scan));
        }
        if (rules.isEmpty()) {
            LOGGER.info("Check Engine: nothing will stop loading");
            Files.deleteIfExists(report);
            intercept(repeat, folder); // the last launch crashed and nothing changed: it will again
            return List.of();
        }
        List<RootProblem> problems = LoadBlockers.analyze(rules, DependencyRules.coreMods(mods),
                scan == null ? List.of() : scan.jars(), FMLPaths.MODSDIR.get());
        if (scan != null && scan.changes() != null) problems = scan.changes().annotate(problems);
        List<Finding> roots = problems.stream().map(RootProblem::toFinding).toList();
        LOGGER.error("Check Engine: {} root problem(s) will stop this pack from loading. Details: {}", roots.size(),
                report);
        roots.forEach(f -> LOGGER.error("  {}", f.title()));
        save(report, roots);
        if (otherPluginsMayAddMods()) {
            // Another mod in the mods folder can add mods of its own after this check, so Forge might still be fine.
            LOGGER.warn("Check Engine: other mod-finding plugins are installed, so this is only logged");
            return List.of();
        }
        intercept(LaunchIntercept.willFail(problems, scan == null ? null : scan.changes(), report), folder);
        List<EarlyLoadingException.ExceptionData> out = new ArrayList<>();
        // "{3}" isn't in Forge's language file, so the screen shows the argument as is ({0} to {2} are Forge's own
        // slots: mod info, loading stage, exception).
        for (Finding f : roots) out.add(new EarlyLoadingException.ExceptionData("{3}", IssueText.problem(f)));
        if (scan != null && scan.changes() != null && !scan.changes().isEmpty()) {
            out.add(new EarlyLoadingException.ExceptionData("{3}", IssueText.clue(scan.changes().toFinding())));
        }
        return out;
    }

    /**
     * Pre-Launch Failure Intercept: on a player's game, show the Check Engine window now, before the loading screen.
     * Quit closes the game right away instead of waiting for the whole pack to load just to see the error.
     */
    private static void intercept(LaunchIntercept.Message message, Path folder) {
        if (message == null || FMLLoader.getDist() != Dist.CLIENT || !LaunchIntercept.enabled(folder)) return;
        LOGGER.warn("Check Engine: {}", message.toText().replace('\n', ' '));
        if (LaunchIntercept.ask(message) == LaunchIntercept.Choice.QUIT) {
            LOGGER.info("Check Engine: the player chose Quit before loading");
            System.exit(0);
        }
    }

    /** The mods Forge found, for "did anything change since the last launch?". */
    private static PackSnapshot snapshot(Iterable<IModFile> mods) {
        Map<String, PackSnapshot.Entry> out = new TreeMap<>();
        for (IModFile file : mods) {
            if (file.getModFileInfo() == null) continue;
            for (IModInfo m : file.getModInfos()) {
                out.put(m.getModId(), new PackSnapshot.Entry(m.getDisplayName(), m.getVersion().toString(),
                        file.getFileName()));
            }
        }
        return new PackSnapshot(LocalDateTime.now().withNano(0).toString(), null, null, out);
    }

    /** Mod ids packed inside other jars (jar-in-jar), with their versions, from Check Engine's own scan. */
    private static Map<String, String> nestedMods(Report scan) {
        Map<String, String> out = new HashMap<>();
        for (ModJar jar : scan.jars()) {
            for (ModInfo m : jar.nestedMods()) out.putIfAbsent(m.modId(), m.version());
        }
        return out;
    }

    /** Service jars in the mods folder other than this one (e.g. Sinytra Connector) can add mods after we run. */
    private static boolean otherPluginsMayAddMods() {
        List<Path> others = ModDirTransformerDiscoverer.allExcluded().stream().filter(p -> !isCheckEngine(p)).toList();
        if (!others.isEmpty()) LOGGER.info("Check Engine: other plugin jars: {}", others);
        return !others.isEmpty();
    }

    /** True for this jar (any copy or version of it). */
    private static boolean isCheckEngine(Path jar) {
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar.toFile())) {
            return zip.getEntry(EarlyCheck.class.getName().replace('.', '/') + ".class") != null;
        } catch (IOException | RuntimeException e) {
            return false;
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
        if (EarlyHandoff.scan() != null) return;
        EarlyHandoff.offer(CompletableFuture.supplyAsync(() -> {
            try {
                Side side = FMLLoader.getDist() == Dist.CLIENT ? Side.CLIENT : Side.SERVER;
                ScanOptions found = PackFolder.locate(FMLPaths.GAMEDIR.get(), side).options();
                ScanOptions options = new ScanOptions(side, FMLLoader.versionInfo().mcVersion(),
                        FMLLoader.versionInfo().forgeVersion(), found.clientOnlyFiles(), ScanOptions.Loader.FORGE);
                return PackScanner.scan(FMLPaths.MODSDIR.get(), options);
            } catch (IOException | RuntimeException e) {
                LOGGER.error("Check Engine: pack scan failed", e);
                return null;
            }
        }));
    }

    @Override
    public String name() {
        return "checkengine";
    }

    @Override
    public void scanFile(IModFile file, Consumer<Path> pathConsumer) {
        // finds no mods of its own
    }

    @Override
    public void initArguments(Map<String, ?> arguments) {
    }

    @Override
    public boolean isValid(IModFile modFile) {
        return true;
    }
}
