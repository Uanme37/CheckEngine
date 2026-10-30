package com.guiltypotato.checkengine.core.scan;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * The jars of the last launch that worked, kept as hard links in checkengine/lastgood/mods/. A hard link is a second
 * name for the same file: it takes no extra space while the mod is still in mods/, and when the launcher removes the
 * jar for an update, the old version lives on here, so "Go back" can put it back. Never a copy: if links can't be made
 * (another drive, a drive that has none, no rights), nothing is kept and one plain note says why.
 * Each kept jar's size and SHA-1 are written down: a jar changed in place (same file, new contents) no longer matches,
 * and isn't used.
 */
public final class KeptJars {
    public static final String FOLDER = "lastgood";
    static final String INDEX = "kept.properties";

    /** A kept jar's record: size, last-modified time and SHA-1 when it was kept. */
    record Kept(long size, long modified, String sha1) {}

    private KeptJars() {}

    /**
     * Links every jar in {@code modsFolder} into {@code into}/lastgood/mods (into = the pending folder, see
     * GoodLaunch). Returns null, or the note to show when nothing could be kept.
     */
    public static String keep(Path modsFolder, Path checkEngineFolder, Path into) throws IOException {
        Path store = into.resolve(FOLDER);
        Path mods = store.resolve("mods");
        GoodLaunch.clear(store);
        Files.createDirectories(mods);
        Map<String, Kept> before = index(checkEngineFolder.resolve(FOLDER));
        Map<String, Kept> now = new TreeMap<>();
        String note = null;
        List<Path> jars;
        try (Stream<Path> s = Files.list(modsFolder)) {
            jars = s.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .filter(Files::isRegularFile).sorted().toList();
        }
        for (Path jar : jars) {
            String name = jar.getFileName().toString();
            Path link = mods.resolve(name);
            try {
                Files.createLink(link, jar);
            } catch (UnsupportedOperationException e) {
                note = "Can't keep old versions here: this drive doesn't support it.";
                break;
            } catch (FileSystemException e) {
                note = why(e, jar, checkEngineFolder);
                if (note != null) break;
                continue; // just this jar (held open, odd name): the rest still get kept
            }
            long size = Files.size(jar);
            long modified = Files.getLastModifiedTime(jar).toMillis();
            Kept old = before.get(name);
            Path oldLink = checkEngineFolder.resolve(FOLDER).resolve("mods").resolve(name);
            // Same file as last time and untouched: no need to read it again (big packs have gigabytes of jars)
            String sha = old != null && old.size() == size && old.modified() == modified && Files.exists(oldLink)
                    && Files.isSameFile(oldLink, jar) ? old.sha1() : sha1(jar);
            now.put(name, new Kept(size, modified, sha));
        }
        if (note != null) GoodLaunch.clear(mods);
        Properties p = new Properties();
        now.forEach((n, k) -> p.setProperty(n, k.size() + "|" + k.modified() + "|" + k.sha1()));
        if (note != null) p.setProperty("#note", note);
        try (Writer out = Files.newBufferedWriter(store.resolve(INDEX), StandardCharsets.UTF_8)) {
            p.store(out, "Check Engine: jars of the last launch that worked, kept as hard links in mods/");
        }
        return note;
    }

    /** A note when links can't be made in this folder at all, or null when it was just this one jar. */
    private static String why(FileSystemException e, Path jar, Path checkEngineFolder) {
        try {
            if (!Files.getFileStore(jar).equals(Files.getFileStore(checkEngineFolder))) {
                return "Can't keep old versions here: Check Engine's folder is on another drive.";
            }
        } catch (IOException ignored) {
            // can't tell
        }
        if (e instanceof AccessDeniedException) return null; // the game holds this jar open: skip it
        String msg = String.valueOf(e.getReason()).toLowerCase(Locale.ROOT);
        if (msg.contains("not supported") || msg.contains("incorrect function")) {
            return "Can't keep old versions here: this drive doesn't support it.";
        }
        if (msg.contains("privilege") || msg.contains("permission")) {
            return "Can't keep old versions here: no rights to make links in this folder.";
        }
        return null;
    }

    /** The note from the last keep ("Check Engine's folder is on another drive"), or null. */
    public static String note(Path checkEngineFolder) {
        Path file = checkEngineFolder.resolve(FOLDER).resolve(INDEX);
        if (!Files.isRegularFile(file)) return null;
        Properties p = new Properties();
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(in);
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
        return p.getProperty("#note");
    }

    /**
     * The kept copy of {@code jarName}, or null when there's none or it was changed in place since it was kept (then
     * it's not the old version any more, so it must not be put back).
     */
    public static Path usable(Path checkEngineFolder, String jarName) {
        Path store = checkEngineFolder.resolve(FOLDER);
        Kept k = index(store).get(jarName);
        Path link = store.resolve("mods").resolve(jarName);
        if (k == null || !Files.isRegularFile(link)) return null;
        try {
            return Files.size(link) == k.size() && sha1(link).equals(k.sha1()) ? link : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** Moves the jars kept in {@code pending} in place of the old set (Check Engine's own links, not player files). */
    static void promote(Path pending, Path checkEngineFolder) throws IOException {
        Path from = pending.resolve(FOLDER);
        if (!Files.isRegularFile(from.resolve(INDEX))) return;
        Path to = checkEngineFolder.resolve(FOLDER);
        // One step: the old kept jars go aside, the new ones move in, and only then the old ones go. A start cut
        // short in between still has one complete set (the aside one is used again next time).
        Path aside = checkEngineFolder.resolve(FOLDER + "-old");
        GoodLaunch.clear(aside);
        if (Files.exists(to)) Files.move(to, aside, StandardCopyOption.ATOMIC_MOVE);
        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        GoodLaunch.clear(aside);
    }

    private static Map<String, Kept> index(Path store) {
        Map<String, Kept> out = new TreeMap<>();
        Path file = store.resolve(INDEX);
        if (!Files.isRegularFile(file)) return out;
        Properties p = new Properties();
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(in);
        } catch (IOException | IllegalArgumentException e) {
            return out;
        }
        for (String k : p.stringPropertyNames()) {
            if (k.startsWith("#")) continue;
            String[] parts = p.getProperty(k).split("\\|");
            if (parts.length < 3) continue;
            try {
                out.put(k, new Kept(Long.parseLong(parts[0]), Long.parseLong(parts[1]), parts[2]));
            } catch (NumberFormatException ignored) {
                // skip a broken line
            }
        }
        return out;
    }

    static String sha1(Path file) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        byte[] buf = new byte[1 << 16];
        try (InputStream in = Files.newInputStream(file)) {
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
        }
        return HexFormat.of().formatHex(md.digest());
    }
}
