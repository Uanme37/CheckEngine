package com.guiltypotato.checkengine.core.scan;

import com.guiltypotato.checkengine.core.scan.Finding.Severity;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A mod was updated and its settings file changed at the same time: some updates write a fresh file and the pack's
 * own settings are back to default. Says which values changed and keeps a copy of the old file, because the config
 * checkpoint it comes from is replaced after the next good launch.
 */
public final class SettingsReset {
    public static final String CODE = "settings-reset";
    /** Copies of the settings files from before the update, next to the other Check Engine files. */
    public static final String FOLDER = "settings-before-update";
    private static final int SHOW = 5;
    /** "key = value", "key: value", "\"key\": value," with an optional [section] above it (TOML, properties, JSON, cfg). */
    private static final Pattern LINE = Pattern.compile("^\\s*\"?([A-Za-z0-9_.\\- ]+?)\"?\\s*[=:]\\s*(.*?)\\s*,?\\s*$");
    private static final Pattern SECTION = Pattern.compile("^\\s*\\[+\\s*([^\\]]+?)\\s*\\]+\\s*$");

    private SettingsReset() {}

    public static List<Finding> check(Changes changes, Path gameDir, Path checkEngineFolder) {
        List<Finding> out = new ArrayList<>();
        if (changes == null || gameDir == null) return out;
        List<Changes.Change> updated = changes.changes().stream()
                .filter(c -> c.type() == Changes.Type.UPDATED && !c.downgrade() && c.id().length() >= 3
                        && !c.id().equals("minecraft") && !c.id().equals("loader")).toList();
        List<String> configs = changes.changes().stream()
                .filter(c -> c.type() == Changes.Type.CONFIG && "changed".equals(c.to())).map(Changes.Change::name).toList();
        if (updated.isEmpty() || configs.isEmpty()) return out;
        for (Changes.Change mod : updated) {
            for (String path : configs) {
                if (!belongsTo(path, mod.id())) continue;
                Path old = ConfigCheckpoint.savedCopy(checkEngineFolder, path);
                Path now = gameDir.resolve(path);
                List<String> diffs = changedValues(read(old), read(now));
                if (diffs.isEmpty()) continue; // only new settings were added: a normal update
                String kept = keep(old, checkEngineFolder, path);
                StringBuilder detail = new StringBuilder(mod.name() + " went from " + mod.from() + " to " + mod.to()
                        + " and " + diffs.size() + (diffs.size() == 1 ? " setting" : " settings") + " in " + path
                        + " changed with it:\n");
                for (int i = 0; i < Math.min(SHOW, diffs.size()); i++) detail.append("  ").append(diffs.get(i)).append('\n');
                if (diffs.size() > SHOW) detail.append("  and ").append(diffs.size() - SHOW).append(" more\n");
                detail.append("If you didn't change these yourself, the update probably put them back to default.");
                out.add(new Finding(Severity.WARNING, CODE, mod.name() + " settings may be back to default",
                        detail.toString().strip(),
                        kept == null ? "Set them again in " + path + "."
                                : "Your old file is saved as checkengine/" + kept + ": copy the values you want back "
                                        + "into " + path + ".",
                        List.of()));
            }
        }
        return out;
    }

    /** config/create-client.toml, config/create/server.toml, config/Create.cfg all belong to "create". */
    static boolean belongsTo(String path, String modId) {
        String p = flat(path.replace('\\', '/'));
        String id = flat(modId);
        if (id.isEmpty()) return false;
        String[] parts = p.split("/");
        for (int i = 1; i < parts.length; i++) {
            String part = parts[i];
            if (i < parts.length - 1 ? part.equals(id) : part.startsWith(id + ".") || part.startsWith(id + "client")
                    || part.startsWith(id + "common") || part.startsWith(id + "server") || part.equals(id)) return true;
        }
        return false;
    }

    /** Lower case without - and _ ("create_sa" and "create-sa" are the same). */
    private static String flat(String s) {
        return s.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
    }

    /** "section.key: old -> new" for every setting in both files whose value differs. */
    static List<String> changedValues(String before, String after) {
        if (before == null || after == null) return List.of();
        Map<String, String> a = values(before);
        Map<String, String> b = values(after);
        List<String> out = new ArrayList<>();
        a.forEach((key, value) -> {
            String now = b.get(key);
            if (now != null && !now.equals(value)) out.add(key + ": " + value + " -> " + now);
        });
        return out;
    }

    static Map<String, String> values(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        String section = "";
        for (String raw : text.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//") || line.startsWith(";")) continue;
            Matcher s = SECTION.matcher(line);
            if (s.matches()) {
                section = s.group(1).strip() + ".";
                continue;
            }
            Matcher m = LINE.matcher(line);
            if (!m.matches()) continue;
            String value = m.group(2);
            if (value.isEmpty() || value.equals("{") || value.equals("[")) continue; // a block, not a value
            int comment = value.indexOf(" #");
            if (comment > 0 && !value.startsWith("\"")) value = value.substring(0, comment).strip();
            out.putIfAbsent(section + m.group(1).strip(), value);
        }
        return out;
    }

    private static String read(Path p) {
        try {
            return Files.isRegularFile(p) && Files.size(p) < 2L * 1024 * 1024 ? Files.readString(p, StandardCharsets.UTF_8) : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Copies the old file to checkengine/settings-before-update/; returns that path relative to checkengine/, or null. */
    private static String keep(Path old, Path checkEngineFolder, String path) {
        String rel = FOLDER + "/" + path.replace('\\', '/');
        try {
            Path to = checkEngineFolder.resolve(rel);
            Files.createDirectories(to.getParent());
            Files.copy(old, to, StandardCopyOption.REPLACE_EXISTING);
            return rel;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
