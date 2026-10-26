package com.guiltypotato.checkengine.core.crash;

import com.guiltypotato.checkengine.core.model.ModInfo;
import com.guiltypotato.checkengine.core.model.ModJar;
import com.guiltypotato.checkengine.core.scan.Changes;
import com.guiltypotato.checkengine.core.scan.Finding;
import com.guiltypotato.checkengine.core.scan.Report;
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
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * "Your last game crashed because X. Fix: Y." On startup, finds a crash report from the previous session (one
 * started before this launch, that hasn't been shown yet), explains it with {@link CrashTranslator}, and adds
 * what changed since the pack last worked. On the first launch with Check Engine, a crash from the last 24 hours (from
 * before it was installed) is explained too.
 */
public final class CrashDoctor {
    /** checkengine/crash-doctor.properties: when the last session started, and the last crash shown. */
    static final String STATE_FILE = "crash-doctor.properties";
    public static final String REPORT_FILE = "last-crash.txt";
    /** The game log of the crashed session, saved for the "Game log" button. */
    public static final String LOG_FILE = "crash-log.txt";
    /** How far back the first launch with Check Engine looks for a crash from before it was installed. */
    static final long BEFORE_INSTALL_WINDOW = 24L * 60 * 60 * 1000;
    /** The crashes where Check Engine only knows where it happened, not why. */
    private static final java.util.Set<String> UNSURE = java.util.Set.of("crash", "unknown-crash");
    /** Crashes where the mod in the stack trace is a bystander: something else failed first. */
    private static final java.util.Set<String> NOT_AT_FAULT = java.util.Set.of("knock-on-crash",
            Finding.MISSING_DEPENDENCY);

    /** Whether the blamed mod is still the same since the crash (for a crash from before Check Engine). */
    public enum Since { GONE, CHANGED, SAME, UNKNOWN }

    /**
     * The previous session's crash, explained.
     *
     * @param file      the crash report
     * @param when      when it happened, e.g. "2026-10-08 12:30"
     * @param explained what it says, translated
     * @param changes   what changed since the pack last worked, or null
     * @param keyLines  the crash's own key lines, word for word
     * @param culprit   the id of the mod it happened in, or null
     * @param culpritVersion that mod's version in the crash report's mod list, or null
     * @param beforeInstall whether it happened before Check Engine was installed (first launch with it)
     */
    public record LastCrash(Path file, String when, CrashTranslator.Explanation explained, Changes changes,
                            List<CrashLines.KeyLine> keyLines, String culprit, String culpritVersion,
                            boolean beforeInstall) {
        /** The most useful finding: what to show in one line. */
        public Finding main() {
            return explained.findings().get(0);
        }

        /** The same crash with what changed since the pack last worked (known once the pack scan is done). */
        public LastCrash withChanges(Changes changes) {
            return new LastCrash(file, when, explained, changes, keyLines, culprit, culpritVersion, beforeInstall);
        }

        LastCrash asBeforeInstall() {
            return new LastCrash(file, when, explained, changes, keyLines, culprit, culpritVersion, true);
        }

        /**
         * Whether Check Engine knows what caused it, not just where it happened. When it doesn't, the screen says
         * "Not sure what caused this" instead of showing a guess as the fix.
         */
        public boolean sure() {
            return !UNSURE.contains(main().code());
        }

        /** For a crash from before Check Engine: is the mod it blamed gone, a different version, or the same? */
        public Since since(Report report) {
            if (culprit == null || report == null || report.jars().isEmpty()) return Since.UNKNOWN;
            String now = null;
            boolean found = false;
            for (ModJar j : report.jars()) {
                if (j.kind() == ModJar.Kind.BROKEN) continue;
                for (ModInfo m : j.mods()) {
                    if (m.modId().equals(culprit)) {
                        found = true;
                        now = m.version();
                    }
                }
            }
            if (!found) return Since.GONE;
            if (culpritVersion != null && now != null && !culpritVersion.equals(now)) return Since.CHANGED;
            return Since.SAME;
        }

        /** The toast instead of Crash Doctor when the mod it blamed is gone or changed: {title, text}, else null. */
        public String[] sinceToast(Since since) {
            return switch (since) {
                case GONE -> new String[] {"Check Engine: Looks fixed", culprit + " crashed the game before. It's gone now."};
                case CHANGED -> new String[] {"Check Engine: Might be fixed",
                        culprit + " crashed the game before. It's a new version now."};
                default -> null;
            };
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
            StringBuilder sb = new StringBuilder(beforeInstall
                    ? "Check Engine: before Check Engine was installed, the game crashed (" + when + ")\n"
                    : "Check Engine: your last game crashed (" + when + ")\n");
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
        try {
            if (started != null) {
                crash = find(gameDir, Long.parseLong(started), state.getProperty("last_shown"));
            } else {
                // First launch with Check Engine: the player may have installed it because the game just crashed.
                LastCrash before = find(gameDir, System.currentTimeMillis() - BEFORE_INSTALL_WINDOW, null);
                if (before != null) crash = before.asBeforeInstall();
            }
        } catch (RuntimeException e) {
            crash = null; // a crash we can't read is no reason to break startup
        }
        state.setProperty("session_started", Long.toString(System.currentTimeMillis()));
        if (crash != null) state.setProperty("last_shown", crash.file().getFileName().toString());
        save(checkEngineFolder, state);
        return crash;
    }

    /**
     * The previous session's unseen crash without marking anything (for the pre-launch check, which runs before
     * {@link #checkAndStartSession}), or null.
     */
    public static LastCrash peek(Path gameDir, Path checkEngineFolder) {
        Properties state = load(checkEngineFolder);
        String started = state.getProperty("session_started");
        if (started == null) return null;
        try {
            return find(gameDir, Long.parseLong(started), state.getProperty("last_shown"));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The newest crash report written since {@code sessionStart} that isn't {@code lastShown}, explained. */
    static LastCrash find(Path gameDir, long sessionStart, String lastShown) {
        Path newest = crashFiles(gameDir).stream().filter(p -> modified(p) >= sessionStart)
                .max(Comparator.comparingLong(CrashDoctor::modified)).orElse(null);
        if (newest == null || newest.getFileName().toString().equals(lastShown)) return null;
        String text;
        try {
            text = Files.readString(newest, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
        // The game log of the same session can name the mod that failed first (the report blames a knock-on).
        CrashTranslator.Explanation explained = CrashTranslator.withLog(CrashTranslator.translate(text),
                GameLog.forCrash(gameDir, newest));
        if (explained.findings().isEmpty()) return null;
        String when = LocalDateTime.ofInstant(Instant.ofEpochMilli(modified(newest)), ZoneId.systemDefault())
                .withSecond(0).withNano(0).toString().replace('T', ' ');
        String culprit = CrashLines.culprit(text);
        List<CrashLines.KeyLine> keys = CrashLines.pick(text);
        // A knock-on crash or a missing mod: the mod in the stack trace isn't the one at fault, so don't point at it.
        if (NOT_AT_FAULT.contains(explained.findings().get(0).code())) {
            culprit = null;
            keys = CrashLines.unmarked(keys);
        }
        return new LastCrash(newest, when, explained, null, keys, culprit, CrashLines.versionInReport(text, culprit),
                false);
    }

    /** Whether Crash Assistant is in the mods folder: it already showed the logs, so Crash Doctor keeps to the fix. */
    public static boolean crashAssistant(Path gameDir) {
        try (Stream<Path> files = Files.list(gameDir.resolve("mods"))) {
            return files.map(p -> p.getFileName().toString().toLowerCase(java.util.Locale.ROOT))
                    .anyMatch(n -> n.startsWith("crashassistant") && n.endsWith(".jar"));
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * Saves the game log of the session that crashed as checkengine/crash-log.txt (latest.log is already this
     * launch's log by the time Crash Doctor shows). Null when no log mentions the crash.
     */
    public static Path saveLog(Path gameDir, LastCrash crash, Path checkEngineFolder) throws IOException {
        String log = GameLog.forCrash(gameDir, crash.file());
        if (log == null) return null;
        Files.createDirectories(checkEngineFolder);
        return Files.writeString(checkEngineFolder.resolve(LOG_FILE), log, StandardCharsets.UTF_8);
    }

    /**
     * Every crash file of a pack: crash reports, and the hs_err_pid*.log Java writes in the game folder when the game
     * dies without one (a silent crash, "exit code -1073741819").
     */
    public static java.util.List<Path> crashFiles(Path gameDir) {
        java.util.List<Path> out = new java.util.ArrayList<>();
        Path dir = gameDir.resolve("crash-reports");
        if (Files.isDirectory(dir)) {
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(p -> p.getFileName().toString().endsWith(".txt")).forEach(out::add);
            } catch (IOException ignored) {
                // none from here
            }
        }
        try (Stream<Path> files = Files.list(gameDir)) {
            files.filter(p -> p.getFileName().toString().matches("hs_err_pid\\d+\\.log")).forEach(out::add);
        } catch (IOException ignored) {
            // none from here
        }
        return out;
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
