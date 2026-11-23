package checkengine.early;

import checkengine.core.intercept.LaunchIntercept;
import checkengine.core.intercept.SetupCheck;
import checkengine.core.model.ModInfo;
import checkengine.core.model.ModJar;
import checkengine.core.model.Side;
import checkengine.core.scan.EarlyHandoff;
import checkengine.core.scan.Finding;
import checkengine.core.scan.LoadBlockers;
import checkengine.core.scan.PackFolder;
import checkengine.core.scan.PackScanner;
import checkengine.core.scan.PackSnapshot;
import checkengine.core.scan.Report;
import checkengine.core.scan.RootProblem;
import checkengine.core.strip.StripMine;
import checkengine.core.scan.ScanOptions;
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
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.fml.loading.ModDirTransformerDiscoverer;
import net.minecraftforge.forgespi.language.IModInfo;
import net.minecraftforge.forgespi.locating.IDependencyLocator;
import net.minecraftforge.forgespi.locating.IModFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs once Forge has found every mod, just before it checks their dependencies. If Check Engine thinks that check
 * will fail, it shows its warning window and the player decides: quit and fix it, or load anyway. Check Engine never
 * stops loading by itself on Forge: if it's wrong, the modpack still starts; if it's right, Forge's own check stops
 * the game. It also starts the full modpack scan for the in-game mod.
 */
public class EarlyCheck implements IDependencyLocator {
    static final Logger LOGGER = LoggerFactory.getLogger("Check Engine");
    static final String REPORT_FILE = "early-report.txt";

    @Override
    public List<IModFile> scanMods(Iterable<IModFile> mods) {
        try {
            check(mods);
        } catch (Throwable t) {
            // Never be the reason a modpack doesn't start.
            LOGGER.error("Check Engine: early check failed", t);
        }
        // Never throw Forge's loading error from here: a false alarm would stop a modpack that works.
        return List.of();
    }

    private static void check(Iterable<IModFile> mods) throws IOException {
        // Did the last session that reached the title screen really work (no crash after)? Settle that before
        // anything compares against the last good launch.
        Path folder = FMLPaths.GAMEDIR.get().resolve("checkengine");
        Path report = folder.resolve(REPORT_FILE);
        LaunchIntercept.Message repeat;
        List<LoadBlockers.Blocker> rules;
        try (checkengine.core.scan.CostClock cost = checkengine.core.scan.CostClock.holding("early check")) {
            checkengine.core.scan.GoodLaunch.settle(FMLPaths.GAMEDIR.get(),
                    FMLPaths.GAMEDIR.get().resolve("checkengine"));
            startPackScan();
            repeat = LaunchIntercept.repeatCrash(FMLPaths.GAMEDIR.get(), folder, snapshot(mods));
            rules = DependencyRules.check(mods, Map.of());
        }
        Report scan = null;
        if (!rules.isEmpty()) {
            // Forge may not have added the mods packed inside other jars yet: count them too before saying anything.
            scan = waitForScan();
            if (scan == null) {
                // Without the scan, mods packed inside other jars (Kotlin for Forge, Puzzles Access API...) look
                // missing. Not sure means not saying "won't start": never be the reason a pack doesn't load.
                LOGGER.warn("Check Engine: the mods folder scan didn't finish in time, so possible problems are only logged: {}",
                        rules.stream().map(LoadBlockers.Blocker::toString).toList());
                if (repeat != null) intercept(repeat, folder);
                else settings(folder, 17);
                return;
            }
            rules = DependencyRules.check(mods, nestedMods(scan));
        }
        if (rules.isEmpty()) {
            LOGGER.info("Check Engine: nothing will stop loading");
            Files.deleteIfExists(report);
            if (repeat != null) intercept(repeat, folder); // the last launch crashed and nothing changed: it will again
            else settings(folder, 17);
            return;
        }
        List<RootProblem> problems = LoadBlockers.analyze(rules, DependencyRules.coreMods(mods),
                scan == null ? List.of() : scan.jars(), FMLPaths.MODSDIR.get());
        if (scan != null && scan.changes() != null) problems = scan.changes().annotate(problems);
        List<Finding> roots = problems.stream().map(RootProblem::toFinding).toList();
        LOGGER.error("Check Engine: {} root problem(s) will stop this modpack from loading. Details: {}", roots.size(),
                report);
        roots.forEach(f -> LOGGER.error("  {}", f.title()));
        save(report, roots);
        if (otherPluginsMayAddMods()) {
            // Another mod in the mods folder can add mods of its own after this check, so Forge might still be fine.
            LOGGER.warn("Check Engine: other mod-finding plugins are installed, so this is only logged");
            return;
        }
        if (StripMine.running(folder) != null) {
            // A Misfire round: mods are off on purpose, so advice about them would only mislead. Forge decides.
            LOGGER.warn("Check Engine: Misfire test round, some mods are switched off on purpose");
            return;
        }
        // Ignition off: nothing from Check Engine. Forge's own check stops the game by itself.
        if (!checkengine.core.scan.Modules.load(FMLPaths.GAMEDIR.get()).on(checkengine.core.scan.Modules.Part.IGNITION)) return;
        // The warning window: Quit closes the game, Load anyway lets Forge's own check decide.
        intercept(LaunchIntercept.willFail(problems, scan == null ? null : scan.changes(), report), folder);
    }

    /**
     * Pre-Launch Failure Intercept: on a player's game, show the Check Engine window now, before the loading screen.
     * Quit closes the game right away instead of waiting for the whole pack to load just to see the error.
     */
    private static void intercept(LaunchIntercept.Message message, Path folder) {
        if (message == null || FMLLoader.getDist() != Dist.CLIENT || !LaunchIntercept.enabled(folder)) return;
        if (!LaunchIntercept.shows(message, FMLPaths.GAMEDIR.get())) return;
        LOGGER.warn("Check Engine: {}", message.toText().replace('\n', ' '));
        if (LaunchIntercept.ask(message, FMLPaths.GAMEDIR.get()) == LaunchIntercept.Choice.QUIT) {
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
            // The packed-in mod jar has the same path in every version, also from before the package rename.
            return zip.getEntry(EarlyCheck.class.getName().replace('.', '/') + ".class") != null
                    || zip.getEntry("META-INF/jarjar/checkengine-mod.jar") != null;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static Report waitForScan() {
        try {
            return EarlyHandoff.scan().get(30, TimeUnit.SECONDS); // a big pack on a busy disk can take a while
        } catch (Exception e) {
            return null;
        }
    }

    private static void save(Path report, List<Finding> blockers) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("Check Engine: why this modpack didn't start (" + LocalDateTime.now().withNano(0) + ")");
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
            try (checkengine.core.scan.CostClock cost = checkengine.core.scan.CostClock.background("mods folder scan")) {
                Side side = FMLLoader.getDist() == Dist.CLIENT ? Side.CLIENT : Side.SERVER;
                ScanOptions found = PackFolder.locate(FMLPaths.GAMEDIR.get(), side).options();
                ScanOptions options = new ScanOptions(side, FMLLoader.versionInfo().mcVersion(),
                        FMLLoader.versionInfo().forgeVersion(), found.clientOnlyFiles(), ScanOptions.Loader.FORGE);
                return PackScanner.scan(FMLPaths.MODSDIR.get(), options);
            } catch (IOException | RuntimeException e) {
                LOGGER.error("Check Engine: modpack scan failed", e);
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

    /** Launcher settings that will hurt this pack (too little memory, a much newer Java): a blue window. */
    private static void settings(Path folder, int expectedJava) {
        if (FMLLoader.getDist() != Dist.CLIENT || !LaunchIntercept.enabled(folder)) return;
        if (!checkengine.core.scan.Modules.load(FMLPaths.GAMEDIR.get()).on(checkengine.core.scan.Modules.Part.IGNITION)) return;
        SetupCheck.Warning w = SetupCheck.first(FMLPaths.GAMEDIR.get(), folder, expectedJava);
        if (w == null) return;
        // Quiet mode: the pack maker switched the memory and Java windows off
        if (checkengine.core.scan.PackInfo.load(FMLPaths.GAMEDIR.get()).hidesScreen("settings")) {
            LOGGER.warn("Check Engine (not shown, quiet mode): {}", w.toText());
            return;
        }
        LOGGER.warn("Check Engine: {}", w.toText());
        if (LaunchIntercept.askSetting(w, folder) == LaunchIntercept.Choice.QUIT) {
            LOGGER.info("Check Engine: the player chose Quit before loading");
            System.exit(0);
        }
    }
}
