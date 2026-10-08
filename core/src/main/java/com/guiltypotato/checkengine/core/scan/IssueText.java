package com.guiltypotato.checkengine.core.scan;

import java.util.List;
import java.util.Set;

/**
 * How findings read on the mod loader's own error screen. § colour codes: c red, 6 gold, e yellow, a green,
 * l bold, r reset.
 */
public final class IssueText {
    /** Codes the early check already reports as problems (with the loader's own rules), so they aren't repeated. */
    private static final Set<String> LOADER_RULES = Set.of(Finding.MISSING_DEPENDENCY, Finding.WRONG_VERSION,
            Finding.INCOMPATIBLE_MOD);

    private IssueText() {}

    /** Something that stops the pack from loading: what, why, and the fix. */
    public static String problem(Finding f) {
        return "§c§lCheck Engine:§r §e" + f.title() + "§r\n" + f.detail() + "\n§aFix:§r " + f.fix();
    }

    /** A warning that might explain a failure: kept short, the report file has the details. */
    public static String clue(Finding f) {
        return "§6Check Engine clue:§r §e" + f.title() + "§r\n§aFix:§r " + f.fix();
    }

    /** The scan's findings worth showing as clues next to a loading error. */
    public static List<Finding> clues(Report report) {
        if (report == null) return List.of();
        return report.findings().stream()
                .filter(f -> f.severity() != Finding.Severity.INFO && !LOADER_RULES.contains(f.code()))
                .toList();
    }
}
