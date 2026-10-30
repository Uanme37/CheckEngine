package com.guiltypotato.checkengine.core.intercept;

import com.guiltypotato.checkengine.core.crash.CrashDoctor;
import com.guiltypotato.checkengine.core.crash.CrashDoctor.LastCrash;
import com.guiltypotato.checkengine.core.scan.Changes;
import com.guiltypotato.checkengine.core.scan.Finding;
import com.guiltypotato.checkengine.core.scan.PackSnapshot;
import com.guiltypotato.checkengine.core.scan.RootProblem;
import com.guiltypotato.checkengine.core.strip.StripMine;
import com.guiltypotato.checkengine.core.strip.StripMineHelper;
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
        MAY_FAIL,
        /** Strip Mine finished: here's the mod behind the crash. */
        FOUND
    }

    public enum Choice { QUIT, CONTINUE, STRIP_MINE }

    /**
     * What the window says.
     *
     * @param problems one line per problem, the first is the biggest
     * @param details  extra lines under the first problem (what it stops)
     * @param changed  "+ Create Addon X 2.4.0" lines: what changed since the pack last worked
     * @param fixes    what to do, one per problem (same order), duplicates removed
     * @param report    the full report "Show full error" opens, or null
     * @param stripMine starts a Strip Mine search for this crash (the window then offers it), or null
     * @param stripMineMods how many mods a search would go through, for the window's explanation
     * @param stripMineLocked whether CurseForge has the pack locked right now (checked again on Start), or null
     * @param quitFix   applies the fix when the player quits ("QUIT AND FIX IT"), or null for a plain Quit
     * @param goBack    what "Go back to when it worked" would do: the summary first, then one line per change; maybe
     *                  empty
     * @param goBackRun does it (the game then quits), or null
     * @param stripMineConfigs Strip Mine would search the changed config files, not the mods
     */
    /**
     * Go back, as the window offers it.
     *
     * @param lastWorked   "Last worked: 28 Oct 10:00", or null
     * @param choice       both mods and configs changed: offer "Mods only" next to "Mods and configs"
     * @param configsFirst the pack maker's default (go_back_configs): "Mods and configs" first, else "Mods only"
     * @param run          does it (true = with the configs); throws when it couldn't
     */
    public record GoBackOffer(String lastWorked, boolean choice, boolean configsFirst,
                              java.util.function.Consumer<Boolean> run) {}

    public record Message(Level level, List<String> problems, List<String> details, List<String> changed,
                          List<String> fixes, Path report, Runnable stripMine, int stripMineMods,
                          java.util.function.BooleanSupplier stripMineLocked, Runnable quitFix,
                          List<String> goBack, GoBackOffer goBackRun, boolean stripMineConfigs) {
        public Message(Level level, List<String> problems, List<String> details, List<String> changed,
                       List<String> fixes, Path report) {
            this(level, problems, details, changed, fixes, report, null, 0, null, null, List.of(), null, false);
        }

        Message withStripMine(Runnable start, int mods, java.util.function.BooleanSupplier locked) {
            return withStripMine(start, mods, locked, false);
        }

        Message withStripMine(Runnable start, int mods, java.util.function.BooleanSupplier locked, boolean configs) {
            return new Message(level, problems, details, changed, fixes, report, start, mods, locked, quitFix, goBack,
                    goBackRun, configs);
        }

        Message withQuitFix(Runnable fix) {
            return new Message(level, problems, details, changed, fixes, report, stripMine, stripMineMods,
                    stripMineLocked, fix, goBack, goBackRun, stripMineConfigs);
        }

        Message withGoBack(List<String> lines, GoBackOffer run) {
            return new Message(level, problems, details, changed, fixes, report, stripMine, stripMineMods,
                    stripMineLocked, quitFix, lines, run, stripMineConfigs);
        }

        /** True if CurseForge has the pack locked, so Strip Mine can't start yet. */
        public boolean isStripMineLocked() {
            return stripMineLocked != null && stripMineLocked.getAsBoolean();
        }

        /** About how many launches a Strip Mine search takes. */
        public int stripMineRounds() {
            return stripMineMods <= 1 ? 1 : 32 - Integer.numberOfLeadingZeros(stripMineMods - 1);
        }

        public String headline() {
            if (level == Level.FOUND) {
                if (problems.get(0).startsWith(GAVE_UP)) return "STRIP MINE COULDN'T FINISH";
                return problems.size() == 1 ? "STRIP MINE FOUND IT" : "STRIP MINE NARROWED IT DOWN";
            }
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

    /** What Strip Mine found, for the window. */
    static final String GAVE_UP = "Strip Mine couldn't finish the search";

    static Message stripMineResult(StripMine.State done, Path report) {
        return stripMineResult(done, report, null);
    }

    /** As above; with the mods folder, "QUIT AND FIX IT" switches the faulty mod off (and what can't run without it). */
    static Message stripMineResult(StripMine.State done, Path report, Path modsFolder) {
        if (done.gaveUp()) {
            List<String> notes = done.notes();
            return new Message(Level.FOUND, List.of(GAVE_UP), List.of(
                    "Test rounds kept failing in a different way: switching mods off broke other mods.",
                    notes.isEmpty() ? "" : notes.get(notes.size() - 1)), List.of(), List.of("Every mod is switched back "
                    + "on. Make a help file (Crash Doctor's button, or /checkengine export) and send it to the pack's "
                    + "author, with the crash report."), report);
        }
        if (done.configs()) return configResult(done, report, modsFolder);
        String found = done.found();
        List<String> problems = found != null ? List.of(found + " causes the crash")
                : done.suspects().stream().limit(10).map(f -> f + " (one of these)").toList();
        List<String> details = new ArrayList<>();
        if (!done.crash().isEmpty()) details.add("The crash: " + done.crash());
        details.add("Found in " + done.round() + (done.round() == 1 ? " round." : " rounds.")
                + " Every mod is switched back on.");
        if (found == null) {
            return new Message(Level.FOUND, problems, details, List.of(), List.of("These mods need each other, so Strip "
                    + "Mine can't split them. Update them, or remove them one at a time."), report);
        }
        List<String> off = List.of(found);
        if (modsFolder != null) {
            try {
                off = StripMine.switchOffPlan(modsFolder, found);
            } catch (IOException ignored) {
                // just the jar itself
            }
        }
        List<String> others = off.subList(1, off.size());
        String what = others.isEmpty() ? found : found + " and the " + others.size() + " mod" + (others.size() == 1
                ? "" : "s") + " that need it (" + String.join(", ", others.stream().limit(4).toList())
                + (others.size() > 4 ? ", ..." : "") + ")";
        List<String> fixes = List.of(
                "QUIT AND FIX IT switches off " + what + " (renamed to .disabled, not deleted).",
                "Or update it, or send the crash report to its author.");
        Message m = new Message(Level.FOUND, problems, details, List.of(), fixes, report);
        if (modsFolder == null) return m;
        List<String> toSwitch = off;
        return m.withQuitFix(() -> {
            List<String> locked = StripMine.switchOff(modsFolder, toSwitch);
            if (locked.isEmpty()) return;
            // NeoForge 26.1 holds the jars open until the game exits: rename those on the way out.
            Runtime.getRuntime().addShutdownHook(new Thread(() -> StripMine.switchOff(modsFolder, locked)));
        });
    }

    /** What a search over config files found: "QUIT AND FIX IT" puts back that file's last working version. */
    private static Message configResult(StripMine.State done, Path report, Path modsFolder) {
        String found = done.found();
        List<String> details = new ArrayList<>();
        if (!done.crash().isEmpty()) details.add("The crash: " + done.crash());
        details.add("Found in " + done.round() + (done.round() == 1 ? " round." : " rounds.")
                + " Every config file is back as you had it.");
        if (found == null) {
            return new Message(Level.FOUND, done.suspects().stream().limit(10).map(f -> f + " (one of these)").toList(),
                    details, List.of(), List.of("Put these back one at a time, or all at once with Go back to when it worked."),
                    report).withStripMine(null, 0, null, true);
        }
        Message m = new Message(Level.FOUND, List.of(found + " causes the crash"), details, List.of(), List.of(
                "QUIT AND FIX IT puts back " + found + " as it was when the pack last worked. Your version is "
                        + "kept in checkengine/config-undo-<date>, nothing is deleted.",
                "Or open it and undo your last edit there."), report).withStripMine(null, 0, null, true);
        if (modsFolder == null) return m;
        return m.withQuitFix(() -> {
            try {
                com.guiltypotato.checkengine.core.scan.ConfigCheckpoint.restoreOne(modsFolder.getParent(),
                        modsFolder.getParent().resolve("checkengine"), found);
            } catch (IOException ignored) {
                // the file stays as it is; the result says which one it is
            }
        });
    }

    /** The mods of the latest launch, whether it worked or crashed. */
    static final String LAST_LAUNCH_FILE = "last-launch.properties";

    /**
     * Call once per launch with the mods the loader found. Remembers them, and if the last launch crashed and the
     * mods are exactly the same now, returns the "will probably fail again" warning (else null).
     */
    public static Message repeatCrash(Path gameDir, Path checkEngineFolder, PackSnapshot now) {
        // Strip Mine round: crashes are expected and the pack is half switched off on purpose. Start the helper that
        // sets up the next round once this game closes, and stay quiet.
        if (StripMine.running(checkEngineFolder) != null) {
            StripMineHelper.launch(gameDir, gameDir.resolve("mods"), checkEngineFolder);
            return null;
        }
        // Strip Mine just finished. Every mod is back on, so a crash-on-load pack crashes again before the title
        // screen: the answer has to come now. Shown once.
        StripMine.State done = StripMine.finished(checkEngineFolder);
        if (done != null && enabled(checkEngineFolder)) {
            try {
                Path report = checkEngineFolder.resolve("strip-mine-result.txt");
                Message m = stripMineResult(done, report, gameDir.resolve("mods"));
                Files.writeString(report, m.toText() + "\n\n" + String.join("\n", done.notes()) + "\n",
                        StandardCharsets.UTF_8);
                StripMine.clear(checkEngineFolder);
                return m;
            } catch (IOException ignored) {
                // shown on the title screen instead
            }
        }
        PackSnapshot previous = PackSnapshot.load(checkEngineFolder, LAST_LAUNCH_FILE);
        boolean previousStarted = reachedTitleScreen(checkEngineFolder);
        try {
            now.save(checkEngineFolder, LAST_LAUNCH_FILE);
        } catch (IOException ignored) {
            // next launch just can't compare
        }
        if (previous == null || !previous.mods().equals(now.mods())) return null;
        // The last launch got through startup and crashed later, while playing: startup itself works, so warning
        // "startup will fail again" would be a false alarm. Crash Doctor still explains that crash after loading.
        if (previousStarted) return null;
        LastCrash crash = CrashDoctor.peek(gameDir, checkEngineFolder);
        if (crash == null) return null;
        // A random timing crash usually doesn't happen twice: launching again is the fix, so don't warn against it.
        if (Finding.TIMING_CRASH.equals(crash.main().code())) return null;
        Path details;
        try {
            details = crash.save(checkEngineFolder);
        } catch (IOException e) {
            details = crash.file();
        }
        // A pack that crashes while loading never reaches Crash Doctor: offer Strip Mine right here.
        Path mods = gameDir.resolve("mods");
        String title = crash.main().title();
        boolean startup = StripMine.isStartupCrash(crash.explained().description());
        int modCount = 0;
        try (java.util.stream.Stream<Path> jars = Files.list(mods)) {
            modCount = (int) jars.map(p -> p.getFileName().toString().toLowerCase())
                    .filter(n -> n.endsWith(".jar") && !n.startsWith("checkengine")).count();
        } catch (IOException ignored) {
            // the explanation just leaves the numbers out
        }
        // No mod changed since the pack last worked, but several configs did: search those instead.
        PackSnapshot lastGood = PackSnapshot.load(checkEngineFolder);
        List<String> configs = com.guiltypotato.checkengine.core.strip.ConfigStrip.suspects(gameDir, checkEngineFolder,
                lastGood == null ? null : lastGood.changesTo(now));
        if (lastGood != null && !configs.isEmpty()) {
            return mayFail(crash, details).withStripMine(() -> {
                try {
                    com.guiltypotato.checkengine.core.strip.ConfigStrip.start(gameDir, checkEngineFolder, configs, title,
                            startup, false);
                    StripMineHelper.launch(gameDir, mods, checkEngineFolder);
                } catch (IOException ignored) {
                    // nothing to search
                }
            }, configs.size(), () -> false, true);
        }
        return mayFail(crash, details).withStripMine(() -> {
            try {
                StripMine.start(mods, checkEngineFolder, title, startup, false);
                StripMineHelper.launch(gameDir, mods, checkEngineFolder);
            } catch (IOException ignored) {
                // fewer than 2 mods, or the folder can't be written: nothing to search
            }
        }, modCount, () -> StripMine.lockedByCurseForge(gameDir));
    }

    /**
     * Whether the latest launch got to the title screen (or server ready): the mod saves the pack's state then (see
     * GoodLaunch: as the last good launch, or set aside when that session crashed later), so it's newer than the
     * record this check wrote when that launch started.
     */
    static boolean reachedTitleScreen(Path checkEngineFolder) {
        try {
            Path started = checkEngineFolder.resolve(LAST_LAUNCH_FILE);
            if (!Files.isRegularFile(started)) return false;
            for (Path saved : List.of(checkEngineFolder.resolve(com.guiltypotato.checkengine.core.scan.GoodLaunch.TITLE),
                    checkEngineFolder.resolve(PackSnapshot.FILE),
                    checkEngineFolder.resolve(com.guiltypotato.checkengine.core.scan.GoodLaunch.CRASHED),
                    checkEngineFolder.resolve(com.guiltypotato.checkengine.core.scan.GoodLaunch.UNFINISHED),
                    com.guiltypotato.checkengine.core.scan.GoodLaunch.pending(checkEngineFolder)
                            .resolve(PackSnapshot.FILE))) {
                if (Files.isRegularFile(saved)
                        && Files.getLastModifiedTime(saved).compareTo(Files.getLastModifiedTime(started)) >= 0) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            return false;
        }
    }

    static List<String> changedLines(Changes changes) {
        List<String> out = new ArrayList<>();
        if (changes == null) return out;
        for (Changes.Change c : changes.changes()) {
            out.add(switch (c.type()) {
                case ADDED -> "+ " + c.name() + " " + c.to();
                case CONFIG -> "* " + c.to() + " config " + c.name();
                case REMOVED -> "- " + c.name() + " " + c.from();
                case UPDATED -> (c.downgrade() ? "↓ " : "~ ") + c.name() + " " + c.from() + " → " + c.to();
            });
        }
        return out;
    }

    /**
     * Whether the window is on (checkengine/settings.properties, launch_popup). Writes the file with the default
     * the first time, so players can find the switch.
     */
    public static boolean enabled(Path checkEngineFolder) {
        // During a Strip Mine round the pack is half switched off on purpose: its problems aren't the player's.
        if (StripMine.running(checkEngineFolder) != null) return false;
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
        return ask(message, null);
    }

    /**
     * Offers "Go back to when it worked" when anything changed since the pack last worked: new mods off, old versions
     * and removed mods back, configs back, in one click. Not for Strip Mine's own result (it already names the mod).
     */
    static Message withGoBack(Message m, Path gameDir) {
        if (gameDir == null || m.level() == Level.FOUND) return m;
        Path folder = gameDir.resolve("checkengine");
        com.guiltypotato.checkengine.core.strip.GoBack.Plan plan;
        try {
            plan = com.guiltypotato.checkengine.core.strip.GoBack.plan(gameDir, folder);
        } catch (IOException | RuntimeException e) {
            return m;
        }
        if (plan.isEmpty()) return m;
        List<String> lines = new ArrayList<>();
        lines.add(plan.summary());
        lines.addAll(plan.lines());
        if (plan.note() != null) lines.add(plan.note());
        return m.withGoBack(lines, new GoBackOffer(plan.lastWorked(), plan.hasMods() && !plan.configs().isEmpty(),
                com.guiltypotato.checkengine.core.scan.PackInfo.goBackConfigsByDefault(gameDir), configs -> {
            try {
                // the game hasn't loaded anything yet: configs go back now; locked jars at the next start
                com.guiltypotato.checkengine.core.strip.GoBack.apply(gameDir, folder, plan, configs, false);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e); // the window says so
            }
        }));
    }

    /** As {@link #ask(Message)}, with the pack's name and help link from {@code gameDir} (see PackInfo). */
    public static Choice ask(Message message, Path gameDir) {
        try {
            message = withGoBack(message, gameDir);
            Choice c = Popup.show(message, logo(), com.guiltypotato.checkengine.core.scan.PackInfo.load(gameDir));
            if (c == Choice.STRIP_MINE && message.stripMine() != null) {
                message.stripMine().run(); // the helper sets up round 1 once the game has closed
                return Choice.QUIT;
            }
            return c;
        } catch (Throwable t) {
            try {
                return Popup.simple(message);
            } catch (Throwable ignored) {
                return Choice.CONTINUE;
            }
        }
    }

    /** Shows a settings window (memory, Java). Anything that goes wrong means CONTINUE. */
    public static Choice askSetting(SetupCheck.Warning warning, Path checkEngineFolder) {
        try {
            return Popup.showSetting(warning, logo(), checkEngineFolder);
        } catch (Throwable t) {
            return Choice.CONTINUE;
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
