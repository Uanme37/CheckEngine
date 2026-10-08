package com.guiltypotato.checkengine.core.scan;

import java.util.ArrayList;
import java.util.HashSet;
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
        // The list of changes is the clue itself, so it's shown in full.
        String what = f.code().equals(Changes.CODE) ? f.detail() + "\n" : "";
        return "§6Check Engine clue:§r §e" + f.title() + "§r\n" + what + "§aFix:§r " + f.fix();
    }

    /** The scan's findings worth showing as clues next to a loading error. */
    public static List<Finding> clues(Report report) {
        return clues(report, List.of());
    }

    /** Same, leaving out what the root problems already explain (e.g. "Fabric mod: Create" under "Create is the Fabric version"). */
    public static List<Finding> clues(Report report, List<RootProblem> roots) {
        if (report == null) return List.of();
        Set<String> explained = new HashSet<>();
        roots.forEach(r -> explained.addAll(r.files()));
        List<Finding> clues = new ArrayList<>();
        // "It worked yesterday": what changed since then is often the best clue, so it goes first.
        if (report.changes() != null && !report.changes().isEmpty()) clues.add(report.changes().toFinding());
        report.findings().stream()
                .filter(f -> f.severity() != Finding.Severity.INFO && !LOADER_RULES.contains(f.code()))
                .filter(f -> f.files().isEmpty() || !explained.containsAll(f.files()))
                .forEach(clues::add);
        return clues;
    }
}
