package com.guiltypotato.checkengine.core.intercept;

import com.guiltypotato.checkengine.core.crash.CrashDoctor;
import com.guiltypotato.checkengine.core.crash.CrashDoctor.LastCrash;
import com.guiltypotato.checkengine.core.scan.Changes;
import com.guiltypotato.checkengine.core.scan.Finding;
import com.guiltypotato.checkengine.core.scan.PackSnapshot;
import com.guiltypotato.checkengine.core.scan.RootProblem;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Pre-Launch Failure Intercept: "Your pack is broken? Find out in seconds, not after Minecraft finishes loading."
 * Decides what to tell the player before the loading screen, and shows it in a small window (see {@link Popup}).
 */
public final class LaunchIntercept {
    static final String SETTINGS_FILE = "settings.properties";

    public enum Level {
        /** The loader's own rules say the pack can't start. */
        WILL_FAIL,
        /** The last launch crashed and no mods changed since: it will very likely crash the same way. */
        MAY_FAIL
    }

    public enum Choice { QUIT, CONTINUE }

    /**
     * What the window says.
     *
     * @param problems one line per problem, the first is the biggest
     * @param details  extra lines under the first problem (what it stops)
     * @param changed  "+ Create Addon X 2.4.0" lines: what changed since the pack last worked
     * @param fixes    what to do, one per problem (same order), duplicates removed
     * @param report   the full report "Show full error" opens, or null
     */
    public record Message(Level level, List<String> problems, List<String> details, List<String> changed,
                          List<String> fixes, Path report) {
        public String headline() {
            return level == Level.WILL_FAIL ? "STARTUP WILL FAIL" : "STARTUP WILL PROBABLY FAIL AGAIN";
        }

        /** Plain text, for the log and for the simple fallback box. */
        public String toText() {
            StringBuilder sb = new StringBuilder(headline()).append('\n');
            problems.forEach(p -> sb.append(p).append('\n'));
            details.forEach(d -> sb.append("  ").append(d).append('\n'));
            if (!changed.isEmpty()) {
                sb.append("Changed since it last worked:\n");
                changed.forEach(c -> sb.append("  ").append(c).append('\n'));
            }
            fixes.forEach(f -> sb.append("Fix: ").append(f).append('\n'));
            return sb.toString().strip();
        }
    }

    private LaunchIntercept() {}

    /** The loader is going to refuse the pack: what's wrong, biggest problem first. */
    public static Message willFail(List<RootProblem> roots, Changes changes, Path report) {
        List<String> problems = new ArrayList<>();
        for (RootProblem r : roots) problems.add(r.title()); // all of them: the window scrolls
        List<String> details = new ArrayList<>();
        RootProblem first = roots.get(0);
        int stopped = first.affected().size();
        if (stopped > 0) details.add("→ " + stopped + (stopped == 1 ? " mod can't load" : " mods can't load"));
        if (first.certainty() != null) details.add("How sure: " + first.certainty());
        List<String> fixes = roots.stream().map(RootProblem::fix).filter(f -> f != null).distinct().toList();
        return new Message(Level.WILL_FAIL, problems, details, changedLines(changes), fixes, report);
    }

    /** The last launch crashed and the mods are the same as then. */
    public static Message mayFail(LastCrash crash, Path report) {
        Finding main = crash.main();
        List<String> details = List.of("Last launch (" + crash.when() + ") crashed, and no mods changed since then.");
        return new Message(Level.MAY_FAIL, List.of(main.title()), details, List.of(),
                main.fix() == null ? List.of() : List.of(main.fix()), report);
    }

    /** The mods of the latest launch, whether it worked or crashed. */
    static final String LAST_LAUNCH_FILE = "last-launch.properties";

    /**
     * Call once per launch with the mods the loader found. Remembers them, and if the last launch crashed and the
     * mods are exactly the same now, returns the "will probably fail again" warning (else null).
     */
    public static Message repeatCrash(Path gameDir, Path checkEngineFolder, PackSnapshot now) {
        PackSnapshot previous = PackSnapshot.load(checkEngineFolder, LAST_LAUNCH_FILE);
        try {
            now.save(checkEngineFolder, LAST_LAUNCH_FILE);
        } catch (IOException ignored) {
            // next launch just can't compare
        }
        if (previous == null || !previous.mods().equals(now.mods())) return null;
        LastCrash crash = CrashDoctor.peek(gameDir, checkEngineFolder);
        if (crash == null) return null;
        Path details;
        try {
            details = crash.save(checkEngineFolder);
        } catch (IOException e) {
            details = crash.file();
        }
        return mayFail(crash, details);
    }

    static List<String> changedLines(Changes changes) {
        List<String> out = new ArrayList<>();
        if (changes == null) return out;
        for (Changes.Change c : changes.changes()) {
            out.add(switch (c.type()) {
                case ADDED -> "+ " + c.name() + " " + c.to();
                case REMOVED -> "- " + c.name() + " " + c.from();
                case UPDATED -> "~ " + c.name() + " " + c.from() + " → " + c.to();
            });
        }
        return out;
    }

    /**
     * Whether the window is on (checkengine/settings.properties, launch_popup). Writes the file with the default
     * the first time, so players can find the switch.
     */
    public static boolean enabled(Path checkEngineFolder) {
        Path file = checkEngineFolder.resolve(SETTINGS_FILE);
        Properties p = new Properties();
        if (Files.isRegularFile(file)) {
            try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                p.load(in);
            } catch (IOException | IllegalArgumentException e) {
                return true;
            }
        } else {
            p.setProperty("launch_popup", "true");
            try {
                Files.createDirectories(checkEngineFolder);
                try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                    p.store(out, "Check Engine settings. launch_popup: show a window before the loading screen when the"
                            + " pack won't start (true/false)");
                }
            } catch (IOException ignored) {
                // still on
            }
        }
        return !"false".equalsIgnoreCase(p.getProperty("launch_popup", "true").strip());
    }

    /** Shows the window and waits for the player. Anything that goes wrong means CONTINUE: never block a launch. */
    public static Choice ask(Message message) {
        try {
            return Popup.show(message, logo());
        } catch (Throwable t) {
            try {
                return Popup.simple(message);
            } catch (Throwable ignored) {
                return Choice.CONTINUE;
            }
        }
    }

    private static byte[] logo() {
        try (InputStream in = LaunchIntercept.class.getResourceAsStream("/checkengine_logo.png")) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }
}
