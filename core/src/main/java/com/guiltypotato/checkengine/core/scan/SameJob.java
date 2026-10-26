package com.guiltypotato.checkengine.core.scan;

import com.guiltypotato.checkengine.core.scan.Finding.Severity;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Two mods doing the same job: two minimaps, two recipe viewers, two inventory sorters. They fight over the same keys,
 * screens or drops, or just cost memory twice. A warning, never an error: some packs pair them on purpose.
 */
public final class SameJob {
    public static final String CODE = "same-job-mods";

    /**
     * @param job    "minimaps", as in "2 minimaps"
     * @param mods   mod ids that do it
     * @param why    what goes wrong with two
     * @param fineTogether pairs that are made to work together (both ids), never reported
     */
    record Job(String job, List<String> mods, String why, Set<Set<String>> fineTogether) {
        Job(String job, List<String> mods, String why) {
            this(job, mods, why, Set.of());
        }
    }

    static final List<Job> JOBS = List.of(
            new Job("minimaps", List.of("xaerominimap", "journeymap", "voxelmap"),
                    "Two maps on screen, two sets of keys, and both keep map data for every world."),
            new Job("recipe viewers", List.of("jei", "roughlyenoughitems", "emi"),
                    "Two item lists on the inventory screen and two recipe screens.",
                    // EMI shows JEI's recipes on purpose when both are installed
                    Set.of(Set.of("jei", "emi"))),
            new Job("inventory sorters", List.of("inventoryprofilesnext", "inventorysorter", "invtweaks"),
                    "Both sort on the same click or key, so items jump around twice."),
            new Job("renderers", List.of("sodium", "embeddium", "rubidium", "optifine"),
                    "They replace the same rendering code; together the game usually crashes or draws wrong."),
            new Job("shader loaders", List.of("iris", "oculus", "optifine"),
                    "Both try to load shader packs; together the game usually crashes or draws wrong."),
            new Job("grave mods", List.of("gravestone", "corpse", "yigd", "tombstone"),
                    "Both grab your items when you die: items can end up split between them, or lost."),
            new Job("dynamic lights mods", List.of("lambdynlights", "sodiumdynamiclights", "ryoamiclights",
                    "dynamiclightsreforged"), "Both light up held torches: double work every frame."),
            new Job("zoom mods", List.of("zoomify", "ok_zoomer", "logical_zoom"),
                    "Two zoom keys that can clash."),
            new Job("look-at info mods", List.of("jade", "wthit", "theoneprobe"),
                    "Two info boxes when you look at a block.",
                    // The One Probe is often kept next to Jade for its machine details
                    Set.of(Set.of("jade", "theoneprobe"))));

    private SameJob() {}

    /** An installed mod: its name and the jar it's in. */
    public record Installed(String name, String jar) {}

    /**
     * One warning per job with 2+ of its mods installed in different jars (one jar can answer to an old name too:
     * Embeddium is also "rubidium", REI carries a JEI stand-in).
     */
    public static List<Finding> check(Map<String, Installed> installed) {
        List<Finding> out = new ArrayList<>();
        for (Job job : JOBS) {
            Set<String> jars = new java.util.HashSet<>();
            List<String> have = job.mods().stream().filter(installed::containsKey)
                    // a stand-in so other mods' plugins work, e.g. REI Plugin Compatibilities' "JEI Stub"
                    .filter(id -> !installed.get(id).name().toLowerCase(java.util.Locale.ROOT).contains("stub"))
                    .filter(id -> jars.add(installed.get(id).jar())).toList();
            if (have.size() < 2) continue;
            if (have.size() == 2 && job.fineTogether().contains(Set.copyOf(have))) continue;
            List<String> names = have.stream().map(id -> installed.get(id).name()).toList();
            out.add(new Finding(Severity.WARNING, CODE, have.size() + " " + job.job() + ": "
                    + String.join(" and ", names), job.why(),
                    "Keep the one you like and remove the other" + (have.size() > 2 ? "s" : "")
                            + ", unless the pack is set up to use them together.", List.of()));
        }
        return out;
    }
}
