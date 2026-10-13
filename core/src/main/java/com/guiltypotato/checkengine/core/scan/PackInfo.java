package com.guiltypotato.checkengine.core.scan;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/**
 * Who the pack is, for Check Engine's screens: "Beyond the Crank won't start", and where players can get help.
 * The pack maker sets both in config/checkengine-pack.properties (shipped with the pack's config folder). Without a
 * name there, it's read from the launcher's instance (CurseForge's minecraftinstance.json, Prism's instance.cfg).
 *
 * @param name        the pack's name, or null if unknown
 * @param supportLink an http(s) link for help (Discord, GitHub issues, CurseForge page...), or null
 */
public record PackInfo(String name, String supportLink) {
    public static final String FILE = "checkengine-pack.properties";
    /** Longer names don't fit the window's headline. */
    private static final int MAX_HEADLINE_NAME = 26;

    public static PackInfo load(Path gameDir) {
        if (gameDir == null) return new PackInfo(null, null);
        Properties p = settings(gameDir);
        String name = blankToNull(p.getProperty("pack_name"));
        if (name == null) name = withoutCopyNumber(curseForgeName(gameDir));
        if (name == null) name = withoutCopyNumber(prismName(gameDir));
        String link = blankToNull(p.getProperty("support_link"));
        if (link != null && !link.matches("(?i)https?://\\S+")) link = null;
        return new PackInfo(name, link);
    }

    /** The name in upper case for a headline ("BEYOND THE CRANK"), or null when unknown or too long. */
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
                    + "pack_name=\n"
                    + "support_link=\n");
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

    /** "Beyond The Crank (1)": the launcher's second copy of a pack, still the same pack. */
    static String withoutCopyNumber(String name) {
        return name == null ? null : blankToNull(name.replaceFirst("\\s*\\(\\d+\\)$", ""));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
