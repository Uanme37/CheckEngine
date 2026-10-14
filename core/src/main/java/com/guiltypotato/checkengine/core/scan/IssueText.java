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

    /** On the loader's error screen during a Strip Mine round, instead of advice about mods switched off on purpose. */
    public static final String STRIP_MINE_ROUND = "§6§lCheck Engine Strip Mine:§r this is a test round, with some mods "
            + "switched off on purpose, so this error is expected.\n§aWhat to do:§r close the game and press Play. "
            + "Check Engine sets up the next round by itself.";

    /** Something that stops the pack from loading: what, why, and the fix. */
    public static String problem(Finding f) {
        return "§c§lCheck Engine:§r §e" + f.title() + "§r\n" + f.detail() + "\n§aFix:§r " + f.fix();
    }

    /** Where the loader's screen sends players for everything else. */
    public static final String REPORT_HINT = "§7Full details: checkengine/early-report.txt§r";

    /**
     * The loader's error screen, short: per problem one line (what, and how many mods it stops) and the fix, in the
     * same words as the Check Engine window. The last one says where the full report is.
     */
    public static List<String> problems(List<RootProblem> roots) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < roots.size(); i++) {
            RootProblem r = roots.get(i);
            int stopped = r.affected().size();
            String line = "§c§lCheck Engine:§r §e" + r.title() + "§r"
                    + (stopped > 0 ? " §7(" + stopped + (stopped == 1 ? " mod can't load)" : " mods can't load)") + "§r" : "")
                    + (r.fix() == null ? "" : "\n§aFix:§r " + r.fix());
            out.add(i == roots.size() - 1 ? line + "\n" + REPORT_HINT : line);
        }
        return out;
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
