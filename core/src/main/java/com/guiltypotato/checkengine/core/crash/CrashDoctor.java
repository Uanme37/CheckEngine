package com.guiltypotato.checkengine.core.crash;

import com.guiltypotato.checkengine.core.scan.Changes;
import com.guiltypotato.checkengine.core.scan.Finding;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * "Your last game crashed because X. Fix: Y." On startup, finds a crash report from the previous session (one
 * started before this launch, that hasn't been shown yet), explains it with {@link CrashTranslator}, and adds
 * what changed since the pack last worked. Old crash reports from before Check Engine was installed are ignored.
 */
public final class CrashDoctor {
    /** checkengine/crash-doctor.properties: when the last session started, and the last crash shown. */
    static final String STATE_FILE = "crash-doctor.properties";
    public static final String REPORT_FILE = "last-crash.txt";

    /**
     * The previous session's crash, explained.
     *
     * @param file      the crash report
     * @param when      when it happened, e.g. "2026-10-08 12:30"
     * @param explained what it says, translated
     * @param changes   what changed since the pack last worked, or null
     */
    public record LastCrash(Path file, String when, CrashTranslator.Explanation explained, Changes changes) {
        /** The most useful finding: what to show in one line. */
        public Finding main() {
            return explained.findings().get(0);
        }

        /** The same crash with what changed since the pack last worked (known once the pack scan is done). */
        public LastCrash withChanges(Changes changes) {
            return new LastCrash(file, when, explained, changes);
        }

        /** Saves checkengine/last-crash.txt, for the screen's "Open details" button. */
        public Path save(Path checkEngineFolder) throws IOException {
            Files.createDirectories(checkEngineFolder);
            Path out = checkEngineFolder.resolve(REPORT_FILE);
            Files.writeString(out, toText(), StandardCharsets.UTF_8);
            return out;
        }

        /** The text for checkengine/last-crash.txt. */
        public String toText() {
            StringBuilder sb = new StringBuilder("Check Engine: your last game crashed (" + when + ")\n");
            sb.append("Crash report: ").append(file).append("\n\n");
            if (changes != null && !changes.isEmpty()) {
                sb.append("Changed since the pack last worked (").append(changes.when()).append("):\n  ")
                        .append(changes.summary().replace("\n", "\n  ")).append("\n\n");
            }
            sb.append(explained.toText());
            return sb.toString();
        }
    }

    private CrashDoctor() {}

    /**
     * Call once per launch, as early as possible. Returns the previous session's unseen crash (or null), and records
     * that a new session started, so the next launch only looks at crashes from this one.
     */
    public static LastCrash checkAndStartSession(Path gameDir, Path checkEngineFolder) {
        Properties state = load(checkEngineFolder);
        LastCrash crash = null;
        String started = state.getProperty("session_started");
        if (started != null) {
            try {
                crash = find(gameDir, Long.parseLong(started), state.getProperty("last_shown"));
            } catch (RuntimeException e) {
                crash = null; // a crash we can't read is no reason to break startup
            }
        }
        state.setProperty("session_started", Long.toString(System.currentTimeMillis()));
        if (crash != null) state.setProperty("last_shown", crash.file().getFileName().toString());
        save(checkEngineFolder, state);
        return crash;
    }

    /** The newest crash report written since {@code sessionStart} that isn't {@code lastShown}, explained. */
    static LastCrash find(Path gameDir, long sessionStart, String lastShown) {
        Path dir = gameDir.resolve("crash-reports");
        if (!Files.isDirectory(dir)) return null;
        Path newest;
        try (Stream<Path> files = Files.list(dir)) {
            newest = files.filter(p -> p.getFileName().toString().endsWith(".txt"))
                    .filter(p -> modified(p) >= sessionStart)
                    .max(Comparator.comparingLong(CrashDoctor::modified)).orElse(null);
        } catch (IOException e) {
            return null;
        }
        if (newest == null || newest.getFileName().toString().equals(lastShown)) return null;
        String text;
        try {
            text = Files.readString(newest, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
        CrashTranslator.Explanation explained = CrashTranslator.translate(text);
        if (explained.findings().isEmpty()) return null;
        String when = LocalDateTime.ofInstant(Instant.ofEpochMilli(modified(newest)), ZoneId.systemDefault())
                .withSecond(0).withNano(0).toString().replace('T', ' ');
        return new LastCrash(newest, when, explained, null);
    }

    private static long modified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    private static Properties load(Path folder) {
        Properties p = new Properties();
        Path file = folder.resolve(STATE_FILE);
        if (Files.isRegularFile(file)) {
            try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                p.load(in);
            } catch (IOException | IllegalArgumentException ignored) {
                // start fresh
            }
        }
        return p;
    }

    private static void save(Path folder, Properties p) {
        try {
            Files.createDirectories(folder);
            try (Writer out = Files.newBufferedWriter(folder.resolve(STATE_FILE), StandardCharsets.UTF_8)) {
                p.store(out, "Check Engine: Crash Doctor");
            }
        } catch (IOException ignored) {
            // worst case the same crash is explained twice
        }
    }
}
