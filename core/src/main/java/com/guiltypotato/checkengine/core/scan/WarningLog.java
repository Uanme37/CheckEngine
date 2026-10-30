package com.guiltypotato.checkengine.core.scan;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Every warning Check Engine has shown in this pack: when it was first seen and whether it's still there
 * (checkengine/warning-log.properties). "Got it" only hides a warning; it never makes one look fixed: a warning is
 * fixed when a launch no longer finds it.
 */
public final class WarningLog {
    static final String FILE = "warning-log.properties";
    /** Fixed warnings beyond this many are forgotten, oldest first. */
    static final int KEEP_FIXED = 30;

    /**
     * @param title     the warning
     * @param firstSeen "2026-10-28T18:02"
     * @param fixedAt   when a launch first didn't find it, or null while it's still there
     */
    public record Entry(String title, Finding.Severity severity, String firstSeen, String fixedAt) {
        public boolean stillThere() {
            return fixedAt == null;
        }
    }

    private WarningLog() {}

    /** Called once per launch with what it found: new ones are added, gone ones marked fixed, back ones reopened. */
    public static List<Entry> update(Path folder, List<Finding> found, String now) throws IOException {
        Map<String, Entry> log = new LinkedHashMap<>();
        for (Entry e : load(folder)) log.put(e.title(), e);
        Map<String, Finding> current = new LinkedHashMap<>();
        for (Finding f : found) current.putIfAbsent(f.title(), f);
        for (Entry e : List.copyOf(log.values())) {
            if (!current.containsKey(e.title()) && e.stillThere()) {
                log.put(e.title(), new Entry(e.title(), e.severity(), e.firstSeen(), now));
            }
        }
        for (Finding f : current.values()) {
            Entry e = log.get(f.title());
            // a fixed warning that comes back counts from now
            if (e == null || !e.stillThere()) log.put(f.title(), new Entry(f.title(), f.severity(), now, null));
        }
        List<Entry> fixed = log.values().stream().filter(e -> !e.stillThere())
                .sorted(Comparator.comparing(Entry::fixedAt)).toList();
        for (int i = 0; i < fixed.size() - KEEP_FIXED; i++) log.remove(fixed.get(i).title());
        save(folder, new ArrayList<>(log.values()));
        return entries(folder);
    }

    /** Still-there ones first, then fixed ones, newest first within each. */
    public static List<Entry> entries(Path folder) {
        List<Entry> all = new ArrayList<>(load(folder));
        all.sort(Comparator.comparing((Entry e) -> !e.stillThere()).thenComparing(Entry::firstSeen,
                Comparator.reverseOrder()));
        return all;
    }

    static List<Entry> load(Path folder) {
        List<Entry> out = new ArrayList<>();
        Path file = folder.resolve(FILE);
        if (!Files.isRegularFile(file)) return out;
        Properties p = new Properties();
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(in);
        } catch (IOException | IllegalArgumentException e) {
            return out;
        }
        for (int i = 0; p.containsKey(i + ".title"); i++) {
            Finding.Severity sev;
            try {
                sev = Finding.Severity.valueOf(p.getProperty(i + ".severity", "WARNING"));
            } catch (IllegalArgumentException e) {
                sev = Finding.Severity.WARNING;
            }
            out.add(new Entry(p.getProperty(i + ".title"), sev, p.getProperty(i + ".first", "?"),
                    p.getProperty(i + ".fixed")));
        }
        return out;
    }

    private static void save(Path folder, List<Entry> all) throws IOException {
        Properties p = new Properties();
        for (int i = 0; i < all.size(); i++) {
            Entry e = all.get(i);
            p.setProperty(i + ".title", e.title());
            p.setProperty(i + ".severity", e.severity().name());
            p.setProperty(i + ".first", e.firstSeen());
            if (e.fixedAt() != null) p.setProperty(i + ".fixed", e.fixedAt());
        }
        Files.createDirectories(folder);
        try (Writer out = Files.newBufferedWriter(folder.resolve(FILE), StandardCharsets.UTF_8)) {
            p.store(out, "Check Engine: warnings it has shown here, still there or fixed");
        }
    }
}
