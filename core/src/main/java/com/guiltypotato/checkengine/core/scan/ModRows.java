package com.guiltypotato.checkengine.core.scan;

import com.guiltypotato.checkengine.core.model.ModInfo;
import com.guiltypotato.checkengine.core.model.ModJar;
import java.io.IOException;
import java.nio.file.Files;
import com.guiltypotato.checkengine.core.version.ModVersion;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Pit Stop mod list: per mod, the time spent loading it (where the loader lets Check Engine measure it), the
 * blocks and items registered under its id, its file size and whether it's new or updated since the pack last worked.
 * No score and no "heavy" label: the numbers side by side, the player sorts them.
 *
 * @param ms     time spent loading this mod (its own setup steps), or -1 when not measured
 * @param tag    "New", "Updated" or ""
 */
public record ModRows(String id, String name, long ms, int blocks, int items, double mb, String tag) {
    public int blocksAndItems() {
        return blocks + items;
    }

    /** Counts registry ids ("create:cogwheel") by their namespace, which is the mod's id. */
    public static Map<String, Integer> byNamespace(Iterable<String> ids) {
        Map<String, Integer> out = new HashMap<>();
        for (String id : ids) {
            int colon = id.indexOf(':');
            out.merge(colon < 0 ? "minecraft" : id.substring(0, colon), 1, Integer::sum);
        }
        return out;
    }

    /**
     * One row per top-level mod. A file with several mods in it counts its size once, for the first mod.
     *
     * @param times   mod id -> time spent loading it, or null when this loader can't measure it
     * @param changes what changed since the pack last worked, or null
     */
    public static List<ModRows> of(List<ModJar> jars, Map<String, Long> times, Map<String, Integer> blocks,
                                   Map<String, Integer> items, Changes changes) {
        Map<String, String> tags = new HashMap<>();
        if (changes != null) {
            for (Changes.Change c : changes.changes()) {
                if (c.type() == Changes.Type.ADDED) tags.put(c.id(), "New");
                else if (c.type() == Changes.Type.UPDATED) tags.put(c.id(), "Updated");
            }
        }
        // One row per mod: two jars of one mod (a duplicate) show as the newest, the one the loader uses
        Map<String, ModRows> out = new java.util.LinkedHashMap<>();
        Map<String, String> versions = new HashMap<>();
        for (ModJar jar : jars) {
            double mb = size(jar) / (1024.0 * 1024.0);
            boolean first = true;
            for (ModInfo m : jar.mods()) {
                String old = versions.get(m.modId());
                if (old != null && ModVersion.parse(String.valueOf(m.version())).compareTo(ModVersion.parse(old)) <= 0) {
                    first = false;
                    continue;
                }
                long ms = times == null ? -1 : times.getOrDefault(m.modId(), -1L);
                versions.put(m.modId(), String.valueOf(m.version()));
                out.put(m.modId(), new ModRows(m.modId(), m.name() == null ? m.modId() : m.name(), ms,
                        blocks.getOrDefault(m.modId(), 0), items.getOrDefault(m.modId(), 0), first ? mb : 0,
                        tags.getOrDefault(m.modId(), "")));
                first = false;
            }
        }
        return new ArrayList<>(out.values());
    }

    private static long size(ModJar jar) {
        try {
            return Files.size(jar.file());
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    /** "1.2 s · 812 blocks + items · 18.4 MB · Updated" */
    public String details(boolean timed) {
        List<String> parts = new ArrayList<>();
        if (timed) parts.add(ms < 0 ? "-" : StartupTimes.time(ms));
        parts.add(blocksAndItems() + " blocks + items");
        parts.add(mb < 0.1 ? "<0.1 MB" : String.format(java.util.Locale.ROOT, "%.1f MB", mb));
        if (!tag.isEmpty()) parts.add(tag);
        return String.join(" · ", parts);
    }
}
