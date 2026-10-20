package com.guiltypotato.checkengine.core.scan;

import com.guiltypotato.checkengine.core.scan.Finding.Severity;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Two different mods that are known to go wrong together, even though neither one says so in its mod info (those
 * rules are the loader's job). Ships inside the jar and grows with each release; checked against real packs so a
 * pair many good packs use is never on it.
 */
public final class BadPairs {
    public static final String CODE = "bad-mod-pair";

    /**
     * @param a       mod ids for the first side (any one counts)
     * @param b       mod ids for the second side (any one counts)
     * @param fixedBy mod ids of add-ons that make the two work together: if one is installed, nothing is said
     * @param fixedByName words in a mod's name that mark such an add-on too (all must appear), for add-ons with
     *                    unknown or changing ids
     */
    record Pair(Severity severity, List<String> a, List<String> b, List<String> fixedBy, List<String> fixedByName,
                String title, String why, String fix) {}

    static final List<Pair> PAIRS = List.of(
            new Pair(Severity.WARNING, List.of("optifine"), List.of("create"), List.of(), List.of(),
                    "OptiFine and Create",
                    "Create's moving machines and OptiFine don't get along: blocks can turn into flat colors, "
                            + "and players can fall through moving contraptions.",
                    "Use Embeddium or Sodium with Oculus or Iris instead of OptiFine."),
            new Pair(Severity.WARNING, List.of("bettercombat"), List.of("epicfight"), List.of("combateventfix"),
                    List.of("better combat", "epic fight"),
                    "Better Combat and Epic Fight",
                    "Both take over how weapons swing, so attacks can break, miss or play the wrong animation.",
                    "Keep one, or add an add-on made to join them (search \"Better Combat Epic Fight compat\")."),
            new Pair(Severity.INFO, List.of("create"), List.of("iris", "oculus"),
                    List.of("colorwheel", "irisflw", "ocuwheel"), List.of(),
                    "Create with shaders",
                    "With a shader pack on, Create's machines and contraptions can look wrong or slow the game down.",
                    "Add Colorwheel (or Iris & Oculus Flywheel Compat on older Create) so shaders and Create work "
                            + "together."));

    private BadPairs() {}

    /** One finding per pair with both sides installed and no add-on that joins them. */
    public static List<Finding> check(Map<String, SameJob.Installed> installed) {
        List<Finding> out = new ArrayList<>();
        for (Pair p : PAIRS) {
            String a = first(p.a(), installed);
            String b = first(p.b(), installed);
            if (a == null || b == null || installed.get(a).jar().equals(installed.get(b).jar())) continue;
            if (p.fixedBy().stream().anyMatch(installed::containsKey)) continue;
            if (!p.fixedByName().isEmpty() && installed.values().stream().anyMatch(m -> {
                String name = m.name().toLowerCase(Locale.ROOT);
                return p.fixedByName().stream().allMatch(name::contains);
            })) continue;
            out.add(new Finding(p.severity(), CODE, p.title(), p.why(), p.fix(),
                    List.of(installed.get(a).jar(), installed.get(b).jar())));
        }
        return out;
    }

    private static String first(List<String> ids, Map<String, SameJob.Installed> installed) {
        return ids.stream().filter(installed::containsKey).findFirst().orElse(null);
    }
}
