package com.guiltypotato.checkengine.core.scan;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * How long the last few launches took to reach the title screen (or "server ready"), and what changed in the mods
 * before each one: checkengine/launch-history.properties, the last {@link #KEEP} launches. From it comes the pack's
 * usual start time and whether the last start was clearly slower. It never says which mod is to blame: a slower start
 * is shown next to what changed, and the player decides.
 */
public final class LaunchHistory {
    static final String FILE = "launch-history.properties";
    /** The mods of the newest launch in the history, to tell what changed before the next one. */
    static final String MODS_FILE = "launch-history-mods.properties";
    static final int KEEP = 10;
    /** A launch after this many mod changes is a pack update: the first start after one isn't a fair "usual". */
    static final int BIG_CHANGE = 3;
    /** Fewer usual launches than this and there's no usual time yet. */
    static final int MIN_USUAL = 3;
    /** Fewer usual launches than this and "slower than usual" isn't said yet: 3 starts are too few to judge by. */
    static final int MIN_SLOWER = 5;
    /** Clearly slower: at least this much longer... */
    static final long SLOWER_MS = 20_000;
    /** ...and at least this many quarters of the usual time longer (25%). */
    static final double SLOWER_SHARE = 0.25;

    /**
     * @param when     "2026-10-28T18:02"
     * @param ms       from starting Java to the title screen
     * @param changes  how many mods were added, removed or updated since the launch before
     * @param changed  those changes in a few words ("added Create +2 more"), or "" for none
     * @param first    the first launch Check Engine saw (cold caches: not a fair usual)
     */
    public record Launch(String when, long ms, int changes, String changed, boolean first) {
        /** Whether this launch can count towards the usual time. */
        boolean fair() {
            return !first && changes < BIG_CHANGE;
        }
    }

    /**
     * The last start next to the usual one.
     *
     * @param lastMs        the newest launch
     * @param usualMs       the median of the fair launches before it
     * @param changedSince  what changed since the last launch at the usual speed ("added Create +2 more"), or ""
     */
    public record Slower(long lastMs, long usualMs, String changedSince) {
        public long extraMs() {
            return lastMs - usualMs;
        }

        /** "Started 40s slower than usual. Changed since then: added Create +2 more." */
        public String text() {
            String s = "Started " + StartupTimes.secs(Math.max(0, extraMs())) + " slower than usual.";
            return changedSince.isEmpty() ? s : s + " Changed since then: " + changedSince + ".";
        }
    }

    private LaunchHistory() {}

    /**
     * Adds a launch: compares its mods with the newest one in the history, keeps the last {@link #KEEP}. Returns the
     * whole history, oldest first.
     */
    public static List<Launch> record(Path folder, PackSnapshot mods, long ms, String when) throws IOException {
        List<Launch> all = new ArrayList<>(load(folder));
        PackSnapshot before = PackSnapshot.load(folder, MODS_FILE);
        int count = 0;
        String changed = "";
        if (before != null) {
            Changes c = before.changesTo(mods);
            List<Changes.Change> list = c.changes().stream()
                    .filter(x -> !x.id().equals("minecraft") && !x.id().equals("loader")).toList();
            count = list.size();
            changed = summary(list);
        }
        all.add(new Launch(when, ms, count, changed, before == null));
        while (all.size() > KEEP) all.remove(0);
        save(folder, all);
        mods.save(folder, MODS_FILE);
        return all;
    }

    /** "added Create", "updated Jade +2 more", "" when nothing changed. */
    static String summary(List<Changes.Change> changes) {
        if (changes.isEmpty()) return "";
        Changes.Change c = changes.get(0);
        String first = switch (c.type()) {
            case ADDED -> "added " + c.name();
            case REMOVED -> "removed " + c.name();
            case UPDATED -> (c.downgrade() ? "went back a version: " : "updated ") + c.name();
            case CONFIG -> "changed " + c.name();
        };
        return changes.size() == 1 ? first : first + " +" + (changes.size() - 1) + " more";
    }

    public static List<Launch> load(Path folder) {
        Path file = folder.resolve(FILE);
        List<Launch> out = new ArrayList<>();
        if (!Files.isRegularFile(file)) return out;
        Properties p = new Properties();
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(in);
        } catch (IOException | IllegalArgumentException e) {
            return out;
        }
        for (int i = 0; p.containsKey(i + ".ms"); i++) {
            try {
                out.add(new Launch(p.getProperty(i + ".when", "?"), Long.parseLong(p.getProperty(i + ".ms")),
                        Integer.parseInt(p.getProperty(i + ".changes", "0")), p.getProperty(i + ".changed", ""),
                        Boolean.parseBoolean(p.getProperty(i + ".first", "false"))));
            } catch (NumberFormatException ignored) {
                // skip a broken line
            }
        }
        return out;
    }

    private static void save(Path folder, List<Launch> all) throws IOException {
        Properties p = new Properties();
        p.setProperty("noticed", noticed(folder));
        for (int i = 0; i < all.size(); i++) {
            Launch l = all.get(i);
            p.setProperty(i + ".when", l.when());
            p.setProperty(i + ".ms", Long.toString(l.ms()));
            p.setProperty(i + ".changes", Integer.toString(l.changes()));
            p.setProperty(i + ".changed", l.changed());
            p.setProperty(i + ".first", Boolean.toString(l.first()));
        }
        Files.createDirectories(folder);
        try (Writer out = Files.newBufferedWriter(folder.resolve(FILE), StandardCharsets.UTF_8)) {
            p.store(out, "Check Engine: how long the last launches took to start (newest last)");
        }
    }

    /** The usual start time: the median of the fair launches in {@code launches}, or -1 with fewer than 3. */
    public static long usual(List<Launch> launches) {
        List<Long> times = new ArrayList<>(launches.stream().filter(Launch::fair).map(Launch::ms).toList());
        if (times.size() < MIN_USUAL) return -1;
        times.sort(null);
        int n = times.size();
        return n % 2 == 1 ? times.get(n / 2) : (times.get(n / 2 - 1) + times.get(n / 2)) / 2;
    }

    /** Whether {@code ms} is clearly slower than {@code usual}: 20 s more and a quarter more. */
    static boolean clearlySlower(long ms, long usual) {
        return usual > 0 && ms - usual >= SLOWER_MS && ms >= usual * (1 + SLOWER_SHARE);
    }

    /** The newest launch next to the usual time of the ones before it, when it was clearly slower; else null. */
    public static Slower slower(List<Launch> launches) {
        if (launches.size() < 2) return null;
        Launch last = launches.get(launches.size() - 1);
        List<Launch> before = launches.subList(0, launches.size() - 1);
        if (before.stream().filter(Launch::fair).count() < MIN_SLOWER) return null;
        long usual = usual(before);
        if (!clearlySlower(last.ms(), usual)) return null;
        // What changed since the newest launch that started at the usual speed (or since the history began)
        int from = 0;
        for (int i = before.size() - 1; i >= 0; i--) {
            if (!clearlySlower(before.get(i).ms(), usual)) {
                from = i + 1;
                break;
            }
        }
        List<String> parts = new ArrayList<>();
        int count = 0;
        for (Launch l : launches.subList(from, launches.size())) {
            if (l.changed().isEmpty()) continue;
            if (parts.isEmpty()) parts.add(l.changed().replaceAll(" \\+\\d+ more$", ""));
            count += l.changes();
        }
        String changed = parts.isEmpty() ? "" : count > 1 ? parts.get(0) + " +" + (count - 1) + " more" : parts.get(0);
        return new Slower(last.ms(), usual, changed);
    }

    /**
     * The slower notice, once per slowdown: returns it the first time this slowdown is seen and remembers it, null
     * after that. A slowdown is new again once a start was back to normal in between, or the mods changed again.
     */
    public static Slower noticeOnce(Path folder) {
        List<Launch> all = load(folder);
        Slower s = slower(all);
        String key = s == null ? "" : key(all);
        if (key.equals(noticed(folder))) return null;
        try {
            Properties p = props(folder);
            p.setProperty("noticed", key);
            try (Writer out = Files.newBufferedWriter(folder.resolve(FILE), StandardCharsets.UTF_8)) {
                p.store(out, "Check Engine: how long the last launches took to start (newest last)");
            }
        } catch (IOException ignored) {
            // shown again next time
        }
        return s;
    }

    /** One slowdown: the launch where it began and the mod changes since. Same key = the same slowdown. */
    private static String key(List<Launch> all) {
        long usual = usual(all.subList(0, all.size() - 1));
        int start = all.size() - 1;
        while (start > 0 && clearlySlower(all.get(start - 1).ms(), usual)) start--;
        StringBuilder sb = new StringBuilder(all.get(start).when());
        for (Launch l : all.subList(start + 1, all.size())) if (l.changes() > 0) sb.append('+').append(l.when());
        return sb.toString();
    }

    private static String noticed(Path folder) {
        return props(folder).getProperty("noticed", "");
    }

    private static Properties props(Path folder) {
        Properties p = new Properties();
        try (Reader in = Files.newBufferedReader(folder.resolve(FILE), StandardCharsets.UTF_8)) {
            p.load(in);
        } catch (IOException | IllegalArgumentException e) {
            // empty
        }
        return p;
    }
}
