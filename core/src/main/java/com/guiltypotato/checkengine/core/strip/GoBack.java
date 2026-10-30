package com.guiltypotato.checkengine.core.strip;

import com.guiltypotato.checkengine.core.model.ModJar;
import com.guiltypotato.checkengine.core.model.Side;
import com.guiltypotato.checkengine.core.scan.Changes;
import com.guiltypotato.checkengine.core.scan.ConfigCheckpoint;
import com.guiltypotato.checkengine.core.scan.KeptJars;
import com.guiltypotato.checkengine.core.scan.PackSnapshot;
import com.guiltypotato.checkengine.core.scan.ScanOptions;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * "Go back to when it worked": everything since the last launch that worked, undone in one go. New mods are switched
 * off (with what needs them), updated mods get their old jar back from the kept links (KeptJars) and the new one
 * switched off, removed mods come back from the kept links, and the configs go back (ConfigCheckpoint). Nothing is
 * deleted: jars are renamed to .disabled, changed configs move to checkengine/config-undo-&lt;date&gt;/, and
 * {@link #putBack} undoes all of it.
 */
public final class GoBack {
    /** checkengine/go-back.properties: what the last Go back did, for Put it back. */
    public static final String FILE = "go-back.properties";
    /** Old jars to put in once the new jar of the same name is out of the way (it was locked). */
    static final String PENDING_FILE = "go-back-pending.txt";

    /** An old version to put back: {@code oldJar} from the kept links, and {@code newJar} to switch off (or null). */
    public record Restore(String name, String oldJar, String newJar) {}

    /**
     * @param turnOff  new jars to switch off (with the jars that need them)
     * @param restore  old versions that can be put back (updated, or removed)
     * @param notKept  "Create 6.0.4": changed mods whose old jar wasn't kept, so they stay as they are
     * @param configs  config files that go back
     * @param note     why no old jars were kept at all ("on another drive"), or null
     * @param since    when the pack last worked ("2026-10-28T10:00"), or null
     */
    public record Plan(List<String> turnOff, List<Restore> restore, List<String> notKept,
                       List<ConfigCheckpoint.Change> configs, String note, String since) {
        /** Whether there are mods to change (not only configs). */
        public boolean hasMods() {
            return !turnOff.isEmpty() || !restore.isEmpty();
        }

        /** "28 Oct 10:00" for the header, or null when that isn't known. */
        public String lastWorkedShort() {
            String s = lastWorked();
            return s == null ? null : s.substring("Last worked: ".length());
        }

        /** "Last worked: 28 Oct 10:00", or null when that isn't known. */
        public String lastWorked() {
            if (since == null) return null;
            try {
                return "Last worked: " + LocalDateTime.parse(since).format(
                        java.time.format.DateTimeFormatter.ofPattern("d MMM HH:mm", java.util.Locale.ENGLISH));
            } catch (RuntimeException e) {
                return "Last worked: " + since.replace('T', ' ');
            }
        }

        public boolean isEmpty() {
            return turnOff.isEmpty() && restore.isEmpty() && configs.isEmpty();
        }

        /** "Turn off 2 new mods, put back the old version of 3 mods, put back 4 config files" */
        public String summary() {
            List<String> parts = new ArrayList<>();
            if (!turnOff.isEmpty()) parts.add("turn off " + count(turnOff.size(), "new mod"));
            long updated = restore.stream().filter(r -> r.newJar() != null).count();
            long removed = restore.size() - updated;
            if (updated > 0) parts.add("put back the old version of " + count((int) updated, "mod"));
            if (removed > 0) parts.add("put back " + count((int) removed, "removed mod"));
            long added = configs.stream().filter(c -> c.kind() == ConfigCheckpoint.Kind.ADDED).count();
            if (configs.size() > added) parts.add("put back " + count((int) (configs.size() - added), "config file"));
            if (added > 0) parts.add("move away " + count((int) added, "new config file"));
            if (parts.isEmpty()) return "Nothing to go back to.";
            String s = String.join(", ", parts);
            return Character.toUpperCase(s.charAt(0)) + s.substring(1);
        }

        /** One line per thing that changes, in player words, then what can't be put back. */
        public List<String> lines() {
            List<String> out = new ArrayList<>();
            for (String j : turnOff) out.add("Turn off " + j);
            for (Restore r : restore) {
                out.add(r.newJar() != null ? "Put back the old " + r.name() + " (" + r.oldJar() + ")"
                        : "Put back " + r.name() + " (" + r.oldJar() + ")");
            }
            for (String n : notKept) out.add(n + ": old version not kept, can't put it back");
            for (ConfigCheckpoint.Change c : configs) {
                out.add(switch (c.kind()) {
                    case CHANGED -> "Put back " + c.path();
                    case REMOVED -> "Bring back " + c.path();
                    case ADDED -> "Move away " + c.path() + " (new since it worked)";
                });
            }
            return out;
        }
    }

    private GoBack() {}

    /** What Go back would do now. Empty when there's no last good launch. */
    public static Plan plan(Path gameDir, Path checkEngineFolder) throws IOException {
        Path mods = gameDir.resolve("mods");
        PackSnapshot last = PackSnapshot.load(checkEngineFolder);
        String note = KeptJars.note(checkEngineFolder);
        if (last == null || !Files.isDirectory(mods)) {
            return new Plan(List.of(), List.of(), List.of(), List.of(), note, last == null ? null : last.time());
        }
        List<String> turnOff = UndoNewMods.plan(mods, checkEngineFolder);
        Map<String, ModJar> jars = StripMine.readJars(mods, List.of());
        PackSnapshot now = PackSnapshot.of(new ArrayList<>(jars.values()),
                new ScanOptions(Side.CLIENT, last.minecraft(), last.loader(),
                        Set.of()));
        List<Restore> restore = new ArrayList<>();
        List<String> notKept = new ArrayList<>();
        Set<String> seenOld = new LinkedHashSet<>();
        for (var c : last.changesTo(now).changes()) {
            if (c.type() != Changes.Type.UPDATED
                    && c.type() != Changes.Type.REMOVED) continue;
            PackSnapshot.Entry old = last.mods().get(c.id());
            if (old == null) continue; // Minecraft or the loader: not something Go back can change
            // Never Check Engine itself: going back must not put an older Check Engine in charge of the pack
            if (c.id().equals("checkengine") || old.file().toLowerCase(java.util.Locale.ROOT).startsWith("checkengine")) continue;
            if (!seenOld.add(old.file())) continue; // one jar with several mods
            String newJar = c.type() == Changes.Type.UPDATED ? c.file() : null;
            // Same jar as when it worked, only its version read differently ("${version}" in one reader): unchanged
            if (old.file().equals(newJar) && unchanged(checkEngineFolder, mods.resolve(newJar), old.version(),
                    c.to())) continue;
            if (KeptJars.usable(checkEngineFolder, old.file()) != null
                    || newJar == null && Files.isRegularFile(mods.resolve(old.file() + StripMine.DISABLED))) {
                restore.add(new Restore(old.name(), old.file(), newJar));
            } else {
                notKept.add(old.name() + " " + old.version());
            }
        }
        List<ConfigCheckpoint.Change> configs = ConfigCheckpoint.changes(gameDir, checkEngineFolder);
        return new Plan(turnOff, restore, notKept, configs, note, last.time());
    }

    /** Whether {@code live} is still the jar that was kept (or its version is just a placeholder that wasn't read). */
    private static boolean unchanged(Path checkEngineFolder, Path live, String oldVersion, String newVersion) {
        if (String.valueOf(oldVersion).contains("${") || String.valueOf(newVersion).contains("${")) return true;
        Path kept = KeptJars.usable(checkEngineFolder, live.getFileName().toString());
        try {
            return kept != null && Files.isRegularFile(live) && (Files.isSameFile(kept, live)
                    || Files.size(kept) == Files.size(live) && Files.mismatch(kept, live) == -1);
        } catch (IOException e) {
            return false;
        }
    }

    /** Mods and configs, now (the CLI, the window before the game loads). */
    public static List<String> apply(Path gameDir, Path checkEngineFolder, Plan plan) throws IOException {
        return apply(gameDir, checkEngineFolder, plan, true, false);
    }

    /**
     * Does the plan. The record for Put it back is written first and kept up to date, so a Go back cut short can still
     * be put back. Returns the jars the game holds open (NeoForge 26.1): those are switched off, and their old versions
     * put in, at the next start.
     *
     * @param configs      false: "Mods only", the configs stay as they are
     * @param configsLater true when the game is running: mods save their configs as the game closes, so the configs
     *                     go back at the next start instead (applyPending)
     */
    public static List<String> apply(Path gameDir, Path checkEngineFolder, Plan plan, boolean configs,
                                     boolean configsLater) throws IOException {
        Path mods = gameDir.resolve("mods");
        List<String> off = new ArrayList<>(plan.turnOff());
        for (Restore r : plan.restore()) if (r.newJar() != null && !off.contains(r.newJar())) off.add(r.newJar());
        List<String> putIn = new ArrayList<>();
        List<String> on = new ArrayList<>();
        for (Restore r : plan.restore()) {
            boolean switchedOff = r.newJar() == null && Files.isRegularFile(mods.resolve(r.oldJar() + StripMine.DISABLED))
                    && !Files.exists(mods.resolve(r.oldJar()));
            if (switchedOff) on.add(r.oldJar());
            else if (KeptJars.usable(checkEngineFolder, r.oldJar()) != null) putIn.add(r.oldJar());
        }
        boolean withConfigs = configs && !plan.configs().isEmpty();
        Path undo = withConfigs ? ConfigCheckpoint.undoFolder(checkEngineFolder) : null;
        Properties p = new Properties();
        p.setProperty("time", LocalDateTime.now().withNano(0).toString());
        p.setProperty("off", String.join("|", off));
        p.setProperty("in", String.join("|", putIn));
        p.setProperty("on", String.join("|", on));
        if (undo != null) {
            p.setProperty("configs", gameDir.relativize(undo).toString().replace('\\', '/'));
            p.setProperty("added_configs", String.join("|", plan.configs().stream()
                    .filter(c -> c.kind() == ConfigCheckpoint.Kind.REMOVED).map(ConfigCheckpoint.Change::path).toList()));
            if (configsLater) p.setProperty("configs_pending", "true");
        }
        // The record first: whatever happens next, Put it back knows everything Go back may have touched
        store(checkEngineFolder.resolve(FILE), p);
        // The pack this session saved as "worked" isn't the pack on disk any more
        com.guiltypotato.checkengine.core.scan.GoodLaunch.discardPending(checkEngineFolder);

        List<String> locked = StripMine.switchOff(mods, off);
        List<String> later = new ArrayList<>();
        for (String f : on) StripMine.rename(mods.resolve(f + StripMine.DISABLED), mods.resolve(f));
        for (String f : putIn) {
            Path kept = KeptJars.usable(checkEngineFolder, f);
            if (kept == null) continue;
            if (Files.exists(mods.resolve(f))) later.add(f); // the new jar has the same name and is still locked
            else putIn(kept, mods.resolve(f));
        }
        if (!later.isEmpty()) {
            Files.createDirectories(checkEngineFolder);
            Files.write(checkEngineFolder.resolve(PENDING_FILE), later, StandardCharsets.UTF_8);
        }
        if (undo != null && !configsLater) ConfigCheckpoint.restore(gameDir, checkEngineFolder, undo);
        return locked;
    }

    /** Properties written to a temporary file first, then moved over the real one in one step. */
    static void store(Path file, Properties p) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (Writer out = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            p.store(out, "Check Engine: what Go back did (Put it back undoes it)");
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * A kept jar back into mods/: a second hard link (no space), or a copy of that one jar when links don't work
     * (copied next to it first, then moved in, so a cut-short copy is never a broken jar in mods/).
     */
    private static void putIn(Path kept, Path target) throws IOException {
        try {
            Files.createLink(target, kept);
        } catch (IOException | UnsupportedOperationException e) {
            Path part = target.resolveSibling(target.getFileName() + ".part");
            Files.copy(kept, part, StandardCopyOption.REPLACE_EXISTING);
            Files.move(part, target, StandardCopyOption.ATOMIC_MOVE);
        }
    }

    /**
     * Call before the loader opens the mods folder (after StripMine.applyPending): puts in the old jars that had to
     * wait for a locked jar of the same name.
     */
    public static void applyPending(Path modsFolder, Path checkEngineFolder) {
        applyPendingConfigs(modsFolder.getParent(), checkEngineFolder);
        Path file = checkEngineFolder.resolve(PENDING_FILE);
        if (!Files.isRegularFile(file)) return;
        try {
            List<String> still = new ArrayList<>();
            for (String f : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (f.isBlank()) continue;
                Path kept = KeptJars.usable(checkEngineFolder, f);
                if (kept == null) continue;
                if (Files.exists(modsFolder.resolve(f))) still.add(f);
                else putIn(kept, modsFolder.resolve(f));
            }
            if (still.isEmpty()) Files.deleteIfExists(file); // our own note, done
            else Files.write(file, still, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // try again next start
        }
    }

    /** A Go back from inside the game: its configs go back now, before any mod reads them. */
    public static void applyPendingConfigs(Path gameDir, Path checkEngineFolder) {
        Path file = checkEngineFolder.resolve(FILE);
        Properties p = load(file);
        if (p == null || !"true".equals(p.getProperty("configs_pending"))) return;
        try {
            Path undo = inside(gameDir, p.getProperty("configs"));
            if (undo != null) ConfigCheckpoint.restore(gameDir, checkEngineFolder, undo);
            p.remove("configs_pending");
            store(file, p);
        } catch (IOException | RuntimeException ignored) {
            // tried again next start
        }
    }

    private static Properties load(Path file) {
        if (!Files.isRegularFile(file)) return null;
        Properties p = new Properties();
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(in);
            return p;
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
    }

    /** A path from one of Check Engine's own files, only when it stays inside {@code dir} (never "../.."). */
    static Path inside(Path dir, String name) {
        if (name == null || name.isBlank()) return null;
        Path base = dir.toAbsolutePath().normalize();
        Path p = base.resolve(name).normalize();
        return p.startsWith(base) && !p.equals(base) ? p : null;
    }

    /** Whether there's a Go back to put back. */
    public static boolean done(Path checkEngineFolder) {
        return Files.isRegularFile(checkEngineFolder.resolve(FILE));
    }

    /**
     * What Put it back would do, in player words ("Turn 3 mods back on, turn off 2 old versions, put back your
     * configs"), with when Go back ran ("2026-10-28 16:40"); null when there's nothing to put back.
     */
    public static String undoSummary(Path checkEngineFolder) {
        Properties p = new Properties();
        try (Reader in = Files.newBufferedReader(checkEngineFolder.resolve(FILE), StandardCharsets.UTF_8)) {
            p.load(in);
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        int on = split(p.getProperty("off")).size();
        int old = split(p.getProperty("in")).size() + split(p.getProperty("on")).size();
        if (on > 0) parts.add("turn " + count(on, "mod") + " back on");
        if (old > 0) parts.add("turn off " + count(old, "old version") + " again");
        if (p.getProperty("configs") != null) parts.add("put back your configs");
        if (parts.isEmpty()) return null;
        String s = String.join(", ", parts);
        return Character.toUpperCase(s.charAt(0)) + s.substring(1) + " (Go back ran "
                + p.getProperty("time", "?").replace('T', ' ') + ")";
    }

    /**
     * Undoes the last Go back: the old jars it put in go off again (.disabled), the jars it switched off come back on,
     * and the configs it moved away come back. Returns what it put back, in player words.
     */
    public static List<String> putBack(Path gameDir, Path checkEngineFolder) throws IOException {
        Path mods = gameDir.resolve("mods");
        Path file = checkEngineFolder.resolve(FILE);
        Properties p = load(file);
        if (p == null) return List.of();
        List<String> out = new ArrayList<>();
        Files.deleteIfExists(checkEngineFolder.resolve(PENDING_FILE));
        List<String> off = names(mods, p.getProperty("off"));
        // jars Go back couldn't switch off yet (locked): they must stay on now
        StripMine.removePending(mods, off);
        List<String> leftIn = new ArrayList<>(), leftOn = new ArrayList<>(), leftOff = new ArrayList<>();
        // The old jars first: an old jar can have the same name as the new one
        for (String f : names(mods, p.getProperty("in"))) {
            if (!offAgain(mods, checkEngineFolder, f, "Turned off the old " + f, out)) leftIn.add(f);
        }
        for (String f : names(mods, p.getProperty("on"))) {
            if (!offAgain(mods, checkEngineFolder, f, "Turned " + f + " off again", out)) leftOn.add(f);
        }
        for (String f : off) {
            Path offJar = mods.resolve(f + StripMine.DISABLED), live = mods.resolve(f);
            try {
                if (!Files.isRegularFile(offJar)) continue; // not off (any more): nothing to do
                if (!Files.exists(live)) {
                    Files.move(offJar, live);
                    out.add("Turned " + f + " back on");
                } else if (!same(offJar, live)) {
                    leftOff.add(f); // another file has that name now: never overwrite it
                }
            } catch (IOException e) {
                leftOff.add(f);
            }
        }
        boolean configsLeft = false;
        Path undo = inside(gameDir, p.getProperty("configs"));
        if (undo != null && !"true".equals(p.getProperty("configs_pending"))) {
            Path replaced = ConfigCheckpoint.undoFolder(checkEngineFolder).resolveSibling(
                    ConfigCheckpoint.undoFolder(checkEngineFolder).getFileName() + "-before-put-back");
            // configs Go back brought back that the player had removed: move them aside, nothing deleted
            for (String c : split(p.getProperty("added_configs"))) {
                Path live = inside(gameDir, c);
                if (live == null || !Files.isRegularFile(live)) continue;
                moveAside(live, replaced.resolve(c));
            }
            if (Files.isDirectory(undo)) {
                try (var s = Files.walk(undo)) {
                    for (Path f : s.filter(Files::isRegularFile).toList()) {
                        String rel = undo.relativize(f).toString().replace('\\', '/');
                        Path live = inside(gameDir, rel);
                        if (live == null) continue;
                        try {
                            // what's there now (changed since Go back) goes aside first: nothing is overwritten
                            if (Files.isRegularFile(live)) moveAside(live, replaced.resolve(rel));
                            Files.createDirectories(live.getParent());
                            Files.move(f, live);
                            out.add("Put back your " + rel);
                        } catch (IOException e) {
                            configsLeft = true;
                        }
                    }
                }
            }
        }
        if (leftIn.isEmpty() && leftOn.isEmpty() && leftOff.isEmpty() && !configsLeft) {
            Files.deleteIfExists(file); // our own record, not a player file
        } else { // keep what's left, so a second Put it back can finish it
            p.setProperty("in", String.join("|", leftIn));
            p.setProperty("on", String.join("|", leftOn));
            p.setProperty("off", String.join("|", leftOff));
            if (!configsLeft) p.remove("configs");
            store(file, p);
        }
        return out;
    }

    /**
     * Switches {@code f} off again. A .disabled file of that name already there (an update that kept the file name,
     * or a second round trip) is never overwritten: when it's the same jar (one more link to it, or a copy), this link
     * goes; when it's another jar, this one gets a name of its own. Returns false when it couldn't be done.
     */
    private static boolean offAgain(Path mods, Path checkEngineFolder, String f, String said, List<String> out) {
        Path jar = mods.resolve(f), disabled = mods.resolve(f + StripMine.DISABLED);
        try {
            if (!Files.isRegularFile(jar)) return true; // gone already: nothing to switch off
            if (!Files.exists(disabled)) {
                Files.move(jar, disabled);
            } else if (same(jar, disabled) || keptCopy(checkEngineFolder, f, jar)) {
                Files.delete(jar); // a second link (or copy) of a jar that's still kept: nothing is lost
            } else {
                Files.move(jar, unique(mods, f));
            }
            out.add(said);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Whether {@code jar} is the kept jar of that name (a link to it, or the same bytes). */
    private static boolean keptCopy(Path checkEngineFolder, String f, Path jar) throws IOException {
        Path kept = KeptJars.usable(checkEngineFolder, f);
        return kept != null && same(kept, jar);
    }

    private static boolean same(Path a, Path b) throws IOException {
        return Files.isSameFile(a, b) || Files.size(a) == Files.size(b) && Files.mismatch(a, b) == -1;
    }

    /** "create-6.0.3.jar.go-back-2.disabled": a free name the loader skips. */
    private static Path unique(Path mods, String f) {
        for (int i = 1; ; i++) {
            Path p = mods.resolve(f + ".go-back-" + i + StripMine.DISABLED);
            if (!Files.exists(p)) return p;
        }
    }

    private static void moveAside(Path live, Path aside) throws IOException {
        Files.createDirectories(aside.getParent());
        Path to = aside;
        for (int i = 2; Files.exists(to); i++) to = aside.resolveSibling(aside.getFileName() + "." + i);
        Files.move(live, to);
    }

    /** Jar names from the record, only plain file names in mods/ (never a path out of it). */
    private static List<String> names(Path mods, String s) {
        return split(s).stream().filter(n -> inside(mods, n) != null && !n.contains("/") && !n.contains("\\"))
                .toList();
    }

    private static List<String> split(String s) {
        return s == null || s.isBlank() ? List.of() : Arrays.asList(s.split("\\|"));
    }

    private static String count(int n, String what) {
        return n + " " + what + (n == 1 ? "" : "s");
    }
}
