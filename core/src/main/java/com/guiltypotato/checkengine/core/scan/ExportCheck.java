package com.guiltypotato.checkengine.core.scan;

import com.guiltypotato.checkengine.core.scan.Finding.Severity;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Checks a pack export (CurseForge .zip, Modrinth .mrpack) before it's uploaded: the pack maker's own files that
 * shouldn't ship to every player (server list, game settings, worlds, screenshots, logs, map data...), mod jars bundled
 * inside, and the pack maker's Windows user name in configs. Every pack maker ships one of these by accident once.
 */
public final class ExportCheck {
    public static final String CODE = "export";
    private static final Pattern USER_PATH = Pattern.compile("[A-Za-z]:(?:\\\\\\\\|\\\\|/)+Users(?:\\\\\\\\|\\\\|/)+([^\\\\/\"'\\s]+)");
    private static final long MAX_TEXT = 1024 * 1024;

    /** @param kind what the files are, for the finding title; @param why what happens if they ship */
    private record Personal(String pattern, String kind, String why) {}

    /** Paths inside the overrides folder that are the pack maker's, not the pack's. */
    private static final List<Personal> PERSONAL = List.of(
            new Personal("servers.dat", "your server list", "Every player gets your list of servers (and their addresses)."),
            new Personal("options.txt", "your game settings",
                    "Every player gets your keybinds, video settings, volume and language. To ship default settings on "
                            + "purpose, use a mod made for it (like Default Options or Yosbr) instead."),
            new Personal("optionsof.txt", "your OptiFine settings", "Every player gets your OptiFine settings."),
            new Personal("optionsshaders.txt", "your shader settings", "Every player gets your shader settings."),
            new Personal("usercache.json", "names of players you've seen", "It lists players your game has met."),
            new Personal("usernamecache.json", "names of players you've seen", "It lists players your game has met."),
            new Personal("command_history.txt", "your command history", "Every command you typed in game."),
            new Personal("saves/", "your worlds", "Your single-player worlds would download with the pack."),
            new Personal("screenshots/", "your screenshots", "Your screenshots would download with the pack."),
            new Personal("logs/", "your game logs", "Logs have your user name and PC paths in them."),
            new Personal("crash-reports/", "your crash reports", "Crash reports have your user name and PC details."),
            new Personal("journeymap/data/", "your JourneyMap map", "Your explored map and waypoints."),
            new Personal("xaeroworldmap/", "your Xaero's world map", "Your explored map."),
            new Personal("xaerowaypoints/", "your Xaero's waypoints", "Your waypoints."),
            new Personal("xaerominimap/", "your Xaero's minimap data", "Your minimap data."),
            new Personal("backups/", "your backups", "Old world or config backups."),
            new Personal("checkengine/", "Check Engine's reports about your PC", "They're about your game, not the pack. "
                    + "Ship config/checkengine-pack.properties instead (it's in config/)."),
            new Personal(".mixin.out/", "debug output", "Debug files from your game."),
            new Personal("debug/", "debug output", "Debug files from your game."),
            new Personal("hs_err_pid", "Java crash files", "Crash files with your PC's details."));

    private ExportCheck() {}

    /** True for the files Check Pack.bat should treat as an export. */
    public static boolean isExport(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return n.endsWith(".zip") || n.endsWith(".mrpack");
    }

    public static List<Finding> check(Path archive) throws IOException {
        List<Finding> out = new ArrayList<>();
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            boolean curseForge = zip.getEntry("manifest.json") != null;
            boolean modrinth = zip.getEntry("modrinth.index.json") != null;
            if (!curseForge && !modrinth) {
                out.add(new Finding(Severity.WARNING, CODE, "Not a CurseForge or Modrinth export",
                        "There's no manifest.json (CurseForge) or modrinth.index.json (Modrinth) in this zip.",
                        "In CurseForge: three dots next to Play > Export Profile. In Modrinth: the pack's \"...\" > "
                                + "Export modpack.", List.of()));
                return out;
            }
            List<String> overrides = new ArrayList<>();
            long size = 0;
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String name = e.getName().replace('\\', '/');
                String rest = stripOverrides(name);
                if (rest == null || e.isDirectory()) continue;
                overrides.add(rest);
                size += Math.max(0, e.getSize());
            }

            // The pack maker's own files.
            for (Personal p : PERSONAL) {
                List<String> hits = overrides.stream().filter(f -> matches(f, p.pattern())).toList();
                if (hits.isEmpty()) continue;
                out.add(new Finding(Severity.WARNING, CODE, "The export has " + p.kind(),
                        p.why() + " Found: " + sample(hits),
                        "Export again and untick it in the export window.", List.copyOf(hits)));
            }

            // Mod jars bundled instead of linked.
            List<String> jars = overrides.stream().filter(f -> f.toLowerCase(Locale.ROOT).startsWith("mods/")
                    && f.toLowerCase(Locale.ROOT).endsWith(".jar")).toList();
            if (!jars.isEmpty()) {
                out.add(new Finding(Severity.WARNING, CODE, jars.size() + " mod jar" + (jars.size() == 1 ? " is" : "s are")
                        + " packed inside the export",
                        "Exports normally only list mods, and players' launchers download them. "
                                + (curseForge ? "CurseForge may reject the upload unless these mods can't be found on "
                                + "CurseForge and their licenses allow it. " : "Check each mod's license allows it. ")
                                + "Found: " + sample(jars),
                        "Install those mods from the launcher's own mod list, then export again.", List.copyOf(jars)));
            }

            // The pack maker's Windows user name in configs (paths like C:\Users\Name\...).
            Set<String> names = new LinkedHashSet<>();
            List<String> withName = new ArrayList<>();
            for (String f : overrides) {
                if (!f.matches("(?i).*\\.(toml|json|json5|cfg|txt|properties|snbt|yml|yaml|ini|js|conf)$")) continue;
                ZipEntry e = zip.getEntry(entryName(zip, f));
                if (e == null || e.getSize() > MAX_TEXT) continue;
                try (InputStream in = zip.getInputStream(e)) {
                    Matcher m = USER_PATH.matcher(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                    boolean found = false;
                    while (m.find()) {
                        String user = m.group(1);
                        if (user.equalsIgnoreCase("Public") || user.equalsIgnoreCase("Default")) continue;
                        names.add(user);
                        found = true;
                    }
                    if (found) withName.add(f);
                }
            }
            if (!withName.isEmpty()) {
                out.add(new Finding(Severity.WARNING, CODE, "Your Windows user name is in " + withName.size() + " config file"
                        + (withName.size() == 1 ? "" : "s"),
                        "Paths like C:\\Users\\" + names.iterator().next() + "\\... point at your PC: they show your user "
                                + "name and won't work on players' PCs. Found in: " + sample(withName),
                        "Open those files and remove the paths, or reset those settings to their defaults.",
                        List.copyOf(withName)));
            }

            if (size > 200L * 1024 * 1024) {
                out.add(new Finding(Severity.INFO, CODE, "The export is big (" + size / (1024 * 1024) + " MB of files)",
                        "Everything besides the mods is downloaded by every player.",
                        "Check for leftovers: worlds, resource packs you don't need, backups.", List.of()));
            }
        }
        return out;
    }

    /** "overrides/config/x.toml" -> "config/x.toml"; null when it isn't in an overrides folder. */
    private static String stripOverrides(String name) {
        for (String prefix : List.of("overrides/", "client-overrides/", "server-overrides/")) {
            if (name.startsWith(prefix)) return name.substring(prefix.length());
        }
        return null;
    }

    private static String entryName(ZipFile zip, String rest) {
        for (String prefix : List.of("overrides/", "client-overrides/", "server-overrides/")) {
            if (zip.getEntry(prefix + rest) != null) return prefix + rest;
        }
        return rest;
    }

    private static boolean matches(String file, String pattern) {
        String f = file.toLowerCase(Locale.ROOT);
        if (pattern.endsWith("/")) return f.startsWith(pattern);
        if (pattern.equals("hs_err_pid")) return f.startsWith("hs_err_pid");
        return f.equals(pattern);
    }

    private static String sample(List<String> files) {
        List<String> top = files.stream().limit(3).toList();
        return String.join(", ", top) + (files.size() > 3 ? " and " + (files.size() - 3) + " more" : "") + ".";
    }

    /** Plain text for Check Pack.bat. */
    public static String toText(Path archive, List<Finding> findings) {
        StringBuilder sb = new StringBuilder("Export check: " + archive.getFileName() + "\n\n");
        if (findings.isEmpty()) {
            return sb.append("Looks clean: none of your own files (server list, settings, worlds, logs...) are in it.\n")
                    .toString();
        }
        for (Finding f : findings) {
            sb.append("- ").append(f.title()).append('\n').append("  ").append(f.detail()).append('\n');
            if (f.fix() != null) sb.append("  Fix: ").append(f.fix()).append('\n');
            sb.append('\n');
        }
        return sb.toString();
    }
}
