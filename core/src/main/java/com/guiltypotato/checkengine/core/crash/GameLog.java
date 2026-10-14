package com.guiltypotato.checkengine.core.crash;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/**
 * The game log that goes with a crash report. A crash report only shows the last error; the log also has the one
 * before it, e.g. the mod that failed to load and left architectury "not available".
 */
public final class GameLog {
    /** How many log files to look through (latest.log and the newest .log.gz files). */
    private static final int MAX_FILES = 8;
    /** Logs bigger than this are cut: the loading part we want is at the start. */
    private static final int MAX_BYTES = 40 * 1024 * 1024;

    private GameLog() {}

    /**
     * The log of the session that wrote {@code crashFile}: the one that says it saved that crash report. Null when
     * none does (an old crash, a silent crash, or logs cleaned up).
     */
    public static String forCrash(Path gameDir, Path crashFile) {
        String name = crashFile.getFileName().toString();
        if (!name.startsWith("crash-")) return null;
        Path logs = gameDir.resolve("logs");
        if (!Files.isDirectory(logs)) return null;
        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.list(logs)) {
            s.filter(p -> {
                String f = p.getFileName().toString();
                return f.equals("latest.log") || f.endsWith(".log.gz") && !f.startsWith("debug");
            }).sorted(Comparator.comparingLong(GameLog::modified).reversed()).limit(MAX_FILES).forEach(files::add);
        } catch (IOException e) {
            return null;
        }
        for (Path f : files) {
            String text = read(f);
            if (text != null && text.contains(name)) return text;
        }
        return null;
    }

    private static String read(Path f) {
        try (InputStream raw = Files.newInputStream(f);
                InputStream in = f.getFileName().toString().endsWith(".gz") ? new GZIPInputStream(raw) : raw) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0 && out.size() < MAX_BYTES) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private static long modified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }
}
