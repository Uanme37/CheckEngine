package com.guiltypotato.checkengine.core.scan;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.CRC32;

/**
 * The pack's settings as they were on the last launch that worked: a copy of every config file (config/,
 * defaultconfigs/, kubejs/), kept in checkengine/config-checkpoint/. One mistyped value in a config can break a whole
 * pack while every mod is fine, so this says which configs changed since it worked, and puts them back on request.
 * Only text files are kept (no images or archives), and only the ones that changed are copied again.
 */
public final class ConfigCheckpoint {
    static final String FOLDER = "config-checkpoint";
    private static final String INDEX = "index.properties";
    private static final List<String> ROOTS = List.of("config", "defaultconfigs", "kubejs");
    private static final long MAX_FILE = 2L * 1024 * 1024;

    public enum Kind { CHANGED, ADDED, REMOVED }

    /** @param path relative to the game folder, with forward slashes ("config/create-server.toml") */
    public record Change(Kind kind, String path, long modified) {
        public String describe() {
            return switch (kind) {
                case CHANGED -> "changed " + path;
                case ADDED -> "added " + path;
                case REMOVED -> "removed " + path;
            };
        }
    }

    private ConfigCheckpoint() {}

    /** Called when a launch worked: remembers every config file as it is now. */
    public static void save(Path gameDir, Path checkEngineFolder) throws IOException {
        Path store = checkEngineFolder.resolve(FOLDER);
        Map<String, String> old = index(store);
        Map<String, String> now = new TreeMap<>();
        for (Map.Entry<String, Path> e : files(gameDir).entrySet()) {
            String sum = checksum(e.getValue());
            now.put(e.getKey(), sum);
            Path copy = store.resolve("files").resolve(e.getKey());
            if (sum.equals(old.get(e.getKey())) && Files.isRegularFile(copy)) continue;
            Files.createDirectories(copy.getParent());
            Files.copy(e.getValue(), copy, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        }
        Properties p = new Properties();
        p.putAll(now);
        p.setProperty("#saved", LocalDateTime.now().withNano(0).toString());
        Files.createDirectories(store);
        try (Writer out = Files.newBufferedWriter(store.resolve(INDEX), StandardCharsets.UTF_8)) {
            p.store(out, "Check Engine: config files of the last launch that worked (copies in files/)");
        }
    }

    /** Which config files differ from the last launch that worked, newest change first. Empty when there's no checkpoint. */
    public static List<Change> changes(Path gameDir, Path checkEngineFolder) {
        Map<String, String> old = index(checkEngineFolder.resolve(FOLDER));
        if (old.isEmpty()) return List.of();
        List<Change> out = new ArrayList<>();
        Map<String, Path> now = files(gameDir);
        for (Map.Entry<String, Path> e : now.entrySet()) {
            String before = old.get(e.getKey());
            if (before == null) {
                out.add(new Change(Kind.ADDED, e.getKey(), modified(e.getValue())));
            } else {
                try {
                    if (!before.equals(checksum(e.getValue()))) {
                        out.add(new Change(Kind.CHANGED, e.getKey(), modified(e.getValue())));
                    }
                } catch (IOException ignored) {
                    // unreadable right now: can't tell
                }
            }
        }
        for (String path : old.keySet()) {
            if (!now.containsKey(path)) out.add(new Change(Kind.REMOVED, path, 0));
        }
        out.sort((a, b) -> Long.compare(b.modified(), a.modified()));
        return out;
    }

    /**
     * Puts back the configs of the last launch that worked. The current versions are moved to
     * checkengine/config-undo-&lt;date&gt;/ first (nothing is deleted). Returns that folder, or null when nothing changed.
     */
    public static Path restore(Path gameDir, Path checkEngineFolder) throws IOException {
        List<Change> changes = changes(gameDir, checkEngineFolder);
        if (changes.isEmpty()) return null;
        Path store = checkEngineFolder.resolve(FOLDER).resolve("files");
        Path undo = checkEngineFolder.resolve("config-undo-"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss")));
        for (Change c : changes) {
            Path live = gameDir.resolve(c.path());
            if (Files.isRegularFile(live)) {
                Path keep = undo.resolve(c.path());
                Files.createDirectories(keep.getParent());
                Files.move(live, keep, StandardCopyOption.REPLACE_EXISTING);
            }
            Path saved = store.resolve(c.path());
            if (c.kind() != Kind.ADDED && Files.isRegularFile(saved)) {
                Files.createDirectories(live.getParent());
                Files.copy(saved, live, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return undo;
    }

    /** Config text files by path relative to the game folder. */
    static Map<String, Path> files(Path gameDir) {
        Map<String, Path> out = new TreeMap<>();
        for (String root : ROOTS) {
            Path dir = gameDir.resolve(root);
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> s = Files.walk(dir)) {
                s.filter(Files::isRegularFile).filter(ConfigCheckpoint::isText).forEach(p ->
                        out.put(gameDir.relativize(p).toString().replace('\\', '/'), p));
            } catch (IOException | RuntimeException ignored) {
                // skip what can't be read
            }
        }
        return out;
    }

    private static boolean isText(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!n.matches(".*\\.(toml|json|json5|cfg|conf|properties|txt|snbt|nbt|yml|yaml|ini|js|ts|zs|csv|mcmeta)$")) {
            return false;
        }
        try {
            return Files.size(p) <= MAX_FILE;
        } catch (IOException e) {
            return false;
        }
    }

    private static String checksum(Path p) throws IOException {
        CRC32 crc = new CRC32();
        crc.update(Files.readAllBytes(p));
        return Long.toHexString(crc.getValue());
    }

    private static Map<String, String> index(Path store) {
        Map<String, String> out = new TreeMap<>();
        Path file = store.resolve(INDEX);
        if (!Files.isRegularFile(file)) return out;
        Properties p = new Properties();
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(in);
        } catch (IOException | IllegalArgumentException e) {
            return out;
        }
        for (String k : p.stringPropertyNames()) if (!k.startsWith("#")) out.put(k, p.getProperty(k));
        return out;
    }

    private static long modified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }
}
