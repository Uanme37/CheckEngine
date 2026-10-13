package com.guiltypotato.checkengine.core.intercept;

import com.guiltypotato.checkengine.core.json.Json;
import com.guiltypotato.checkengine.core.scan.PackInfo;
import com.guiltypotato.checkengine.core.scan.RamAdvisor;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * Launcher settings that will hurt this pack: far too little memory, or a Java much newer than this Minecraft was made
 * for. Nothing is broken in the pack itself, so these are blue "settings" windows, shown before the load only when the
 * problem is big, with "Don't ask again".
 */
public final class SetupCheck {
    /**
     * One settings window.
     *
     * @param key        the setting that turns this warning off ("ignore_memory")
     * @param quitFirst  true when Quit is the main button (the launch will probably fail), false for Continue
     */
    public record Warning(String key, String code, String codeLabel, String headline, String panel, String problem,
                          String detail, String fix, String fixNote, boolean quitFirst) {
        public String toText() {
            return headline + ": " + problem + " " + detail + " Fix: " + fix + (fixNote == null ? "" : " " + fixNote);
        }
    }

    private SetupCheck() {}

    /** The first settings problem worth a window, or null. Skips the ones the player turned off. */
    public static Warning first(Path gameDir, Path checkEngineFolder, int expectedJava) {
        Warning w = memory(gameDir, Runtime.getRuntime().maxMemory() / (1024 * 1024));
        if (w != null && !ignored(checkEngineFolder, w.key())) return w;
        w = java(Runtime.version().feature(), expectedJava, gameDir);
        if (w != null && !ignored(checkEngineFolder, w.key())) return w;
        return null;
    }

    /**
     * Far too little memory for this pack: under 60% of what it needs, and at least 1.5 GB short. What it needs is the
     * pack author's recommendation in CurseForge, or Check Engine's estimate (measured on the last launch if it can).
     */
    static Warning memory(Path gameDir, long maxHeapMb) {
        RamAdvisor.Facts facts = RamAdvisor.facts(gameDir, gameDir.resolve("mods"));
        long authors = curseForgeRecommendedMb(gameDir);
        long need = authors > 0 ? authors : RamAdvisor.recommendedMb(facts);
        if (maxHeapMb <= 0 || maxHeapMb >= need * 60 / 100 || need - maxHeapMb < 1536) return null;
        long pc = facts.systemMb();
        long target = pc > 0 ? Math.min(need, Math.max(maxHeapMb, pc - 3072)) : need;
        PackInfo pack = PackInfo.load(gameDir);
        String name = pack.name() != null ? pack.name() : "This pack";
        String headName = pack.headlineName();
        String fix = launcherSteps(gameDir, "memory", gb(target));
        long pcGb = Math.round(pc / 1024.0);
        String note = pc <= 0 ? null : target >= need ? "Your PC has " + pcGb + " GB, so that's fine."
                : "Your PC has " + pcGb + " GB, so " + gb(target) + " is the most it can spare. Close other programs "
                        + "while playing.";
        return new Warning("ignore_memory", "CE-08", "LOW MEMORY",
                headName != null ? "LOW MEMORY FOR " + headName : "NOT ENOUGH MEMORY",
                "MEMORY", "The game has " + gb(maxHeapMb) + ". " + name + (authors > 0 ? " recommends " : " needs about ")
                        + gb(need) + ".",
                "With " + facts.modCount() + " mods it will probably freeze or crash while loading.", fix, note, true);
    }

    /**
     * A Java much newer than this Minecraft was made for (5+ versions): most mods are fine, some older ones crash.
     * Too old a Java never gets this far (the game can't start at all), so that case is Crash Doctor's.
     */
    static Warning java(int running, int expected, Path gameDir) {
        if (expected <= 0 || running < expected + 5) return null;
        return new Warning("ignore_java", "CE-07", "JAVA VERSION", "JAVA " + running + " MAY BREAK SOME MODS", "JAVA",
                "This Minecraft is made for Java " + expected + ". This launch uses Java " + running + ".",
                "Most mods are fine, but some older ones crash on newer Java.",
                "If it crashes: " + launcherSteps(gameDir, "java", "Java " + expected), null, false);
    }

    /** How to change memory or Java in the launcher this pack runs in. */
    private static String launcherSteps(Path gameDir, String what, String value) {
        boolean memory = what.equals("memory");
        if (Files.isRegularFile(gameDir.resolve("minecraftinstance.json"))) {
            return "CurseForge: three dots next to Play > Profile options > " + (memory ? "Allocated memory" : "Java")
                    + " > " + value + ".";
        }
        Path parent = gameDir.toAbsolutePath().getParent();
        if (parent != null && Files.isRegularFile(parent.resolve("instance.cfg"))) {
            return "Prism: Edit instance > Settings > Java > " + (memory ? "Maximum memory allocation" : "Java installation")
                    + " > " + value + ".";
        }
        return memory ? "In your launcher's settings, give the game " + value + " of memory (-Xmx)."
                : "In your launcher's settings, pick " + value + ".";
    }

    /** The pack author's recommended memory, which CurseForge uses when "use recommended" is on. */
    static long curseForgeRecommendedMb(Path gameDir) {
        Path cf = gameDir.resolve("minecraftinstance.json");
        if (!Files.isRegularFile(cf)) return 0;
        try {
            Map<String, Object> o = Json.parseObject(Files.readString(cf, StandardCharsets.UTF_8));
            return o.get("recommendedRam") instanceof Double d ? d.longValue() : 0;
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    static String gb(long mb) {
        double g = mb / 1024.0;
        return (Math.abs(g - Math.round(g)) < 0.05 ? String.valueOf(Math.round(g))
                : String.format(Locale.ROOT, "%.1f", g)) + " GB";
    }

    // ---- "Don't ask again", in checkengine/settings.properties ----

    static boolean ignored(Path checkEngineFolder, String key) {
        return "true".equalsIgnoreCase(settings(checkEngineFolder).getProperty(key, "false").strip());
    }

    public static void ignore(Path checkEngineFolder, String key) {
        Properties p = settings(checkEngineFolder);
        p.setProperty(key, "true");
        try {
            Files.createDirectories(checkEngineFolder);
            try (Writer out = Files.newBufferedWriter(checkEngineFolder.resolve(LaunchIntercept.SETTINGS_FILE),
                    StandardCharsets.UTF_8)) {
                p.store(out, "Check Engine settings. launch_popup: show a window before the loading screen when the"
                        + " pack won't start (true/false). ignore_memory / ignore_java: don't warn about those.");
            }
        } catch (IOException ignored) {
            // asked again next time
        }
    }

    private static Properties settings(Path checkEngineFolder) {
        Properties p = new Properties();
        Path file = checkEngineFolder.resolve(LaunchIntercept.SETTINGS_FILE);
        if (!Files.isRegularFile(file)) return p;
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(in);
        } catch (IOException | IllegalArgumentException ignored) {
            // defaults
        }
        return p;
    }

    /** For tests: just the lines a window would show. */
    static List<String> lines(Warning w) {
        return List.of(w.headline(), w.problem(), w.detail(), w.fix());
    }
}
