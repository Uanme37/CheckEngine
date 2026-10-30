package com.guiltypotato.checkengine.core.scan;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Who the pack is, for Check Engine's screens: "Test Pack won't start", and where players can get help.
 * The pack maker sets both in config/checkengine-pack.properties (shipped with the pack's config folder). Without a
 * name there, it's read from the launcher's instance (CurseForge's minecraftinstance.json, Prism's instance.cfg).
 *
 * @param name        the pack's name, or null if unknown
 * @param supportLink an http(s) link for help (Discord, GitHub issues, CurseForge page...), or null
 * @param recommendedMb the memory the pack maker recommends (recommended_ram=10G), or 0
 */
public record PackInfo(String name, String supportLink, long recommendedMb, boolean quiet, List<String> hide) {
    public static final String FILE = "checkengine-pack.properties";
    /** Longer names don't fit the window's headline. */
    private static final int MAX_HEADLINE_NAME = 26;

    public PackInfo(String name, String supportLink) {
        this(name, supportLink, 0);
    }

    public PackInfo(String name, String supportLink, long recommendedMb) {
        this(name, supportLink, recommendedMb, false, List.of());
    }

    /**
     * Quiet mode: whether players don't get to see this finding. Problems that stop the game always show; with
     * quiet=true no warning does; hide_warnings hides the ones whose kind ("same-job-mods") or title matches.
     */
    public boolean hides(Finding f) {
        if (f.severity() == Finding.Severity.ERROR) return false;
        if (quiet) return true;
        String title = f.title().toLowerCase(Locale.ROOT);
        return hide.stream().anyMatch(h -> f.code().equalsIgnoreCase(h) || title.contains(h.toLowerCase(Locale.ROOT)));
    }

    /** The findings players get to see (see {@link #hides}). */
    public List<Finding> visible(List<Finding> findings) {
        return findings.stream().filter(f -> !hides(f)).toList();
    }

    /**
     * Whether a whole screen is switched off: "settings" (the memory and Java windows, also off with quiet=true) or
     * "update-notes" (the "pack was updated" screen).
     */
    public boolean hidesScreen(String screen) {
        return (quiet && screen.equals("settings")) || hide.stream().anyMatch(h -> h.equalsIgnoreCase(screen));
    }

    /**
     * The pack maker's default for Go back: go_back_configs=false makes "Mods only" the first choice (the pack's own
     * config edits stay), true or empty "Mods and configs".
     */
    public static boolean goBackConfigsByDefault(Path gameDir) {
        if (gameDir == null) return true;
        String v = settings(gameDir).getProperty("go_back_configs", "true").strip();
        return !"false".equalsIgnoreCase(v) && !"mods".equalsIgnoreCase(v);
    }

    /** The pack maker's default for Check Engine's title screen button: title_button=hide (or false) turns it off. */
    public static boolean titleButtonByDefault(Path gameDir) {
        if (gameDir == null) return true;
        String v = settings(gameDir).getProperty("title_button", "show").strip();
        return !"hide".equalsIgnoreCase(v) && !"false".equalsIgnoreCase(v);
    }

    /**
     * Where the pack maker wants Check Engine's title button: title_button_pos = auto (beside Accessibility, or the
     * bottom-left corner on a custom title screen), bottom_left, bottom_right, top_left, top_right, or x,y.
     */
    public static String titleButtonPos(Path gameDir) {
        if (gameDir == null) return "auto";
        String v = settings(gameDir).getProperty("title_button_pos", "auto").strip();
        return v.isEmpty() ? "auto" : v;
    }

    public static PackInfo load(Path gameDir) {
        if (gameDir == null) return new PackInfo(null, null, 0);
        Properties p = settings(gameDir);
        String name = blankToNull(p.getProperty("pack_name"));
        if (name == null) name = withoutCopyNumber(curseForgeName(gameDir));
        if (name == null) name = withoutCopyNumber(prismName(gameDir));
        String link = blankToNull(p.getProperty("support_link"));
        if (link != null && !link.matches("(?i)https?://\\S+")) link = null;
        List<String> hide = java.util.Arrays.stream(p.getProperty("hide_warnings", "").split(","))
                .map(String::strip).filter(h -> !h.isEmpty()).toList();
        return new PackInfo(name, link, memoryMb(p.getProperty("recommended_ram")),
                Boolean.parseBoolean(p.getProperty("quiet", "false").strip()), hide);
    }

    /** "10G", "10 GB", "10240M", "10240" (MB) -> MB; 0 when empty or unreadable. A bare number under 100 means GB. */
    static long memoryMb(String text) {
        String t = blankToNull(text);
        if (t == null) return 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)^([0-9]+(?:[.][0-9]+)?)\\s*(gb?|mb?)?$")
                .matcher(t);
        if (!m.find()) return 0;
        double n = Double.parseDouble(m.group(1));
        boolean gb = m.group(2) != null ? m.group(2).toLowerCase(Locale.ROOT).startsWith("g") : n < 100;
        return Math.round(gb ? n * 1024 : n);
    }

    /** The name in upper case for a headline ("TEST PACK"), or null when unknown or too long. */
    public String headlineName() {
        return name == null || name.length() > MAX_HEADLINE_NAME ? null : name.toUpperCase(Locale.ROOT);
    }

    /** config/checkengine-pack.properties; written empty the first time, so pack makers can find it. */
    private static Properties settings(Path gameDir) {
        Path file = gameDir.resolve("config").resolve(FILE);
        Properties p = new Properties();
        if (Files.isRegularFile(file)) {
            try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                p.load(in);
            } catch (IOException | IllegalArgumentException ignored) {
                // unreadable: use the defaults
            }
            return p;
        }
        if (!Files.isDirectory(gameDir.resolve("config"))) return p;
        try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            out.write("# Check Engine: for pack makers. Ship this file in your pack's config folder.\n"
                    + "# pack_name: shown on Check Engine's screens (\"My Pack won't start\"). Empty = the launcher's name.\n"
                    + "# support_link: where players get help (Discord invite, GitHub issues, CurseForge page). Empty = no\n"
                    + "#   help link.\n"
                    + "# recommended_ram: the memory your pack needs, e.g. 10G. Players who give it far less get a\n"
                    + "#   warning. Empty = CurseForge's recommended RAM, or Check Engine's own measurement.\n"
                    + "# quiet: true = players only see Check Engine when something stops the game or it crashed.\n"
                    + "# hide_warnings: warnings players don't need, comma-separated: a kind (same-job-mods,\n"
                    + "#   duplicate-mod, downgraded-mod, settings, update-notes) or words from the title (Xaero's Minimap).\n"
                    + "# title_button: show or hide the Pit Stop button on the title screen (players can still change it).\n"
                    + "# title_button_pos: auto (beside Accessibility), bottom_left, bottom_right, top_left, top_right, or\n"
                    + "#   x,y. With FancyMenu you can also move it in FancyMenu's editor.\n"
                    + "# go_back_configs: what Go back offers first. true = mods and configs, false = mods only (your\n"
                    + "#   pack's own config edits stay). Players can always pick the other one.\n"
                    + "pack_name=\n"
                    + "support_link=\n"
                    + "recommended_ram=\n"
                    + "quiet=false\n"
                    + "hide_warnings=\n"
                    + "title_button=show\n"
                    + "title_button_pos=auto\n"
                    + "go_back_configs=true\n");
        } catch (IOException ignored) {
            // read-only folder: fine
        }
        return p;
    }

    /** The top-level "name" in CurseForge's minecraftinstance.json (not the loader's or a mod's). */
    static String curseForgeName(Path gameDir) {
        Path file = gameDir.resolve("minecraftinstance.json");
        if (!Files.isRegularFile(file)) return null;
        try {
            return topLevelString(Files.readString(file, StandardCharsets.UTF_8), "name");
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Prism / MultiMC: the game folder is .minecraft (or minecraft) inside the instance, next to instance.cfg. */
    static String prismName(Path gameDir) {
        Path parent = gameDir.toAbsolutePath().getParent();
        if (parent == null) return null;
        Path cfg = parent.resolve("instance.cfg");
        if (!Files.isRegularFile(cfg)) return null;
        try {
            for (String line : Files.readAllLines(cfg, StandardCharsets.UTF_8)) {
                if (line.startsWith("name=")) return blankToNull(line.substring(5));
            }
        } catch (IOException | RuntimeException ignored) {
            // no name
        }
        return null;
    }

    /** A string value of {@code key} directly inside the outermost JSON object, without a JSON library. */
    static String topLevelString(String json, String key) {
        int depth = 0;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{' || c == '[') depth++;
            else if (c == '}' || c == ']') depth--;
            else if (c == '"') {
                int end = stringEnd(json, i);
                String text = json.substring(i + 1, end);
                i = end;
                if (depth != 1 || !text.equals(key)) continue;
                int j = i + 1;
                while (j < json.length() && (Character.isWhitespace(json.charAt(j)) || json.charAt(j) == ':')) j++;
                if (j >= json.length() || json.charAt(j) != '"') continue; // a key elsewhere, or not a string
                return blankToNull(unescape(json.substring(j + 1, stringEnd(json, j))));
            }
        }
        return null;
    }

    private static int stringEnd(String s, int open) {
        for (int i = open + 1; i < s.length(); i++) {
            if (s.charAt(i) == '\\') i++;
            else if (s.charAt(i) == '"') return i;
        }
        return s.length();
    }

    private static String unescape(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                out.append(c);
                continue;
            }
            char n = s.charAt(++i);
            if (n == 'u' && i + 4 < s.length()) {
                out.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                i += 4;
            } else {
                out.append(n == 'n' || n == 't' ? ' ' : n);
            }
        }
        return out.toString();
    }

    /** "Test Pack (1)": the launcher's second copy of a pack, still the same pack. */
    static String withoutCopyNumber(String name) {
        return name == null ? null : blankToNull(name.replaceFirst("\\s*\\(\\d+\\)$", ""));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
