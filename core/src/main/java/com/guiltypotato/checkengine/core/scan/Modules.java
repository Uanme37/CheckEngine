package com.guiltypotato.checkengine.core.scan;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

/**
 * The modpack maker's switches for Check Engine's parts, in config/checkengine-pack.properties. A missing key means on,
 * so a modpack that doesn't know about them behaves as before. A switch only stops what players see and what the part
 * starts by itself: recording what worked, and finishing a search or a Go back already running, never stop.
 */
public final class Modules {
    public enum Part {
        IGNITION("ignition", "windows before the game loads, startup warnings"),
        MECHANIC("mechanic", "the crash screen"),
        PIT_PASS("pit_pass", "server join help"),
        MISFIRE("misfire", "the search for the faulty mod"),
        SCRAPYARD("scrapyard", "warnings before a world loses a mod"),
        PIT_CREW("pit_crew", "modpack maker scans, update notes");

        final String key;
        final String what;

        Part(String key, String what) {
            this.key = key;
            this.what = what;
        }

        public String key() {
            return key;
        }
    }

    /** Every key the file understands, for catching typos ("mechanics=false") and for the example file. */
    static final Set<String> OTHER_KEYS = Set.of("pack_name", "support_link", "recommended_ram", "quiet", "hide_warnings",
            "title_button", "title_button_pos", "go_back_configs", "mode", "step_aside");

    private final Set<Part> off;
    private final boolean stepAside;
    private final boolean modpackMode;
    private final List<String> unknown;

    private Modules(Set<Part> off, boolean stepAside, boolean modpackMode, List<String> unknown) {
        this.off = off;
        this.stepAside = stepAside;
        this.modpackMode = modpackMode;
        this.unknown = unknown;
    }

    /** Never throws: an unreadable file means everything stays on. */
    public static Modules load(Path gameDir) {
        Properties p = gameDir == null ? new Properties() : PackInfo.settings(gameDir);
        Set<Part> off = EnumSet.noneOf(Part.class);
        for (Part part : Part.values()) {
            if (isOff(p.getProperty(part.key))) off.add(part);
        }
        List<String> unknown = new ArrayList<>();
        for (String key : p.stringPropertyNames()) {
            boolean known = OTHER_KEYS.contains(key);
            for (Part part : Part.values()) known |= part.key.equals(key);
            if (!known) unknown.add(key);
        }
        unknown.sort(null);
        return new Modules(off, !isOff(p.getProperty("step_aside")), isModpackMode(p), List.copyOf(unknown));
    }

    public boolean on(Part part) {
        return !off.contains(part);
    }

    /** step_aside=false: Check Engine keeps its own parts even when a dedicated mod is installed. */
    public boolean stepAside() {
        return stepAside;
    }

    public boolean modpackMode() {
        return modpackMode;
    }

    /** Keys in the file that mean nothing to Check Engine, so a typo doesn't fail silently. */
    public List<String> unknownKeys() {
        return unknown;
    }

    /** Only these turn a switch off; anything else stays on, the safe side. */
    static boolean isOff(String value) {
        if (value == null) return false;
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "false", "off", "no", "0" -> true;
            default -> false;
        };
    }

    static boolean isOn(String value) {
        if (value == null) return false;
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "true", "on", "yes", "1" -> true;
            default -> false;
        };
    }

    static boolean isModpackMode(Properties p) {
        return "modpack".equalsIgnoreCase(p.getProperty("mode", "").strip());
    }

    /** The top of boot-report.txt: what's on and off and why, so a switch that seems ignored is checked in seconds. */
    public String reportBlock(List<String> steppedAside) {
        StringBuilder sb = new StringBuilder("Switches in effect (config/" + PackInfo.FILE + "):\n");
        for (Part part : Part.values()) {
            sb.append("  ").append(part.key).append(": ").append(on(part) ? "on" : "OFF, set in the file")
                    .append(" (").append(part.what).append(")\n");
        }
        sb.append("  mode: ").append(modpackMode ? "modpack (players only see what stops the game, and crashes)"
                : "normal").append('\n');
        if (!stepAside) sb.append("  step_aside: false\n");
        steppedAside.forEach(s -> sb.append("  stepping aside for ").append(s).append('\n'));
        if (!unknown.isEmpty()) {
            sb.append("  not understood, check the spelling: ").append(String.join(", ", unknown)).append('\n');
        }
        return sb.append('\n').toString();
    }
}
