package com.guiltypotato.checkengine.core.scan;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Check Engine's own startup cost, in two numbers: what holds up loading or the game's thread, and what runs in the
 * background. The early plugin and the mod share this class. Time Check Engine's own window is open isn't counted: wrap
 * only the work, not the waiting for the player.
 */
public final class CostClock implements AutoCloseable {
    public static final String FILE = "startup-cost.txt";

    private record Sum(boolean holds, long wallNanos, long cpuNanos) {
        Sum plus(Sum o) {
            return new Sum(holds, wallNanos + o.wallNanos, cpuNanos + o.cpuNanos);
        }
    }

    private static final Map<String, Sum> SUMS = new ConcurrentHashMap<>();

    private final String name;
    private final boolean holds;
    private final long startWall = System.nanoTime();
    private final long startCpu = cpu();

    private CostClock(String name, boolean holds) {
        this.name = name;
        this.holds = holds;
    }

    /** Work the game waits for. Use with try-with-resources. */
    public static CostClock holding(String name) {
        return new CostClock(name, true);
    }

    /** Work on Check Engine's own background threads. */
    public static CostClock background(String name) {
        return new CostClock(name, false);
    }

    @Override
    public void close() {
        SUMS.merge(name, new Sum(holds, System.nanoTime() - startWall, cpu() - startCpu), Sum::plus);
    }

    /** This thread's CPU time, or 0 where Java can't tell. */
    private static long cpu() {
        try {
            ThreadMXBean bean = ManagementFactory.getThreadMXBean();
            return bean.isCurrentThreadCpuTimeSupported() ? bean.getCurrentThreadCpuTime() : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** One line for the log and /checkengine startup: "Check Engine's own cost: game waited 0.9 s, background 3.2 s". */
    public static String summary() {
        long held = 0;
        long background = 0;
        for (Sum s : SUMS.values()) {
            if (s.holds()) held += s.wallNanos();
            else background += s.wallNanos();
        }
        return "Check Engine's own cost: game waited " + StartupTimes.time(held / 1_000_000) + ", background "
                + StartupTimes.time(background / 1_000_000);
    }

    /** The full list, for checkengine/startup-cost.txt. */
    public static String report() {
        StringBuilder sb = new StringBuilder(summary()).append("\n\nGame waited for (time, CPU time):\n");
        append(sb, true);
        sb.append("\nIn the background (time, CPU time):\n");
        append(sb, false);
        return sb.toString();
    }

    private static void append(StringBuilder sb, boolean holds) {
        new TreeMap<>(SUMS).forEach((name, s) -> {
            if (s.holds() != holds) return;
            sb.append(String.format(java.util.Locale.ROOT, "  %-28s %s, %s%n", name,
                    StartupTimes.time(s.wallNanos() / 1_000_000), StartupTimes.time(s.cpuNanos() / 1_000_000)));
        });
    }

    /** Saves the list so far; called again as background work finishes. */
    public static void save(Path checkEngineFolder) {
        try {
            Files.createDirectories(checkEngineFolder);
            Files.writeString(checkEngineFolder.resolve(FILE), report(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            // only a report: the game goes on
        }
    }

    /** First line of the saved list, for /checkengine startup, or null. */
    public static String lastSummary(Path checkEngineFolder) {
        try {
            return Files.readAllLines(checkEngineFolder.resolve(FILE), StandardCharsets.UTF_8).get(0);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** For tests. */
    static void reset() {
        SUMS.clear();
    }
}
