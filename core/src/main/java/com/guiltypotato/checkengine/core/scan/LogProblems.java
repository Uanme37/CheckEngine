package com.guiltypotato.checkengine.core.scan;

import com.guiltypotato.checkengine.core.crash.GameLog;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the game log knows that nobody reads: config values the loader threw away (a typo, a value out of range) and
 * textures or models a mod is missing (purple-black blocks, invisible items).
 */
public final class LogProblems {
    /** "Configuration file C:\...\config\create-server.toml is not correct. Correcting" */
    private static final Pattern CONFIG_FILE = Pattern.compile("Configuration file (.+?) is not correct\\. Correcting");
    /** "Incorrect key kinetics.maxSpeed was corrected from 9999 to its default, 256. " */
    private static final Pattern CORRECTED = Pattern.compile(
            "Incorrect key (.+?) was corrected from (.*?) to its default, (.*?)\\.? *$");
    /** A config the loader couldn't read at all. */
    private static final Pattern BROKEN_CONFIG = Pattern.compile(
            // NeoForge 1.21: "Failed to load config neoforge-common.toml: ...ParsingException" (then resets it)
            "(?:Failed loading config file|Failed to (?:load|parse|read) config(?: file)?) "
                    + "(\\S+?\\.(?:toml|json5?|cfg|properties|ya?ml))\\b");
    /** The mod a missing texture or model belongs to: the namespace of the first resource named. */
    private static final Pattern MISSING_ASSET = Pattern.compile(
            "(?:Missing textures in model|Using missing texture, unable to load|missing model for variant: '|"
                    + "Unable to load model: '|Exception loading blockstate definition: ')\\s*'?([a-z0-9_.-]+):");

    /**
     * A config value the loader reset.
     *
     * @param file  "config/create-server.toml"
     * @param key   "kinetics.maxSpeed"
     * @param was   what the file said
     * @param reset what it is now (the mod's default)
     */
    public record Reset(String file, String key, String was, String reset) {}

    /**
     * @param resets       config values that were thrown away
     * @param broken       config files that couldn't be read
     * @param missingAssets mod id -> how many missing textures/models it has
     */
    public record Result(List<Reset> resets, List<String> broken, Map<String, Integer> missingAssets) {
        public boolean isEmpty() {
            return resets.isEmpty() && broken.isEmpty() && missingAssets.isEmpty();
        }

        public String toText() {
            StringBuilder sb = new StringBuilder();
            if (!broken.isEmpty()) {
                sb.append("Config files the game couldn't read (it used defaults):\n");
                broken.forEach(b -> sb.append("  ").append(b).append('\n'));
            }
            if (!resets.isEmpty()) {
                sb.append("Config values the game threw away (a typo or a value it doesn't allow), now back to "
                        + "default:\n");
                resets.stream().limit(20).forEach(r -> sb.append("  ").append(r.file()).append(": ").append(r.key())
                        .append(" was ").append(r.was()).append(", now ").append(r.reset()).append('\n'));
                if (resets.size() > 20) sb.append("  ...and ").append(resets.size() - 20).append(" more\n");
                sb.append("  Set them again with a value the mod allows (the config file's comments say which).\n");
            }
            if (!missingAssets.isEmpty()) {
                sb.append("Missing textures and models (purple-black blocks, invisible items), by mod:\n");
                missingAssets.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(10)
                        .forEach(e -> sb.append("  ").append(e.getKey()).append(": ").append(e.getValue()).append('\n'));
                sb.append("  Usually an addon that doesn't match its main mod's version, or a resource pack that "
                        + "overrides it.\n");
            }
            return sb.toString();
        }
    }

    /** Fewer missing textures than this for a mod isn't worth naming. */
    static final int MIN_MISSING = 3;

    private LogProblems() {}

    /** Reads the last few launches: a reset value is only in the log of the launch that reset it. */
    public static Result check(Path gameDir) {
        List<String> logs = new ArrayList<>();
        for (Path p : GameLog.recent(gameDir, LogSpam.LAUNCHES)) {
            String text = GameLog.read(p);
            if (text != null) logs.add(text);
        }
        return analyze(logs, gameDir);
    }

    /** Logs newest first; {@code gameDir} only shortens file paths (may be null). */
    public static Result analyze(List<String> logs, Path gameDir) {
        Map<String, Reset> resets = new LinkedHashMap<>(); // file|key -> newest
        Set<String> broken = new LinkedHashSet<>();
        Map<String, Integer> missing = new TreeMap<>();
        Map<String, String> names = new java.util.HashMap<>();
        Map<String, Integer> thisLog = new java.util.HashMap<>();
        for (String log : logs) {
            Matcher n = LogSpam.MOD_LIST.matcher(log);
            while (n.find()) names.putIfAbsent(n.group(2), n.group(1).strip());
            String file = "a config file";
            for (String line : log.split("\r?\n")) {
                Matcher m;
                if ((m = CONFIG_FILE.matcher(line)).find()) {
                    file = shorten(m.group(1), gameDir);
                } else if ((m = CORRECTED.matcher(line)).find()) {
                    String was = m.group(2).strip(), reset = m.group(3).strip();
                    // "null" was = the key wasn't there (a mod update added it); "null" default = the mod dropped
                    // that option; same value = just reformatted. None of those is a typo.
                    if (was.equals("null") || reset.equals("null") || was.isEmpty() || was.equals(reset)
                            || same(was, reset)) {
                        continue;
                    }
                    resets.putIfAbsent(file + "|" + m.group(1), new Reset(file, m.group(1), was, reset));
                } else if ((m = BROKEN_CONFIG.matcher(line)).find()) {
                    broken.add(shorten(m.group(1), gameDir));
                } else if ((m = MISSING_ASSET.matcher(line)).find() && !m.group(1).equals("minecraft")) {
                    thisLog.merge(m.group(1), 1, Integer::sum);
                }
            }
            // missing textures repeat every launch: the most any one launch had, not the sum
            thisLog.forEach((id, c) -> missing.merge(id, c, Math::max));
            thisLog.clear();
        }
        // 1 or 2 missing textures is noise; by name when the log's mod list has it
        Map<String, Integer> shown = new TreeMap<>();
        missing.forEach((id, count) -> {
            if (count >= MIN_MISSING) shown.put(names.getOrDefault(id, id), count);
        });
        return new Result(List.copyOf(resets.values()), List.copyOf(broken), shown);
    }

    /** "1.0" and "1" are the same number. */
    private static boolean same(String a, String b) {
        try {
            return Double.parseDouble(a) == Double.parseDouble(b);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** "C:\...\Pack\config\..\x.toml" -> "config/x.toml" (or "x.toml"), relative to the pack. */
    static String shorten(String path, Path gameDir) {
        String p = path.replace('\\', '/').replace("/config/../", "/");
        int at = Math.max(p.indexOf("/config/"), Math.max(p.indexOf("/serverconfig/"), p.indexOf("/defaultconfigs/")));
        if (at >= 0) {
            String rest = p.substring(at + 1);
            int saves = p.lastIndexOf("/saves/", at);
            return saves >= 0 && rest.startsWith("serverconfig/") ? p.substring(saves + 1) : rest;
        }
        if (gameDir != null) {
            String root = gameDir.toAbsolutePath().toString().replace('\\', '/') + "/";
            if (p.startsWith(root)) return p.substring(root.length());
        }
        return p.substring(p.lastIndexOf('/') + 1);
    }
}
