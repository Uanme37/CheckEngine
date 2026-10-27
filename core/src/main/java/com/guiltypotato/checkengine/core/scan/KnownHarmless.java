package com.guiltypotato.checkengine.core.scan;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Log lines from other mods that look like problems but aren't: the log checks skip them, so they never get blamed.
 * Ships inside the jar and grows with each release. Each entry was seen in a real pack's log.
 */
public final class KnownHarmless {
    /**
     * @param mod     whose lines these are
     * @param why     why they're harmless, in plain words
     * @param pattern matches the line
     */
    public record Entry(String mod, String why, Pattern pattern) {}

    public static final List<Entry> LOG_LINES = List.of(
            new Entry("Mystical Agriculture",
                    "It makes the models for crops from other mods itself, after the game looks for them.",
                    Pattern.compile("Unable to load model: 'mysticalagriculture:item/[a-z0-9_]+_(?:essence|seeds)'")),
            new Entry("MineColonies",
                    "It names more citizen voice lines than it ships; the citizens just stay quiet.",
                    Pattern.compile("Missing sound for event: minecolonies:citizen\\.")));

    private KnownHarmless() {}

    /** Whether this log line is a known harmless one. */
    public static boolean logLine(String line) {
        for (Entry e : LOG_LINES) if (e.pattern().matcher(line).find()) return true;
        return false;
    }
}
