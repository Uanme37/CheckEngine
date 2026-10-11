package com.guiltypotato.checkengine.core.strip;

import com.guiltypotato.checkengine.core.crash.CrashTranslator;
import com.guiltypotato.checkengine.core.model.Dependency;
import com.guiltypotato.checkengine.core.model.ModInfo;
import com.guiltypotato.checkengine.core.model.ModJar;
import com.guiltypotato.checkengine.core.scan.JarReader;
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
 * checkengine/strip-mine.properties, so the game, Check Pack.bat and the helper that runs after the game closes can
 * all carry on the same search. Only files Strip Mine renamed itself are ever renamed back.
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
     */
    public record State(String crash, boolean startup, Status status, int round, List<String> suspects, List<String> toDisable,
                        List<String> disabled, long roundStart, int startCount, List<String> notes,
                        List<String> keepOn, int retries) {
        public boolean active() {
            return status == Status.PENDING || status == Status.RUNNING;
        }

        /** Rounds still to go, roughly. */
        public int roundsLeft() {
            return suspects.size() <= 1 ? 0 : 32 - Integer.numberOfLeadingZeros(suspects.size() - 1);
        }

        /** The answer: the one jar left when FOUND, else null. */
        public String found() {
            return status == Status.FOUND && suspects.size() == 1 ? suspects.get(0) : null;
        }

        State with(Status s, int r, List<String> sus, List<String> off, List<String> dis, long start, List<String> n) {
            return new State(crash, startup, s, r, sus, off, dis, start, startCount, n, keepOn, retries);
        }

        State retry(List<String> keep, int tries) {
            return new State(crash, startup, status, round, suspects, toDisable, disabled, roundStart, startCount, notes,
                    keep, tries);
        }
    }

    private StripMine() {}

    // ---- starting and stopping ----

    /**
     * Starts a search over every mod in {@code modsFolder} except Check Engine. With {@code applyNow} the files are
     * renamed straight away (Check Pack.bat, game closed); without it the plan waits for {@link #advance} after the
     * game closes, because a running game keeps its jars open.
     */
    public static State start(Path modsFolder, Path checkEngineFolder, String crash, boolean startup,
                              boolean applyNow)
            throws IOException {
        Map<String, ModJar> jars = readJars(modsFolder, List.of());
        List<String> suspects = new ArrayList<>();
        for (ModJar jar : jars.values()) {
            if (isCheckEngine(jar)) continue;
            suspects.add(jar.fileName());
        }
        suspects.sort(String.CASE_INSENSITIVE_ORDER);
        if (suspects.size() < 2) throw new IOException("Strip Mine needs at least 2 mods to search.");
        State s = new State(crash, startup, Status.PENDING, 1, suspects, List.of(), List.of(), 0, suspects.size(),
                List.of(), List.of(), 0);
        s = s.with(Status.PENDING, 1, suspects, plan(suspects, jars), List.of(), 0, List.of());
        save(checkEngineFolder, s);
        return applyNow ? apply(modsFolder, checkEngineFolder, s) : s;
    }

    /** Stops the search and switches everything Strip Mine turned off back on. */
    public static State stop(Path modsFolder, Path checkEngineFolder) throws IOException {
        State s = load(checkEngineFolder);
        if (s == null) return null;
        restore(modsFolder, s.disabled());
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
     * Call while the game is closed (the helper after it exits, or Check Pack.bat). Puts a pending plan in place, or
     * reads how the last round went and sets up the next one. {@code override} replaces the automatic result (Check
     * Pack.bat asks the player when it can't tell); pass null to detect it.
     */
    public static State advance(Path gameDir, Path modsFolder, Path checkEngineFolder, Outcome override)
            throws IOException {
        State s = load(checkEngineFolder);
        if (s == null || !s.active()) return s;
        if (s.status() == Status.PENDING) return apply(modsFolder, checkEngineFolder, s);

        Outcome outcome = override != null ? override : outcome(gameDir, checkEngineFolder, s.roundStart());
        if (override == null && outcome != Outcome.WORKED) {
            // Did the test launch fail the same way as the original crash? Switching mods off can break others in
            // new ways (a mod that uses a library without saying so): that round tells nothing about the original.
            Path report = newestCrash(gameDir, s.roundStart());
            String evidence = report != null ? read(report) : recentLog(gameDir, s.roundStart());
            String title = report != null ? title(evidence) : null;
            boolean different = title != null && !title.equals(s.crash());
            Set<String> learned = evidence == null ? Set.of() : neededJars(evidence, modsFolder, s.disabled());
            // A crash over a class or mod from a jar Strip Mine switched off can never be the original crash: that
            // one happened with every mod on.
            if (different || !learned.isEmpty()) {
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
        Map<String, ModJar> jars = readJars(modsFolder, s.disabled());
        List<String> plan = plan(next, jars, new HashSet<>(s.keepOn()), next.size() / 2);
        if (plan.isEmpty()) return finish(modsFolder, checkEngineFolder, s, Status.STUCK, next, notes);
        State pending = s.retry(s.keepOn(), 0).with(Status.PENDING, s.round() + 1, next, plan, s.disabled(), 0, notes);
        save(checkEngineFolder, pending);
        return apply(modsFolder, checkEngineFolder, pending);
    }

    /** Plans the current round again (game closed), e.g. after an update to how rounds are split. */
    public static State replan(Path modsFolder, Path checkEngineFolder) throws IOException {
        State s = load(checkEngineFolder);
        if (s == null || !s.active()) return s;
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
                    + "|Outdated mod: ([a-z0-9_.-]+)|Wrong version of ([a-z0-9_.-]+) for");

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
            Set<String> offIds = new HashSet<>();
            for (String f : out) if (jars.containsKey(f)) offIds.addAll(ids(jars.get(f)));
            // An id another switched-on jar still provides (a duplicate) doesn't go missing.
            Set<String> stillOn = new HashSet<>();
            for (ModJar j : jars.values()) if (!out.contains(j.fileName())) stillOn.addAll(ids(j));
            for (ModJar j : jars.values()) {
                if (out.contains(j.fileName()) || isCheckEngine(j)) continue;
                boolean needs = j.mods().stream().flatMap(m -> m.dependencies().stream())
                        .anyMatch(d -> d.type() == Dependency.Type.REQUIRED && offIds.contains(d.modId())
                                && !stillOn.contains(d.modId()));
                if (needs) {
                    out.add(j.fileName());
                    changed = true;
                }
            }
        }
        return out;
    }

    // ---- files ----

    /** Renames this round's jars to .disabled and switches back on the ones Strip Mine had off but no longer needs. */
    static State apply(Path modsFolder, Path checkEngineFolder, State s) throws IOException {
        Set<String> want = new LinkedHashSet<>(s.toDisable());
        List<String> nowOff = new ArrayList<>();
        for (String f : s.disabled()) {
            if (want.contains(f)) {
                nowOff.add(f);
            } else {
                rename(modsFolder.resolve(f + DISABLED), modsFolder.resolve(f));
            }
        }
        for (String f : want) {
            if (nowOff.contains(f)) continue;
            Path jar = modsFolder.resolve(f);
            if (!Files.isRegularFile(jar)) continue; // moved or deleted by the player: nothing to switch off
            rename(jar, modsFolder.resolve(f + DISABLED));
            nowOff.add(f);
            // Save after every rename, so a crash halfway can still put everything back.
            save(checkEngineFolder, s.with(Status.PENDING, s.round(), s.suspects(), s.toDisable(), nowOff, 0, s.notes()));
        }
        State running = s.with(Status.RUNNING, s.round(), s.suspects(), s.toDisable(), nowOff,
                System.currentTimeMillis(), s.notes());
        save(checkEngineFolder, running);
        return running;
    }

    private static State finish(Path modsFolder, Path checkEngineFolder, State s, Status status, List<String> left,
                                List<String> notes) throws IOException {
        restore(modsFolder, s.disabled());
        State done = s.with(status, s.round(), left, List.of(), List.of(), s.roundStart(), notes);
        save(checkEngineFolder, done);
        return done;
    }

    private static void restore(Path modsFolder, List<String> disabled) throws IOException {
        for (String f : disabled) {
            Path off = modsFolder.resolve(f + DISABLED);
            if (Files.isRegularFile(off)) rename(off, modsFolder.resolve(f));
        }
    }

    private static void rename(Path from, Path to) throws IOException {
        if (!Files.exists(from) || Files.exists(to)) return; // never overwrite anything
        Files.move(from, to);
    }

    /** The jars in the mods folder, plus the ones Strip Mine switched off (never the player's own .disabled files). */
    private static Map<String, ModJar> readJars(Path modsFolder, List<String> ours) throws IOException {
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

    private static boolean isCheckEngine(ModJar j) {
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
                    Integer.parseInt(p.getProperty("retries", "0")));
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
