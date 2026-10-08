package com.guiltypotato.checkengine.core.scan;

import com.guiltypotato.checkengine.core.scan.Finding.Severity;
import com.guiltypotato.checkengine.core.version.VersionRange;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The dependency rules the mod loader is about to refuse to start over, explained in plain English and grouped
 * by what's actually wrong ("Create is missing, and 3 mods need it" instead of 3 separate errors).
 * The loader adapter decides which rules fail (with the loader's own logic); this class only explains them.
 */
public final class LoadBlockers {
    public enum Kind { MISSING, WRONG_VERSION, INCOMPATIBLE }

    /**
     * One broken rule.
     *
     * @param kind      what's wrong
     * @param modName   the mod that has the rule, e.g. "Create: Steam 'n' Rails"
     * @param depId     the mod it's about, e.g. "create"
     * @param depName   its display name if it's installed, else null
     * @param range     the version range the rule asks for, e.g. "[6.0,)"
     * @param installed the installed version of depId, or null if it isn't installed
     * @param reason    the mod author's own explanation, or null
     */
    public record Blocker(Kind kind, String modName, String depId, String depName, String range, String installed,
                          String reason) {}

    private LoadBlockers() {}

    public static List<Finding> explain(List<Blocker> blockers) {
        Map<String, List<Blocker>> groups = new LinkedHashMap<>();
        for (Blocker b : blockers) {
            String key = b.kind() == Kind.INCOMPATIBLE ? b.kind() + "|" + b.modName() + "|" + b.depId()
                    : b.kind() + "|" + b.depId();
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(b);
        }
        List<Finding> out = new ArrayList<>();
        for (List<Blocker> group : groups.values()) {
            Blocker first = group.get(0);
            out.add(switch (first.kind()) {
                case MISSING -> missing(group);
                case WRONG_VERSION -> wrongVersion(group);
                case INCOMPATIBLE -> incompatible(first);
            });
        }
        return out;
    }

    private static Finding missing(List<Blocker> group) {
        String dep = group.get(0).depId();
        StringBuilder detail = new StringBuilder();
        if (group.size() == 1) {
            Blocker b = group.get(0);
            detail.append(b.modName()).append(" needs ").append(dep).append(" (").append(describe(b.range()))
                    .append("), but it isn't installed.");
        } else {
            detail.append(group.size()).append(" mods need ").append(dep).append(", but it isn't installed:");
            for (Blocker b : group) detail.append("\n- ").append(b.modName()).append(" (wants ")
                    .append(describe(b.range())).append(')');
        }
        reasons(detail, group);
        String fix = group.size() == 1
                ? "Install " + dep + ", or remove " + group.get(0).modName() + "."
                : "Install " + dep + ", or remove the " + group.size() + " mods that need it.";
        return new Finding(Severity.ERROR, Finding.MISSING_DEPENDENCY, "Missing mod: " + dep, detail.toString(), fix,
                List.of());
    }

    private static Finding wrongVersion(List<Blocker> group) {
        Blocker first = group.get(0);
        String name = first.depName() != null ? first.depName() : first.depId();
        boolean platform = first.depId().equals("minecraft") || first.depId().equals("neoforge")
                || first.depId().equals("forge");
        StringBuilder detail = new StringBuilder();
        if (group.size() == 1) {
            detail.append(first.modName()).append(" needs ").append(name).append(' ').append(describe(first.range()))
                    .append(", but this pack has ").append(first.installed()).append('.');
        } else {
            detail.append(group.size()).append(" mods need a different ").append(name).append(" (this pack has ")
                    .append(first.installed()).append("):");
            for (Blocker b : group) detail.append("\n- ").append(b.modName()).append(" (wants ")
                    .append(describe(b.range())).append(')');
        }
        reasons(detail, group);
        String fix;
        if (platform) {
            fix = group.size() == 1
                    ? "Use a version of " + first.modName() + " made for " + name + " " + first.installed() + "."
                    : "Use versions of those mods made for " + name + " " + first.installed() + ".";
        } else {
            fix = group.size() == 1
                    ? "Update " + name + " to " + describe(first.range()) + " (or use a version of " + first.modName()
                            + " that matches " + first.installed() + ")."
                    : "Update " + name + " to a version all of them accept (or change the mods that need it).";
        }
        String title = group.size() == 1
                ? "Wrong version: " + first.modName() + " needs " + name + " " + describe(first.range())
                : "Wrong version of " + name + " for " + group.size() + " mods";
        return new Finding(Severity.ERROR, Finding.WRONG_VERSION, title, detail.toString(), fix, List.of());
    }

    private static Finding incompatible(Blocker b) {
        String name = b.depName() != null ? b.depName() : b.depId();
        StringBuilder detail = new StringBuilder(b.modName() + " doesn't work with " + name
                + (VersionRange.parseLenient(b.range()).matchesAnything() ? "" : " " + describe(b.range()))
                + " (this pack has " + b.installed() + ").");
        reasons(detail, List.of(b));
        return new Finding(Severity.ERROR, Finding.INCOMPATIBLE_MOD, b.modName() + " doesn't work with " + name,
                detail.toString(), "Remove " + b.modName() + " or " + name + ".", List.of());
    }

    private static String describe(String range) {
        return VersionRange.parseLenient(range).describe();
    }

    private static void reasons(StringBuilder detail, List<Blocker> group) {
        group.stream().map(Blocker::reason).filter(r -> r != null && !r.isBlank()).distinct()
                .forEach(r -> detail.append("\nThe mod says: \"").append(r.strip()).append('"'));
    }
}
