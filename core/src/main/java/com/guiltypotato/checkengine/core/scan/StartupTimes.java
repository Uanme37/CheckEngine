package com.guiltypotato.checkengine.core.scan;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;

/**
 * How long the last launch took and which mods were slowest, measured by the in-game mod and read back by the
 * checker. Saved as startup.properties (for the checker) and startup-report.txt (for people).
 */
public final class StartupTimes {
    public static final String DATA_FILE = "startup.properties";
    public static final String REPORT_FILE = "startup-report.txt";
    /** Mods faster than this aren't worth listing. */
    private static final long LIST_FROM_MS = 250;
    private static final int MAX_LISTED = 15;

    /** One mod's own startup work: the time spent in its setup and registration code. */
    public record ModTime(String id, String name, long ms) {}

    /**
     * @param totalMs     from starting Java to the title screen (or "server ready")
     * @param findingMs   starting Java, finding and checking mods, until mods start loading
     * @param modsMs      mods loading and setting up
     * @param finishingMs the rest: textures, models, sounds and the world data (server)
     * @param mods        every mod's own time, any order
     * @param when        when it was measured, e.g. "2026-10-07T12:30"
     */
    public record Data(long totalMs, long findingMs, long modsMs, long finishingMs, List<ModTime> mods, String when) {
        public Data {
            mods = mods.stream().sorted(Comparator.comparingLong(ModTime::ms).reversed()).toList();
        }
    }

    private StartupTimes() {}

    public static String format(Data d, boolean server) {
        StringBuilder sb = new StringBuilder("Check Engine startup report\n");
        sb.append(server ? "Server ready in " : "Title screen after ").append(time(d.totalMs()))
                .append(d.when() == null ? "" : " (measured " + d.when() + ")").append("\n\n");
        sb.append("Where the time went:\n");
        sb.append(String.format("  %-34s %s%n", "Starting Java and finding mods", time(d.findingMs())));
        sb.append(String.format("  %-34s %s%n", "Loading and setting up mods", time(d.modsMs())));
        sb.append(String.format("  %-34s %s%n", server ? "Loading the world and data" : "Textures, models and sounds",
                time(d.finishingMs())));
        List<ModTime> slow = d.mods().stream().filter(m -> m.ms() >= LIST_FROM_MS).limit(MAX_LISTED).toList();
        sb.append('\n');
        if (slow.isEmpty()) {
            sb.append("No single mod took more than ").append(time(LIST_FROM_MS)).append(" of its own setup time.\n");
        } else {
            sb.append("Slowest mods (their own setup and registration code):\n");
            int i = 1;
            for (ModTime m : slow) sb.append(String.format("  %2d. %-40s %s%n", i++, m.name() + " (" + m.id() + ")", time(m.ms())));
        }
        sb.append("\nThis counts each mod's own setup code. Textures, models and the time Java spends loading a mod's\n"
                + "code are shared work, so a mod with many blocks or items can still slow the last phase down.\n");
        return sb.toString();
    }

    /** "850 ms", "4.2 s", "2 min 05 s" */
    public static String time(long ms) {
        if (ms < 1000) return ms + " ms";
        if (ms < 60_000) return String.format("%.1f s", ms / 1000.0);
        return String.format("%d min %02d s", ms / 60_000, (ms % 60_000) / 1000);
    }

    public static void save(Path folder, Data d, boolean server) throws IOException {
        Files.createDirectories(folder);
        Properties p = new Properties();
        p.setProperty("total_ms", Long.toString(d.totalMs()));
        p.setProperty("finding_ms", Long.toString(d.findingMs()));
        p.setProperty("mods_ms", Long.toString(d.modsMs()));
        p.setProperty("finishing_ms", Long.toString(d.finishingMs()));
        p.setProperty("server", Boolean.toString(server));
        if (d.when() != null) p.setProperty("measured", d.when());
        int i = 0;
        for (ModTime m : d.mods()) {
            if (i >= MAX_LISTED) break;
            p.setProperty("mod." + i + ".id", m.id());
            p.setProperty("mod." + i + ".name", m.name());
            p.setProperty("mod." + i + ".ms", Long.toString(m.ms()));
            i++;
        }
        try (Writer w = Files.newBufferedWriter(folder.resolve(DATA_FILE), StandardCharsets.UTF_8)) {
            p.store(w, "Check Engine: how long the last launch took");
        }
        Files.writeString(folder.resolve(REPORT_FILE), format(d, server), StandardCharsets.UTF_8);
    }

    /** The last measured launch, or null if there isn't one (or it can't be read). */
    public static Data load(Path folder) {
        Path file = folder.resolve(DATA_FILE);
        if (!Files.isRegularFile(file)) return null;
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
            List<ModTime> mods = new ArrayList<>();
            for (int i = 0; p.containsKey("mod." + i + ".id"); i++) {
                mods.add(new ModTime(p.getProperty("mod." + i + ".id"), p.getProperty("mod." + i + ".name"),
                        Long.parseLong(p.getProperty("mod." + i + ".ms"))));
            }
            return new Data(Long.parseLong(p.getProperty("total_ms")), Long.parseLong(p.getProperty("finding_ms")),
                    Long.parseLong(p.getProperty("mods_ms")), Long.parseLong(p.getProperty("finishing_ms")), mods,
                    p.getProperty("measured"));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    public static boolean wasServer(Path folder) {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(folder.resolve(DATA_FILE), StandardCharsets.UTF_8)) {
            p.load(r);
            return Boolean.parseBoolean(p.getProperty("server"));
        } catch (IOException e) {
            return false;
        }
    }
}
