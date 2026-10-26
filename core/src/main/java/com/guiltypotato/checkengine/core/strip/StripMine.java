package com.guiltypotato.checkengine.core.strip;

import com.guiltypotato.checkengine.core.crash.CrashTranslator;
import com.guiltypotato.checkengine.core.model.Dependency;
import com.guiltypotato.checkengine.core.model.ModInfo;
import com.guiltypotato.checkengine.core.model.ModJar;
import com.guiltypotato.checkengine.core.scan.JarReader;
import com.guiltypotato.checkengine.core.scan.PackSnapshot;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Strip Mine: finds the mod behind a crash nobody can explain, by digging through the pack one layer at a time.
 * Each round switches off half of the mods still under suspicion (renaming them to .disabled, never deleting), the
 * player launches, and the result halves the suspects: a crash means the bad mod is still switched on, a launch that
 * reaches the title screen means it's in the switched-off half. About log2(mods) rounds; 400 mods take about 9.
 *
 * <p>Mods a switched-on mod needs stay on, so a test launch doesn't fail for a different reason. Progress lives in
 * checkengine/strip-mine.properties, so the game (as it closes) and Check Pack.bat can both carry on the same search.
 * Only files Strip Mine renamed itself are ever renamed back.
 */
public final class StripMine {
    public static final String STATE_FILE = "strip-mine.properties";
    /** Written by the mod when a round reaches the title screen (or the server is ready). */
    public static final String WORKED_MARKER = "strip-mine-worked.marker";
    static final String DISABLED = ".disabled";

    public enum Status {
        /** The plan for the next round is saved but the files aren't renamed yet (the game had them open). */
        PENDING,
        /** Files renamed; waiting for the player to launch. */
        RUNNING,
        /** One mod left: that's it. Everything is switched back on. */
        FOUND,
        /** The search can't narrow it down further; the remaining suspects are listed. Everything is back on. */
        STUCK,
        /** The player stopped it. Everything is back on. */
        STOPPED
    }

    public enum Outcome { CRASHED, WORKED, UNKNOWN }

    /**
     * Where the search is.
     *
     * @param crash       what the original crash was, in one line
     * @param startup     the crash happens while loading, so reaching the title screen means "no crash"; for a crash
     *                    while playing the player has to try what crashed and say it didn't
     * @param status      see {@link Status}
     * @param round       the round being tested (1-based)
     * @param suspects    jar file names that may hold the bad mod
     * @param toDisable   jar file names this round switches off
     * @param disabled    jar file names Strip Mine has renamed to .disabled right now
     * @param roundStart  when the files for this round were put in place (ms), 0 if not yet
     * @param startCount  how many suspects there were at the start
     * @param notes       one line per finished round, for the final report
     * @param keepOn      jars a test launch showed other mods need without saying so: never switched off
     * @param retries     how many times this round had to be set up again after a different crash
     * @param roots       mod ids that failed to load in the original crash (from the game log). Some packs hide every
     *                    loading failure behind the same knock-on crash, so this is what tells crashes apart
     * @param configs     a search over changed config files instead of mods: suspects are paths like
     *                    "config/create-server.toml", and "off" means put back as it was when the pack last worked
     */
    public record State(String crash, boolean startup, Status status, int round, List<String> suspects, List<String> toDisable,
                        List<String> disabled, long roundStart, int startCount, List<String> notes,
                        List<String> keepOn, int retries, List<String> roots, boolean configs) {
        public boolean active() {
            return status == Status.PENDING || status == Status.RUNNING;
        }

        /** Rounds still to go, roughly. */
        public int roundsLeft() {
            return suspects.size() <= 1 ? 0 : 32 - Integer.numberOfLeadingZeros(suspects.size() - 1);
        }

        /** True when test rounds kept failing in other ways and the search stopped without narrowing anything. */
        public boolean gaveUp() {
            return status == Status.STUCK && retries > 5;
        }

        /** The answer: the one jar left when FOUND, else null. */
        public String found() {
            return status == Status.FOUND && suspects.size() == 1 ? suspects.get(0) : null;
        }

        State with(Status s, int r, List<String> sus, List<String> off, List<String> dis, long start, List<String> n) {
            return new State(crash, startup, s, r, sus, off, dis, start, startCount, n, keepOn, retries, roots,
                    configs);
        }

        State retry(List<String> keep, int tries) {
            return new State(crash, startup, status, round, suspects, toDisable, disabled, roundStart, startCount, notes,
                    keep, tries, roots, configs);
        }
    }

    private StripMine() {}

    // ---- starting and stopping ----

    /** What to tell the player when CurseForge has the pack locked. */
    public static final String UNLOCK_STEPS = "In the CurseForge app, click the three dots next to Play, open Profile "
            + "options and turn on \"Allow content management\".";

    /**
     * Whether the CurseForge app has this pack locked (installed as it came, "Allow content management" off). The app
     * then checks the mod files at every launch and downloads the ones Strip Mine switched off again, which wrecks the
     * search (and leaves duplicate files). The app saves the switch to minecraftinstance.json right away.
     */
    public static boolean lockedByCurseForge(Path gameDir) {
        Path record = gameDir.resolve("minecraftinstance.json");
        if (!Files.isRegularFile(record)) return false;
        try {
            String text = Files.readString(record, StandardCharsets.UTF_8);
            return java.util.regex.Pattern.compile("\"isUnlocked\"\\s*:\\s*false").matcher(text).find();
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * Starts a search over every mod in {@code modsFolder} except Check Engine. With {@code applyNow} the files are
     * renamed straight away (Check Pack.bat, game closed); without it the plan waits for {@link #advance} as the
     * game closes ({@link StripMineHelper#launch}).
     */
    public static State start(Path modsFolder, Path checkEngineFolder, String crash, boolean startup,
                              boolean applyNow)
            throws IOException {
        if (lockedByCurseForge(modsFolder.getParent())) {
            throw new IOException("CurseForge has this pack locked, so it would undo Strip Mine. " + UNLOCK_STEPS);
        }
        Map<String, ModJar> jars = readJars(modsFolder, List.of());
        List<String> suspects = new ArrayList<>();
        for (ModJar jar : jars.values()) {
            if (isCheckEngine(jar)) continue;
            suspects.add(jar.fileName());
        }
        suspects.sort(String.CASE_INSENSITIVE_ORDER);
        if (suspects.size() < 2) throw new IOException("Strip Mine needs at least 2 mods to search.");
        // The crashed launch's log: the newest rotated one while the game runs again, else latest.log.
        List<String> roots = new ArrayList<>(crashRoots(modsFolder.getParent()));
        if (!roots.isEmpty()) crash = String.join(", ", roots) + " failed to load"; // the real failure, not a knock-on
        State s = new State(crash, startup, Status.PENDING, 1, suspects, List.of(), List.of(), 0, suspects.size(),
                List.of(), List.of(), 0, roots, false);
        // Smart start: "it worked yesterday" usually means one of the mods changed since then did it. Test those (and
        // the mods that failed to load) on their own first: 1 changed mod takes 1-2 rounds instead of 9.
        Set<String> likely = likelyJars(jars, PackSnapshot.load(checkEngineFolder), roots);
        List<String> smart = smartPlan(suspects, jars, likely);
        List<String> notes = smart.isEmpty() ? List.of() : List.of("Smart start: round 1 tests the "
                + likely.size() + " mods most likely to blame (changed since the pack last worked, or named in the crash)");
        s = s.with(Status.PENDING, 1, suspects, smart.isEmpty() ? plan(suspects, jars) : smart, List.of(), 0, notes);
        save(checkEngineFolder, s);
        return applyNow ? apply(modsFolder, checkEngineFolder, s) : s;
    }

    /**
     * What switching off the faulty jar takes: the jar plus every jar that can't load without it, so the next launch
     * doesn't stop over a missing mod instead.
     */
    public static List<String> switchOffPlan(Path modsFolder, String jar) throws IOException {
        return new ArrayList<>(withDependents(jar, readJars(modsFolder, List.of())));
    }

    /**
     * Switches jars off for good, at the player's request (renamed to .disabled, never deleted). Returns the ones that
     * couldn't be renamed yet because the game holds them open.
     */
    public static List<String> switchOff(Path modsFolder, List<String> jars) {
        List<String> locked = new ArrayList<>();
        for (String f : jars) {
            try {
                rename(modsFolder.resolve(f), modsFolder.resolve(f + DISABLED));
            } catch (IOException e) {
                locked.add(f);
            }
        }
        // NeoForge 26.1 keeps its jars locked until the game process ends, so even a rename on the way out can
        // fail: the next start does it before the loader opens the mods folder (applyPending).
        if (!locked.isEmpty()) addPending(modsFolder, locked);
        return locked;
    }

    /** checkengine/switch-off-pending.txt: jars to switch off as soon as they aren't locked. */
    static final String PENDING_FILE = "switch-off-pending.txt";

    private static synchronized void addPending(Path modsFolder, List<String> jars) {
        Path file = modsFolder.getParent().resolve("checkengine").resolve(PENDING_FILE);
        try {
            Set<String> all = new LinkedHashSet<>(Files.isRegularFile(file)
                    ? Files.readAllLines(file, StandardCharsets.UTF_8) : List.of());
            all.addAll(jars);
            all.removeIf(String::isBlank);
            Files.createDirectories(file.getParent());
            Files.write(file, all, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // the jar stays on; the player can rename it
        }
    }

    /**
     * Call before the loader opens the mods folder: switches off the jars a closing game couldn't. Returns the ones
     * that are still locked (have the loader skip them).
     */
    public static List<String> applyPending(Path modsFolder) {
        Path file = modsFolder.getParent().resolve("checkengine").resolve(PENDING_FILE);
        if (!Files.isRegularFile(file)) return List.of();
        List<String> still = new ArrayList<>();
        try {
            for (String f : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (f.isBlank() || !Files.exists(modsFolder.resolve(f))) continue;
                try {
                    rename(modsFolder.resolve(f), modsFolder.resolve(f + DISABLED));
                } catch (IOException e) {
                    still.add(f);
                }
            }
            if (still.isEmpty()) Files.deleteIfExists(file); // our own note, done
            else Files.write(file, still, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // try again next start
        }
        return still;
    }

    /** Stops the search and switches everything Strip Mine turned off back on. */
    public static State stop(Path modsFolder, Path checkEngineFolder) throws IOException {
        State s = load(checkEngineFolder);
        if (s == null) return null;
        restore(modsFolder, checkEngineFolder, s);
        s = s.with(Status.STOPPED, s.round(), s.suspects(), List.of(), List.of(), s.roundStart(), s.notes());
        save(checkEngineFolder, s);
        return s;
    }

    /** Forgets a finished search (after its result was shown), so the next crash can start a new one. */
    public static void clear(Path checkEngineFolder) throws IOException {
        Files.deleteIfExists(checkEngineFolder.resolve(STATE_FILE));
        Files.deleteIfExists(checkEngineFolder.resolve(WORKED_MARKER));
    }

    /** Whether a crash report's "Description:" means it crashed while loading (so reaching the title screen = fine). */
    public static boolean isStartupCrash(String description) {
        if (description == null) return true;
        String d = description.toLowerCase();
        return d.contains("initializing game") || d.contains("loading") || d.contains("mod loading");
    }

    /** The search running right now (rounds to play), or null. */
    public static State running(Path checkEngineFolder) {
        State s = load(checkEngineFolder);
        return s != null && s.active() ? s : null;
    }

    /** A finished search whose result hasn't been shown yet, or null. */
    public static State finished(Path checkEngineFolder) {
        State s = load(checkEngineFolder);
        return s != null && (s.status() == Status.FOUND || s.status() == Status.STUCK) ? s : null;
    }

    // ---- rounds ----

    /**
     * Call as the game closes, or while it's closed (Check Pack.bat). Puts a pending plan in place, or
     * reads how the last round went and sets up the next one. {@code override} replaces the automatic result (Check
     * Pack.bat asks the player when it can't tell); pass null to detect it.
     */
    public static State advance(Path gameDir, Path modsFolder, Path checkEngineFolder, Outcome override)
            throws IOException {
        State s = load(checkEngineFolder);
        if (s == null || !s.active()) return s;
        if (s.status() == Status.PENDING) return apply(modsFolder, checkEngineFolder, s);

        Outcome outcome = override != null ? override : outcome(gameDir, checkEngineFolder, s.roundStart());
        // Configs can't make another mod's class go missing: every crash in a config round counts as "still crashes".
        if (override == null && outcome != Outcome.WORKED && !s.configs()) {
            // Did the test launch fail the same way as the original crash? Switching mods off can break others in
            // new ways (a mod that uses a library without saying so): that round tells nothing about the original.
            Path report = newestCrash(gameDir, s.roundStart());
            String log = recentLog(gameDir, s.roundStart());
            String evidence = (report != null ? read(report) : "") + "\n" + (log == null ? "" : log);
            String title = report != null ? title(read(report)) : null;
            boolean different;
            if (!s.roots().isEmpty() && outcome == Outcome.CRASHED) {
                // Same crash only if one of the mods that failed originally failed again.
                Set<String> now = loadingRoots(evidence);
                different = java.util.Collections.disjoint(now, s.roots());
                if (different && title == null) title = now.isEmpty() ? "no mod failed to load" : String.join(", ", now) + " failed to load";
            } else {
                different = title != null && !title.equals(s.crash());
            }
            Set<String> learned = evidence == null ? Set.of() : neededJars(evidence, modsFolder, s.disabled());
            if (!s.roots().isEmpty() && outcome == Outcome.CRASHED && !different) {
                // The original mod failed again: a real "still crashes". Remember what else broke for later rounds.
                if (!learned.isEmpty()) {
                    List<String> keep = new ArrayList<>(s.keepOn());
                    for (String j : learned) if (!keep.contains(j)) keep.add(j);
                    s = s.retry(keep, s.retries());
                }
            } else if (different || !learned.isEmpty()) {
                // A crash over a class or mod from a jar Strip Mine switched off can never be the original crash:
                // that one happened with every mod on.
                return retry(modsFolder, checkEngineFolder, s, learned, title);
            }
        }
        if (outcome == Outcome.UNKNOWN) return s; // the game was closed before crashing or loading: same round again

        Set<String> off = new HashSet<>(s.toDisable());
        List<String> next = new ArrayList<>();
        for (String f : s.suspects()) if ((outcome == Outcome.WORKED) == off.contains(f)) next.add(f);
        List<String> notes = new ArrayList<>(s.notes());
        notes.add("Round " + s.round() + ": " + off.size() + " of " + s.suspects().size() + " suspects switched off, "
                + (outcome == Outcome.CRASHED ? "still crashed" : "no crash") + " -> " + next.size() + " left");
        if (next.isEmpty()) next = s.suspects(); // can't happen with a real split; never lose the suspects

        if (next.size() == 1) return finish(modsFolder, checkEngineFolder, s, Status.FOUND, next, notes);
        List<String> plan = s.configs() ? new ArrayList<>(next.subList(0, next.size() / 2))
                : plan(next, readJars(modsFolder, s.disabled()), new HashSet<>(s.keepOn()), next.size() / 2);
        if (plan.isEmpty()) return finish(modsFolder, checkEngineFolder, s, Status.STUCK, next, notes);
        State pending = s.retry(s.keepOn(), 0).with(Status.PENDING, s.round() + 1, next, plan, s.disabled(), 0, notes);
        save(checkEngineFolder, pending);
        return apply(modsFolder, checkEngineFolder, pending);
    }

    /** Plans the current round again (game closed), e.g. after an update to how rounds are split. */
    public static State replan(Path modsFolder, Path checkEngineFolder) throws IOException {
        State s = load(checkEngineFolder);
        if (s == null || !s.active() || s.configs()) return s;
        List<String> plan = plan(s.suspects(), readJars(modsFolder, s.disabled()), new HashSet<>(s.keepOn()),
                s.suspects().size() / 2);
        if (plan.isEmpty()) return s;
        State pending = s.with(Status.PENDING, s.round(), s.suspects(), plan, s.disabled(), 0, s.notes());
        save(checkEngineFolder, pending);
        return apply(modsFolder, checkEngineFolder, pending);
    }

    /**
     * The round failed differently from the original crash. Keep on the jars the failure points at, and set the same
     * round up again; with nothing to point at, switch fewer mods off. After a few tries, give up on splitting further.
     */
    private static State retry(Path modsFolder, Path checkEngineFolder, State s, Set<String> learned, String title)
            throws IOException {
        List<String> keep = new ArrayList<>(s.keepOn());
        for (String f : learned) if (!keep.contains(f)) keep.add(f);
        int tries = s.retries() + 1;
        List<String> notes = new ArrayList<>(s.notes());
        notes.add("Round " + s.round() + ": a different crash" + (title == null ? "" : " (" + title + ")")
                + (learned.isEmpty() ? ", trying with fewer mods off" : ", keeping " + String.join(", ", learned)
                + " on") + " and running it again");
        State r = s.retry(keep, tries).with(s.status(), s.round(), s.suspects(), s.toDisable(), s.disabled(),
                s.roundStart(), notes);
        if (tries > 5) return finish(modsFolder, checkEngineFolder, r, Status.STUCK, s.suspects(), notes);
        Map<String, ModJar> jars = readJars(modsFolder, s.disabled());
        int target = s.suspects().size() / 2;
        if (learned.isEmpty()) target = Math.max(1, target >> tries); // nothing to learn from: smaller steps
        List<String> plan = plan(s.suspects(), jars, new HashSet<>(keep), target);
        if (plan.isEmpty()) return finish(modsFolder, checkEngineFolder, r, Status.STUCK, s.suspects(), notes);
        State pending = r.with(Status.PENDING, s.round(), s.suspects(), plan, s.disabled(), 0, notes);
        save(checkEngineFolder, pending);
        return apply(modsFolder, checkEngineFolder, pending);
    }

    private static final java.util.regex.Pattern MISSING_CLASS = java.util.regex.Pattern.compile(
            "(?:NoClassDefFoundError|ClassNotFoundException)[:\\s]+([A-Za-z0-9_$./]+)");
    private static final java.util.regex.Pattern MISSING_ID = java.util.regex.Pattern.compile(
            "Mod '([a-z0-9_.-]+)' is not available|Missing mod: ([a-z0-9_.-]+)|Mod didn't load: ([a-z0-9_.-]+)"
                    + "|Outdated mod: ([a-z0-9_.-]+)|Wrong version of ([a-z0-9_.-]+) for"
                    + "|Mod [a-z0-9_.-]+ requires ([a-z0-9_.-]+) |Currently, ([a-z0-9_.-]+) is ");

    /** The switched-off jars a failure points at: ones holding a class it couldn't find, or a mod it said is missing. */
    static Set<String> neededJars(String evidence, Path modsFolder, List<String> disabled) {
        Set<String> classes = new HashSet<>();
        java.util.regex.Matcher c = MISSING_CLASS.matcher(evidence);
        while (c.find()) classes.add(c.group(1).replace('.', '/') + ".class");
        Set<String> ids = new HashSet<>();
        java.util.regex.Matcher m = MISSING_ID.matcher(evidence);
        while (m.find()) for (int g = 1; g <= m.groupCount(); g++) if (m.group(g) != null) ids.add(m.group(g));
        Set<String> out = new LinkedHashSet<>();
        for (String f : disabled) {
            Path p = modsFolder.resolve(f + DISABLED);
            if (!Files.isRegularFile(p)) continue;
            ModJar j = JarReader.read(p);
            if (j.mods().stream().anyMatch(mod -> ids.contains(mod.modId()))) {
                out.add(f);
                continue;
            }
            if (classes.isEmpty()) continue;
            try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(p.toFile())) {
                if (classes.stream().anyMatch(k -> zip.getEntry(k) != null)) out.add(f);
            } catch (IOException ignored) {
                // unreadable: can't tell
            }
        }
        return out;
    }

    private static final java.util.regex.Pattern ROOT = java.util.regex.Pattern.compile(
            "Failed to create mod instance\\. ModID: ([a-z0-9_.-]+)"
                    + "|\\(([a-z0-9_.-]+)\\) has failed to load correctly"
                    + "|\\(([a-z0-9_.-]+)\\) encountered an error"
                    + "|-- Mod loading issue for: ([a-z0-9_.-]+) --");

    /** Mod ids a log or crash report says failed to load (the mods whose own code broke, not knock-on crashes). */
    static Set<String> loadingRoots(String text) {
        Set<String> out = new LinkedHashSet<>();
        java.util.regex.Matcher m = ROOT.matcher(text);
        while (m.find()) for (int g = 1; g <= m.groupCount(); g++) if (m.group(g) != null) out.add(m.group(g));
        return out;
    }

    /**
     * The mods that failed to load in the launch that wrote the newest crash report: read from that launch's log (the
     * newest log, latest or rotated, finished around the time of the crash that names any). Empty if none does.
     */
    static Set<String> crashRoots(Path gameDir) {
        Path crash = newestCrash(gameDir, 0);
        if (crash == null) return Set.of();
        long when = modified(crash) - 60_000;
        Path logs = gameDir.resolve("logs");
        try (Stream<Path> files = Files.list(logs)) {
            List<Path> candidates = files.filter(p -> {
                        String n = p.getFileName().toString();
                        return !n.startsWith("debug") && (n.equals("latest.log") || n.endsWith(".log.gz"));
                    })
                    .filter(p -> modified(p) >= when)
                    .sorted(Comparator.comparingLong(StripMine::modified))
                    .toList();
            for (Path p : candidates) { // oldest first: the crashed launch ended before any later one
                Set<String> roots = loadingRoots(logText(p));
                if (!roots.isEmpty()) return roots;
            }
        } catch (IOException e) {
            // no logs: compare crash titles instead
        }
        return Set.of();
    }

    private static String logText(Path p) {
        if (!p.getFileName().toString().endsWith(".gz")) return read(p) == null ? "" : read(p);
        try (java.io.InputStream in = new java.util.zip.GZIPInputStream(Files.newInputStream(p))) {
            return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        } catch (IOException e) {
            return "";
        }
    }

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.ISO_8859_1);
        } catch (IOException e) {
            return null;
        }
    }

    /** This round's latest.log, if it was written since the round started. */
    private static String recentLog(Path gameDir, long since) {
        Path log = gameDir.resolve("logs").resolve("latest.log");
        return modified(log) >= since ? read(log) : null;
    }

    private static String title(String crashReport) {
        try {
            return CrashTranslator.translate(crashReport).findings().get(0).title();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** How the round that started at {@code roundStart} went: a new crash report, the title screen, or neither. */
    static Outcome outcome(Path gameDir, Path checkEngineFolder, long roundStart) {
        if (newestCrash(gameDir, roundStart) != null) return Outcome.CRASHED;
        Path marker = checkEngineFolder.resolve(WORKED_MARKER);
        if (modified(marker) >= roundStart && Files.isRegularFile(marker)) return Outcome.WORKED;
        if (logShowsLoadingFailed(gameDir, roundStart)) return Outcome.CRASHED;
        return Outcome.UNKNOWN;
    }

    /** Lines the loaders write when loading fails, even when they show their error screen instead of a crash report. */
    private static final List<String> LOADING_FAILED = List.of("Loading errors encountered", "Mod loading has failed",
            "ModLoadingException", "---- Minecraft Crash Report ----", "Error loading mods", "LoadingFailedException");

    /** Whether this round's latest.log says loading failed. */
    static boolean logShowsLoadingFailed(Path gameDir, long roundStart) {
        Path log = gameDir.resolve("logs").resolve("latest.log");
        if (modified(log) < roundStart) return false;
        try {
            String text = Files.readString(log, StandardCharsets.ISO_8859_1);
            return LOADING_FAILED.stream().anyMatch(text::contains);
        } catch (IOException e) {
            return false;
        }
    }

    /** The newest crash report written at or after {@code since}, or null. */
    static Path newestCrash(Path gameDir, long since) {
        Path dir = gameDir.resolve("crash-reports");
        if (!Files.isDirectory(dir)) return null;
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".txt"))
                    .filter(p -> modified(p) >= since)
                    .max(Comparator.comparingLong(StripMine::modified)).orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    /** One line saying what the newest crash since {@code since} was, or null. */
    public static String crashTitle(Path gameDir, long since) {
        Path p = newestCrash(gameDir, since);
        if (p == null) return null;
        try {
            return CrashTranslator.translate(Files.readString(p, StandardCharsets.UTF_8)).findings().get(0).title();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Called by the mod when a Strip Mine round reaches the title screen or the server is ready. */
    public static void markWorked(Path checkEngineFolder) {
        try {
            Files.createDirectories(checkEngineFolder);
            Files.writeString(checkEngineFolder.resolve(WORKED_MARKER), Long.toString(System.currentTimeMillis()));
        } catch (IOException ignored) {
            // the helper then can't tell; that round is simply run again
        }
    }

    /**
     * Which jars to switch off this round: about half of the suspects, each together with every jar that needs it
     * (an addon can't load without its base mod, so it goes off with it). Those extra jars may already be cleared;
     * switching them off doesn't change the test, because only one mod causes the crash. Suspects with the fewest
     * dependents go first, so a library everything needs doesn't take the whole pack down with it. Empty if no split
     * leaves at least one suspect on and one off.
     */
    static List<String> plan(List<String> suspects, Map<String, ModJar> jars) {
        return plan(suspects, jars, Set.of(), suspects.size() / 2);
    }

    /** As {@link #plan(List, Map)}, never switching off a jar in {@code keepOn}, aiming for {@code target} suspects. */
    static List<String> plan(List<String> suspects, Map<String, ModJar> jars, Set<String> keepOn, int target) {
        Map<String, Set<String>> closure = new HashMap<>();
        for (String s : suspects) {
            Set<String> c = withDependents(s, jars);
            if (c.stream().noneMatch(keepOn::contains)) closure.put(s, c);
        }
        Set<String> suspectSet = new HashSet<>(suspects);
        List<String> order = new ArrayList<>(closure.keySet());
        order.sort(Comparator.comparingInt((String s) -> tested(closure.get(s), suspectSet))
                .thenComparing(String.CASE_INSENSITIVE_ORDER));
        Set<String> off = new LinkedHashSet<>();
        for (String s : order) {
            if (tested(off, suspectSet) >= target) break;
            if (off.contains(s)) continue;
            Set<String> next = new LinkedHashSet<>(off);
            next.addAll(closure.get(s));
            if (tested(next, suspectSet) >= suspects.size()) continue; // would leave no suspect on
            off = next;
        }
        return tested(off, suspectSet) == 0 ? List.of() : new ArrayList<>(off);
    }

    /**
     * Jars most likely to hold the bad mod: the ones added or updated since the last launch that worked, and the ones
     * holding a mod that failed to load in the crash. Never Check Engine.
     */
    static Set<String> likelyJars(Map<String, ModJar> jars, PackSnapshot lastGood, List<String> roots) {
        Set<String> out = new LinkedHashSet<>();
        for (ModJar j : jars.values()) {
            if (isCheckEngine(j)) continue;
            boolean changed = lastGood != null && j.mods().stream().anyMatch(m -> {
                PackSnapshot.Entry old = lastGood.mods().get(m.modId());
                return old == null || !java.util.Objects.equals(old.version(), m.version());
            });
            if (changed || ids(j).stream().anyMatch(roots::contains)) out.add(j.fileName());
        }
        return out;
    }

    /**
     * Round 1 of a smart start: switch off just the likely jars (with what needs them). Empty when there's nothing
     * likely, or it would be more than half the pack anyway (then a normal split is just as good).
     */
    static List<String> smartPlan(List<String> suspects, Map<String, ModJar> jars, Set<String> likely) {
        Set<String> suspectSet = new HashSet<>(suspects);
        Set<String> off = new LinkedHashSet<>();
        for (String f : likely) if (suspectSet.contains(f)) off.addAll(withDependents(f, jars));
        int n = tested(off, suspectSet);
        return n == 0 || n > suspects.size() / 2 ? List.of() : new ArrayList<>(off);
    }

    private static int tested(Set<String> off, Set<String> suspects) {
        int n = 0;
        for (String f : off) if (suspects.contains(f)) n++;
        return n;
    }

    /** {@code jar} plus every jar that needs it, directly or through others (never Check Engine). */
    static Set<String> withDependents(String jar, Map<String, ModJar> jars) {
        Set<String> out = new LinkedHashSet<>(List.of(jar));
        boolean changed = true;
        while (changed) {
            changed = false;
            // A real mod being switched off is only replaced by another real one: a copy packed inside some other jar is
            // often older (Test Pack: Architectury 13.0.6 inside another mod took over when 13.0.11 was off,
            // and its addons refused to load). A library that was only ever packed is fine if any jar still packs it.
            Set<String> offReal = new HashSet<>();
            Set<String> offPacked = new HashSet<>();
            for (String f : out) {
                ModJar j = jars.get(f);
                if (j == null) continue;
                j.mods().forEach(m -> offReal.add(m.modId()));
                j.nestedMods().forEach(m -> offPacked.add(m.modId()));
            }
            offPacked.removeAll(offReal);
            Set<String> onReal = new HashSet<>();
            Set<String> onAny = new HashSet<>();
            for (ModJar j : jars.values()) {
                if (out.contains(j.fileName())) continue;
                j.mods().forEach(m -> onReal.add(m.modId()));
                onAny.addAll(ids(j));
            }
            for (ModJar j : jars.values()) {
                if (out.contains(j.fileName()) || isCheckEngine(j)) continue;
                boolean needs = j.mods().stream().flatMap(m -> m.dependencies().stream())
                        .filter(d -> d.type() == Dependency.Type.REQUIRED)
                        .anyMatch(d -> (offReal.contains(d.modId()) && !onReal.contains(d.modId()))
                                || (offPacked.contains(d.modId()) && !onAny.contains(d.modId())));
                if (needs) {
                    out.add(j.fileName());
                    changed = true;
                }
            }
        }
        return out;
    }

    // ---- files ----

    /**
     * Renames this round's jars to .disabled and switches back on the ones Strip Mine had off but no longer needs.
     * A file Windows keeps locked (NeoForge 26.1 holds its mod jars open while the game runs) is left as it is: the
     * next launch has the loader skip it instead ({@link #lockedOn}).
     */
    static State apply(Path modsFolder, Path checkEngineFolder, State s) throws IOException {
        if (s.configs()) return ConfigStrip.apply(modsFolder.getParent(), checkEngineFolder, s);
        Set<String> want = new LinkedHashSet<>(s.toDisable());
        List<String> nowOff = new ArrayList<>();
        for (String f : s.disabled()) {
            if (want.contains(f)) {
                nowOff.add(f);
            } else {
                try {
                    rename(modsFolder.resolve(f + DISABLED), modsFolder.resolve(f));
                } catch (IOException e) {
                    nowOff.add(f); // still off: keep track of it so it's switched back on later
                    StripMineHelper.log(checkEngineFolder, "couldn't switch " + f + " back on yet: " + e);
                }
            }
        }
        for (String f : want) {
            if (nowOff.contains(f)) continue;
            Path jar = modsFolder.resolve(f);
            if (!Files.isRegularFile(jar)) continue; // moved or deleted by the player: nothing to switch off
            try {
                rename(jar, modsFolder.resolve(f + DISABLED));
            } catch (IOException e) {
                StripMineHelper.log(checkEngineFolder, "couldn't rename " + f + " (in use), the loader skips it: " + e);
                continue;
            }
            nowOff.add(f);
            // Save after every rename, so a crash halfway can still put everything back.
            save(checkEngineFolder, s.with(Status.PENDING, s.round(), s.suspects(), s.toDisable(), nowOff, 0, s.notes()));
        }
        State running = s.with(Status.RUNNING, s.round(), s.suspects(), s.toDisable(), nowOff,
                System.currentTimeMillis(), s.notes());
        save(checkEngineFolder, running);
        return running;
    }

    /**
     * The jars this round should have off that are still switched on because they couldn't be renamed (locked).
     * The loader is told to skip them, so the round still tests the right mods.
     */
    public static List<String> lockedOn(Path modsFolder, Path checkEngineFolder) {
        State s = running(checkEngineFolder);
        if (s == null) return List.of();
        List<String> out = new ArrayList<>();
        for (String f : s.toDisable()) {
            if (!s.disabled().contains(f) && Files.isRegularFile(modsFolder.resolve(f))) out.add(f);
        }
        return out;
    }

    private static State finish(Path modsFolder, Path checkEngineFolder, State s, Status status, List<String> left,
                                List<String> notes) throws IOException {
        restore(modsFolder, checkEngineFolder, s);
        State done = s.with(status, s.round(), left, List.of(), List.of(), s.roundStart(), notes);
        save(checkEngineFolder, done);
        return done;
    }

    /** Puts back everything this search has off right now: jars back on, or the player's own config files. */
    private static void restore(Path modsFolder, Path checkEngineFolder, State s) throws IOException {
        if (s.configs()) {
            for (String f : s.disabled()) ConfigStrip.putBack(modsFolder.getParent(), checkEngineFolder, f);
        } else {
            restore(modsFolder, s.disabled());
        }
    }

    private static void restore(Path modsFolder, List<String> disabled) throws IOException {
        for (String f : disabled) {
            Path off = modsFolder.resolve(f + DISABLED);
            if (Files.isRegularFile(off)) rename(off, modsFolder.resolve(f));
        }
    }

    static void rename(Path from, Path to) throws IOException {
        if (!Files.exists(from) || Files.exists(to)) return; // never overwrite anything
        Files.move(from, to);
    }

    /** The jars in the mods folder, plus the ones Strip Mine switched off (never the player's own .disabled files). */
    static Map<String, ModJar> readJars(Path modsFolder, List<String> ours) throws IOException {
        Map<String, ModJar> out = new HashMap<>();
        try (Stream<Path> files = Files.list(modsFolder)) {
            for (Path p : files.filter(f -> f.getFileName().toString().toLowerCase().endsWith(".jar")).toList()) {
                out.put(p.getFileName().toString(), JarReader.read(p));
            }
        }
        for (String jarName : ours) {
            Path p = modsFolder.resolve(jarName + DISABLED);
            if (!Files.isRegularFile(p) || out.containsKey(jarName)) continue;
            ModJar j = JarReader.read(p);
            out.put(jarName, new ModJar(modsFolder.resolve(jarName), j.kind(), j.mods(), j.nestedMods(), j.error()));
        }
        return out;
    }

    private static Set<String> ids(ModJar j) {
        Set<String> out = new HashSet<>();
        j.mods().forEach(m -> out.add(m.modId()));
        j.nestedMods().forEach(m -> out.add(m.modId()));
        return out;
    }

    static boolean isCheckEngine(ModJar j) {
        return ids(j).contains("checkengine") || j.fileName().toLowerCase().startsWith("checkengine");
    }

    private static long modified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    // ---- state file ----

    public static State load(Path checkEngineFolder) {
        Path file = checkEngineFolder.resolve(STATE_FILE);
        if (!Files.isRegularFile(file)) return null;
        Properties p = new Properties();
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(in);
            return new State(p.getProperty("crash", ""), Boolean.parseBoolean(p.getProperty("startup", "true")),
                    Status.valueOf(p.getProperty("status")),
                    Integer.parseInt(p.getProperty("round", "1")), list(p, "suspects"), list(p, "to_disable"),
                    list(p, "disabled"), Long.parseLong(p.getProperty("round_start", "0")),
                    Integer.parseInt(p.getProperty("start_count", "0")), list(p, "notes"), list(p, "keep_on"),
                    Integer.parseInt(p.getProperty("retries", "0")), list(p, "roots"),
                    Boolean.parseBoolean(p.getProperty("configs", "false")));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    static void save(Path checkEngineFolder, State s) throws IOException {
        Properties p = new Properties();
        p.setProperty("crash", s.crash() == null ? "" : s.crash());
        p.setProperty("startup", Boolean.toString(s.startup()));
        p.setProperty("status", s.status().name());
        p.setProperty("round", Integer.toString(s.round()));
        p.setProperty("suspects", String.join("\n", s.suspects()));
        p.setProperty("to_disable", String.join("\n", s.toDisable()));
        p.setProperty("disabled", String.join("\n", s.disabled()));
        p.setProperty("round_start", Long.toString(s.roundStart()));
        p.setProperty("start_count", Integer.toString(s.startCount()));
        p.setProperty("notes", String.join("\n", s.notes()));
        p.setProperty("keep_on", String.join("\n", s.keepOn()));
        p.setProperty("retries", Integer.toString(s.retries()));
        p.setProperty("configs", Boolean.toString(s.configs()));
        p.setProperty("roots", String.join("\n", s.roots()));
        Files.createDirectories(checkEngineFolder);
        Path tmp = checkEngineFolder.resolve(STATE_FILE + ".tmp");
        try (Writer out = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            p.store(out, "Check Engine Strip Mine: which mods are switched off right now. Don't edit; run "
                    + "'Check Pack.bat' or use the in-game button to stop and put everything back.");
        }
        Files.move(tmp, checkEngineFolder.resolve(STATE_FILE), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static List<String> list(Properties p, String key) {
        String v = p.getProperty(key, "");
        return v.isEmpty() ? List.of() : new ArrayList<>(Arrays.asList(v.split("\n")));
    }
}
