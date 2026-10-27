package com.guiltypotato.checkengine.core.scan;

import com.guiltypotato.checkengine.core.crash.GameLog;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which mods fill the game log with warnings and errors. A flooded log slows the game down, eats disk space and
 * hides the errors that matter. Reads the last few launches, so one bad launch doesn't decide it.
 */
public final class LogSpam {
    /** How many launches to read. */
    public static final int LAUNCHES = 5;
    /** A mod is worth naming from this many warnings and errors per launch. */
    static final int NOISY_PER_LAUNCH = 50;
    /** A log this big (per launch) is a problem on its own. */
    static final long BIG_LOG_BYTES = 50L * 1024 * 1024;

    /** "[14Oct2026 10:01:26.524] [main/WARN] [mixin/]: message" (the short "[10:01:26] [main/WARN]: x" has no logger). */
    private static final Pattern LINE = Pattern.compile(
            "^\\[[^\\]]+\\] \\[[^\\]]*/(WARN|ERROR|FATAL)\\](?: \\[([^\\]]*)\\])?: (.*)$");
    private static final Pattern FROM_MOD = Pattern.compile("from mod ([a-z0-9_.-]+)");
    /** A resource location in the message: 'createhaven:block/x' or createhaven:block/x. */
    private static final Pattern RESOURCE = Pattern.compile("(?<![A-Za-z0-9_.-])([a-z0-9_.-]{2,}):[a-z0-9_./-]+");
    /** "\t\tCreate 6.0.4 (create)" in the mod list at the top of a log. */
    static final Pattern MOD_LIST = Pattern.compile("(?m)^\\t\\t(.+?) \\S+ \\(([a-z0-9_.-]+)\\)\\s*$");
    private static final List<String> NOT_A_MOD = List.of("minecraft", "neoforge", "forge", "c", "java", "http",
            "https", "file", "jar", "union");

    /**
     * One mod's share of the noise.
     *
     * @param mod      mod id (or the logger's name when no mod could be told)
     * @param name     its display name, or the id
     * @param lines    warnings and errors over all the launches read
     * @param errors   how many of them are errors
     * @param top      its most repeated message, with names and numbers blanked out
     * @param topCount how often that message came
     */
    public record Source(String mod, String name, int lines, int errors, String top, int topCount) {
        public int perLaunch(int launches) {
            return Math.max(1, lines / Math.max(1, launches));
        }
    }

    /**
     * @param launches  how many logs were read
     * @param lines     warnings and errors in them, in all
     * @param sources   the noisiest first
     * @param bigLogs   logs over 50 MB: "latest.log (350 MB)"
     * @param normal    noisy mods left out because they were this noisy while the pack worked
     * @param when      where that comes from: "when the pack worked" or "in earlier launches"
     */
    public record Result(int launches, int lines, List<Source> sources, List<String> bigLogs, List<String> normal,
                         String when) {
        public Result(int launches, int lines, List<Source> sources, List<String> bigLogs) {
            this(launches, lines, sources, bigLogs, List.of(), "");
        }

        /**
         * The mods worth naming: on average {@value #NOISY_PER_LAUNCH}+ lines per launch, at most 5, and not ones
         * that were this noisy while the pack worked.
         */
        public List<Source> noisy() {
            return loud().filter(s -> !normal.contains(s.name())).limit(5).toList();
        }

        private java.util.stream.Stream<Source> loud() {
            // Minecraft and the loader itself: not something to update or remove
            return sources.stream().filter(s -> !s.mod().equals("minecraft") && s.perLaunch(launches) >= NOISY_PER_LAUNCH);
        }

        /** Leaves out the noisy mods that were just as noisy while the pack worked (see {@link LogBaseline}). */
        public Result onlyNew(LogBaseline.Normal before) {
            List<String> same = loud().filter(s -> !LogBaseline.isNew(before.spam().get(s.mod()), s.perLaunch(launches)))
                    .map(Source::name).toList();
            return new Result(launches, lines, sources, bigLogs, same, before.when());
        }

        public boolean isEmpty() {
            return noisy().isEmpty() && bigLogs.isEmpty();
        }

        public String toText() {
            StringBuilder sb = new StringBuilder("Log check: the last " + launches + " launch"
                    + (launches == 1 ? "" : "es") + " (" + String.format(Locale.ROOT, "%,d", lines)
                    + " warnings and errors)\n");
            for (String b : bigLogs) sb.append("Big log: ").append(b).append(". Normal is under 20 MB.\n");
            List<Source> noisy = noisy();
            if (!normal.isEmpty()) {
                sb.append("Noisy, but just as noisy " + when + " (normal for this pack, left out): ")
                        .append(String.join(", ", normal.subList(0, Math.min(8, normal.size()))))
                        .append(normal.size() > 8 ? " and " + (normal.size() - 8) + " more" : "").append('\n');
            }
            if (noisy.isEmpty()) {
                sb.append(normal.isEmpty() ? "No mod floods the log.\n" : "No new log noise.\n");
                return sb.toString();
            }
            if (launches == 1) sb.append("Only one launch to read: can't tell yet what's normal for this pack.\n");
            sb.append(normal.isEmpty() ? "Mods writing the most warnings and errors (per launch):\n"
                    : "New noise (per launch):\n");
            for (Source s : noisy) {
                sb.append(String.format(Locale.ROOT, "  %s: about %,d", s.name(), s.perLaunch(launches)));
                if (s.errors() > 0) sb.append(String.format(Locale.ROOT, " (%,d errors in all)", s.errors()));
                sb.append("\n    Most often: ").append(s.top());
                if (s.topCount() > 1) sb.append(String.format(Locale.ROOT, " (x%,d)", s.topCount()));
                sb.append('\n');
            }
            sb.append("Fix: update those mods, or ask their authors. Missing models and textures usually mean an "
                    + "addon doesn't match its main mod's version.\n");
            return sb.toString();
        }
    }

    private LogSpam() {}

    /** Reads the pack's last {@link #LAUNCHES} game logs. */
    public static Result check(Path gameDir) {
        List<String> logs = new ArrayList<>();
        List<String> big = new ArrayList<>();
        for (Path p : GameLog.recent(gameDir, LAUNCHES)) {
            try {
                if (p.getFileName().toString().equals("latest.log") && Files.size(p) > BIG_LOG_BYTES) {
                    big.add(p.getFileName() + " (" + Files.size(p) / (1024 * 1024) + " MB)");
                }
            } catch (java.io.IOException ignored) {
                // size unknown
            }
            String text = GameLog.read(p);
            if (text != null) logs.add(text);
        }
        Result r = analyze(logs);
        return new Result(r.launches(), r.lines(), r.sources(), big).onlyNew(LogBaseline.forPack(gameDir, logs));
    }

    /** Counts warnings and errors per mod over these logs (one per launch). */
    public static Result analyze(List<String> logs) {
        Map<String, String> names = new HashMap<>();
        Map<String, int[]> counts = new LinkedHashMap<>(); // mod -> {lines, errors}
        Map<String, Map<String, Integer>> messages = new HashMap<>();
        int total = 0;
        for (String log : logs) {
            Matcher n = MOD_LIST.matcher(log);
            while (n.find()) names.putIfAbsent(n.group(2), n.group(1).strip());
            for (String line : log.split("\r?\n")) {
                if (line.isEmpty() || line.charAt(0) != '[') continue;
                Matcher m = LINE.matcher(line);
                if (!m.matches() || KnownHarmless.logLine(line)) continue;
                String message = m.group(3);
                String mod = source(m.group(2), message, names);
                total++;
                int[] c = counts.computeIfAbsent(mod, k -> new int[2]);
                c[0]++;
                if (!m.group(1).equals("WARN")) c[1]++;
                messages.computeIfAbsent(mod, k -> new HashMap<>()).merge(blank(message), 1, Integer::sum);
            }
        }
        List<Source> sources = new ArrayList<>();
        counts.forEach((mod, c) -> {
            Map.Entry<String, Integer> top = messages.get(mod).entrySet().stream()
                    .max(Map.Entry.comparingByValue()).orElseThrow();
            sources.add(new Source(mod, names.getOrDefault(mod, mod), c[0], c[1], top.getKey(), top.getValue()));
        });
        sources.sort((a, b) -> Integer.compare(b.lines(), a.lines()));
        return new Result(logs.size(), total, sources, List.of());
    }

    /** Which mod a line is about: the mod named in it, the mod its resources belong to, else the logger. */
    static String source(String logger, String message, Map<String, String> names) {
        Matcher m = FROM_MOD.matcher(message);
        if (m.find()) return m.group(1);
        m = RESOURCE.matcher(message);
        while (m.find()) {
            String ns = m.group(1);
            // a mixin config: "biggerstacks.mixins.json:x" or "copycats-neoforge.mixins.json:x"
            if (ns.endsWith(".json")) ns = ns.split("[.-]")[0];
            if (!NOT_A_MOD.contains(ns) && (names.isEmpty() || names.containsKey(ns))) return ns;
        }
        String l = logger == null ? "" : logger.replaceAll("/.*$", "");
        if (l.isEmpty() || l.startsWith("net.minecraft") || l.startsWith("com.mojang") || l.startsWith("net.neoforged")
                || l.startsWith("net.minecraftforge") || l.equals("mixin") || l.startsWith("cpw.")
                || l.equals("STDOUT") || l.equals("STDERR")) {
            return "minecraft";
        }
        // "com.simibubi.create.Create": the first part of the package that is a mod id
        for (String part : l.toLowerCase(Locale.ROOT).split("\\.")) {
            if (names.containsKey(part) && !NOT_A_MOD.contains(part)) return part;
        }
        for (Map.Entry<String, String> e : names.entrySet()) {
            if (e.getValue().equalsIgnoreCase(l)) return e.getKey(); // logger named after the mod: "ModernFix"
        }
        return l;
    }

    /** The same message with names and numbers blanked out, so repeats count as one: "Unable to load model: '*'". */
    static String blank(String message) {
        String s = message.replaceAll("(?<![A-Za-z])'[^']*'", "'*'").replaceAll("\"[^\"]*\"", "\"*\"")
                .replaceAll("[a-z0-9_.-]+:[a-z0-9_./-]+", "*").replaceAll("@[0-9a-f]+\\b", "")
                .replaceAll("\\b[0-9a-f]*\\d[0-9a-f]*\\b", "#");
        return s.length() > 140 ? s.substring(0, 140) + "..." : s;
    }
}
