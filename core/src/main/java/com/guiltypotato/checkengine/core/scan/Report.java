package com.guiltypotato.checkengine.core.scan;

import com.guiltypotato.checkengine.core.model.ModJar;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything a scan found.
 *
 * @param modsFolder the folder that was scanned
 * @param options    what we knew about the pack
 * @param jars       every jar we looked at
 * @param findings   problems, worst first
 * @param roots      what to fix first: the causes behind the loading errors, biggest first
 */
public record Report(Path modsFolder, ScanOptions options, List<ModJar> jars, List<Finding> findings,
                     List<RootProblem> roots) {

    public Report(Path modsFolder, ScanOptions options, List<ModJar> jars, List<Finding> findings) {
        this(modsFolder, options, jars, findings, List.of());
    }

    /**
     * One line on how the pack is doing, counted per jar: "394 jars: 381 OK, 3 with warnings, 10 can't load".
     * A jar counts as "can't load" if a problem names it or a root problem stops it.
     */
    public String health() {
        Map<String, Finding.Severity> worst = new HashMap<>();
        for (Finding f : findings) {
            if (f.severity() == Finding.Severity.INFO) continue;
            for (String file : f.files()) worst.merge(file, f.severity(), (a, b) -> a.compareTo(b) <= 0 ? a : b);
        }
        for (RootProblem p : roots) {
            for (String file : p.files()) worst.put(file, Finding.Severity.ERROR);
        }
        long broken = jars.stream().filter(j -> worst.get(j.fileName()) == Finding.Severity.ERROR).count();
        long warned = jars.stream().filter(j -> worst.get(j.fileName()) == Finding.Severity.WARNING).count();
        long ok = jars.size() - broken - warned;
        return jars.size() + " jars: " + ok + " OK, " + warned + " with warnings, " + broken + " can't load";
    }

    public long count(Finding.Severity s) {
        return findings.stream().filter(f -> f.severity() == s).count();
    }

    public boolean hasErrors() {
        return count(Finding.Severity.ERROR) > 0;
    }

    public List<Finding> byCode(String code) {
        return findings.stream().filter(f -> f.code().equals(code)).toList();
    }

    /** Plain text report for a console or a .txt file. */
    public String toText() {
        StringBuilder sb = new StringBuilder();
        sb.append("Check Engine report\n");
        sb.append("Folder: ").append(modsFolder).append('\n');
        sb.append("Checked as: ").append(switch (options.side()) {
            case CLIENT -> "client (player's game)";
            case SERVER -> "server";
            case UNKNOWN -> "unknown side";
        });
        if (options.minecraftVersion() != null) sb.append(", Minecraft ").append(options.minecraftVersion());
        if (options.neoforgeVersion() != null) sb.append(", ").append(options.loaderName()).append(" ").append(options.neoforgeVersion());
        sb.append('\n');
        sb.append("Pack health: ").append(health()).append('\n');
        sb.append('\n');
        if (!roots.isEmpty()) {
            sb.append("FIX THESE FIRST (").append(roots.size()).append(roots.size() == 1 ? " root problem" : " root problems")
                    .append(")\n\n");
            int n = 1;
            for (RootProblem p : roots) {
                sb.append(n++).append(". ").append(p.title()).append('\n');
                sb.append("   ").append(p.detail().replace("\n", "\n   ")).append('\n');
                if (p.certainty() != null) sb.append("   How sure: ").append(p.certainty()).append('\n');
                sb.append("   Fix: ").append(p.fix()).append("\n\n");
            }
            sb.append("Everything Check Engine found:\n\n");
        }
        appendFindings(sb, findings);
        return sb.toString();
    }

    /** The summary line plus every finding. Shared with the in-game data scan report. */
    public static void appendFindings(StringBuilder sb, List<Finding> findings) {
        if (findings.isEmpty()) {
            sb.append("No problems found.\n");
            return;
        }
        sb.append(summary(findings)).append("\n\n");
        for (Finding f : findings) {
            sb.append('[').append(f.severity()).append("] ").append(f.title()).append('\n');
            sb.append("  ").append(f.detail().replace("\n", "\n  ")).append('\n');
            if (f.fix() != null) sb.append("  Fix: ").append(f.fix()).append('\n');
            if (!f.files().isEmpty()) sb.append("  Files: ").append(String.join(", ", f.files())).append('\n');
            sb.append('\n');
        }
    }

    /** e.g. "2 problems, 1 warning" (notes are left out). */
    public static String summary(List<Finding> findings) {
        long errors = findings.stream().filter(f -> f.severity() == Finding.Severity.ERROR).count();
        long warnings = findings.stream().filter(f -> f.severity() == Finding.Severity.WARNING).count();
        return errors + (errors == 1 ? " problem, " : " problems, ")
                + warnings + (warnings == 1 ? " warning" : " warnings");
    }
}
