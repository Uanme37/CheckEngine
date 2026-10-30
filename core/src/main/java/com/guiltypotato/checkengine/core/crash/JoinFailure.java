package com.guiltypotato.checkengine.core.crash;

import com.guiltypotato.checkengine.core.scan.Finding;
import com.guiltypotato.checkengine.core.scan.Finding.Severity;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Invalid player data": the server couldn't put the player into the world, so it kicked them. There's no crash
 * report, only "Couldn't place player in world" and an error in the log. This reads that error and says which mod
 * broke the join and what to do, with known fixes for causes people already solved.
 */
public final class JoinFailure {
    public static final String CODE = "join-failed";
    static final String MARKER = "Couldn't place player in world";
    /** Log lines start with "[14Oct2026 10:29:28.182]" or "[10:29:28]"; anything else belongs to the line above. */
    private static final Pattern LOG_LINE = Pattern.compile("^\\[(?:\\d{2}[A-Za-z]{3}\\d{4} |\\d{2}:\\d{2}:\\d{2}\\])");
    private static final Pattern FRAME_JAR = Pattern.compile("~\\[([^\\]%!]+?\\.jar)");
    /** Mixin handlers carry the mod id: "handler$zbc000$luckperms$onPlaceNewPlayer". */
    private static final Pattern MIXIN_MOD = Pattern.compile("\\$[a-z]{3}\\d{3}\\$([a-z][a-z0-9_]*)\\$");
    private static final Pattern LOST = Pattern.compile("(\\S+) lost connection: (?:Invalid player data|multiplayer\\.disconnect\\.invalid_player_data)");
    private static final Pattern VERSION = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)");
    /** The game version in a stack frame: "TRANSFORMER/minecraft@26.1.2/net.minecraft..." */
    private static final Pattern GAME_VERSION = Pattern.compile("minecraft@([0-9][0-9.]*[0-9])/");
    /** Jars of the game and loader themselves: never "the mod behind it". */
    private static final List<String> NOT_MODS = List.of("minecraft", "client-", "server-", "neoforge-", "forge-",
            "fmlloader", "fmlcore", "javafmllanguage", "lowcodelanguage", "mclanguage", "loader-", "bootstraplauncher",
            "securejarhandler", "modlauncher", "eventbus", "coremods", "mixinextras", "mixin-", "datafixerupper",
            "brigadier", "authlib", "netty", "guava", "gson", "fastutil", "log4j");

    /**
     * @param player the kicked player's name, or null
     * @param error  the error's first line, e.g. "java.lang.NullPointerException: Cannot invoke ..."
     * @param mod    the mod most likely behind it (jar file or mod id), or null
     */
    public record Result(String player, String error, String mod, Finding finding, String line) {}

    /** Remembers which kick was already explained (the marker line, with its time). */
    public static final String SHOWN_FILE = "join-explained.txt";

    private JoinFailure() {}

    /** The last failed join in this log, explained; null when the log has none. */
    public static Result fromLog(String log) {
        if (log == null) return null;
        int at = log.lastIndexOf(MARKER);
        if (at < 0) return null;
        // the error: the lines after the marker that aren't new log lines
        List<String> lines = new ArrayList<>();
        String[] rest = log.substring(at).split("\\R");
        for (int i = 1; i < rest.length && lines.size() < 400; i++) {
            if (LOG_LINE.matcher(rest[i]).find()) break;
            if (!rest[i].isBlank()) lines.add(rest[i].strip());
        }
        String error = lines.isEmpty() ? null : lines.get(0);
        String trace = String.join("\n", lines);
        Matcher lost = LOST.matcher(log.substring(at));
        String player = lost.find() ? lost.group(1) : null;
        String mod = blamed(lines);
        int lineStart = log.lastIndexOf('\n', at) + 1;
        int lineEnd = log.indexOf('\n', at);
        String line = log.substring(lineStart, lineEnd < 0 ? log.length() : lineEnd).strip();
        return new Result(player, error, mod, explain(trace, error, mod, player), line);
    }

    /**
     * A kick in the last session that nobody explained yet (the popup didn't show, or the game was closed): read
     * from the newest packed log, which is the session before this one. Null when there was none.
     */
    public static Result lastSession(java.nio.file.Path gameDir, java.nio.file.Path checkEngineFolder) {
        List<java.nio.file.Path> logs = GameLog.recent(gameDir, 3).stream()
                .filter(p -> p.getFileName().toString().endsWith(".log.gz")).toList();
        if (logs.isEmpty()) return null;
        Result r = fromLog(GameLog.read(logs.get(0)));
        if (r == null || r.line().equals(shown(checkEngineFolder))) return null;
        return r;
    }

    /** Marks this kick as explained, so it isn't shown again on the next launch. */
    public static void markShown(java.nio.file.Path checkEngineFolder, Result r) {
        try {
            java.nio.file.Files.createDirectories(checkEngineFolder);
            java.nio.file.Files.writeString(checkEngineFolder.resolve(SHOWN_FILE), r.line(),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException ignored) {
            // shown again next time
        }
    }

    private static String shown(java.nio.file.Path checkEngineFolder) {
        try {
            return java.nio.file.Files.readString(checkEngineFolder.resolve(SHOWN_FILE),
                    java.nio.charset.StandardCharsets.UTF_8).strip();
        } catch (java.io.IOException | RuntimeException e) {
            return "";
        }
    }

    /** The first mod in the error: a mixin handler's mod id, else the first jar that isn't the game or loader. */
    static String blamed(List<String> lines) {
        for (String l : lines) {
            Matcher m = MIXIN_MOD.matcher(l);
            if (m.find() && !m.group(1).equals("minecraft")) return m.group(1);
        }
        for (String l : lines) {
            Matcher m = FRAME_JAR.matcher(l);
            while (m.find()) {
                String jar = m.group(1);
                String low = jar.toLowerCase(Locale.ROOT);
                if (NOT_MODS.stream().noneMatch(low::startsWith) && !low.contains("checkengine")) return jar;
            }
        }
        return null;
    }

    static Finding explain(String trace, String error, String mod, String player) {
        String low = trace.toLowerCase(Locale.ROOT);
        String who = player == null ? "The player" : player;
        String what = who + " was kicked with \"Invalid player data\": the server couldn't put them into the world.";
        String err = error == null ? "" : "\nThe error: " + shorten(error);
        // 1. LuckPerms on NeoForge 1.21.1 (fixed in 5.4.150, see LuckPerms issues 4097 and 4016)
        if (low.contains("luckperms") || low.contains("me.lucko")) {
            String version = mod != null && mod.toLowerCase(Locale.ROOT).contains("luckperms") ? version(mod) : null;
            boolean old = version != null && older(version, 5, 4, 150);
            // That bug is known on 1.21.1 only: on another game version, say nothing about it
            Matcher game = GAME_VERSION.matcher(trace);
            String mc = game.find() ? game.group(1) : null;
            boolean known = mc == null || mc.equals("1.21.1");
            return new Finding(Severity.ERROR, CODE, "LuckPerms broke the join",
                    what + " LuckPerms is in the error" + (version != null ? " (version " + version + ")" : "") + "."
                            + (known ? " Older LuckPerms builds (before 5.4.150) do this on NeoForge 1.21.1." : "")
                            + err,
                    known && (old || version == null)
                            ? "Update LuckPerms to 5.4.150 or newer (the 1.21.4 build also works on 1.21.1),"
                            + " or remove it until then."
                            : "Update LuckPerms, and if it keeps happening report it on LuckPerms' GitHub with this log.",
                    mod == null ? List.of() : List.of(mod));
        }
        // 2. FTB Essentials / FTB Library player data (FTB-Mods-Issues 1946, 1990)
        if (low.contains("ftbessentials") || low.contains("ftb-essentials") || low.contains("dev.ftb.mods")) {
            boolean essentials = low.contains("ftbessentials") || low.contains("ftb-essentials");
            String name = essentials ? "FTB Essentials" : "FTB Library";
            return new Finding(Severity.ERROR, CODE, name + " broke the join",
                    what + " " + name + " is in the error: usually its saved data for this player can't be read." + err,
                    "Update " + name + (essentials ? " and FTB Library" : "") + ". If it keeps happening, move this"
                            + " player's file out of the world's ftbessentials/playerdata folder (keep a copy). It's"
                            + " made again on the next join; homes and warps for this player start fresh.",
                    mod == null ? List.of() : List.of(mod));
        }
        // 3. The player's own save file is broken
        if (low.contains("nbtio") || low.contains("eofexception") || low.contains("zipexception")
                || low.contains("not in gzip format") || low.contains("playerdatastorage")
                || low.contains("failed to load player data")) {
            return new Finding(Severity.ERROR, CODE, "The player's save file is broken",
                    what + " Their save file in the world can't be read (it may have been cut off when the game"
                            + " or server stopped)." + err,
                    "Back up the world first. Then in the world's playerdata folder, find the player's <uuid>.dat:"
                            + " rename it to <uuid>.dat.broken and rename <uuid>.dat_old to <uuid>.dat. That's their save"
                            + " from a little earlier. No .dat_old? Then the player starts fresh without the broken file.",
                    List.of());
        }
        // 4. Some other mod in the error
        if (mod != null) {
            String name = name(mod);
            return new Finding(Severity.ERROR, CODE, name + " broke the join",
                    what + " " + name + (mod.endsWith(".jar") ? " (" + mod + ")" : "")
                            + " is the first mod in the error, so it's the most likely cause." + err,
                    "Update " + name + ". If that doesn't help, try once without it, and report the error to its author"
                            + " (/checkengine export makes a file with the log).",
                    mod.endsWith(".jar") ? List.of(mod) : List.of());
        }
        return new Finding(Severity.ERROR, CODE, "The join failed: Invalid player data",
                what + " The log doesn't name a mod." + err,
                "Run /checkengine export and send the file to the pack maker or whoever helps you; the log in it shows"
                        + " the full error.", List.of());
    }

    private static String shorten(String s) {
        return s.length() > 220 ? s.substring(0, 217) + "..." : s;
    }

    /** "LuckPerms-NeoForge-5.4.140.jar" -> "LuckPerms", "create-1.21.1-6.0.10.jar" -> "create"; a mod id stays as it is. */
    static String name(String mod) {
        if (!mod.endsWith(".jar")) return mod;
        String base = mod.substring(0, mod.length() - 4);
        StringBuilder out = new StringBuilder();
        for (String part : base.split("[-_+ ]")) {
            String low = part.toLowerCase(Locale.ROOT);
            if (part.isEmpty() || Character.isDigit(part.charAt(0)) || low.matches("v[0-9].*|mc[0-9].*|neoforge|forge|fabric|quilt|neo|universal|all")) {
                if (out.length() > 0) break;
                continue;
            }
            if (out.length() > 0) out.append(' ');
            out.append(part);
        }
        return out.length() == 0 ? base : out.toString();
    }

    /** "LuckPerms-NeoForge-5.4.140.jar" -> "5.4.140" */
    static String version(String jar) {
        Matcher m = VERSION.matcher(jar);
        return m.find() ? m.group() : null;
    }

    static boolean older(String version, int major, int minor, int patch) {
        Matcher m = VERSION.matcher(version);
        if (!m.find()) return false;
        int a = Integer.parseInt(m.group(1)), b = Integer.parseInt(m.group(2)), c = Integer.parseInt(m.group(3));
        if (a != major) return a < major;
        if (b != minor) return b < minor;
        return c < patch;
    }
}
