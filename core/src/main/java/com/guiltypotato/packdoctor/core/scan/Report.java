package com.guiltypotato.packdoctor.core.scan;

import com.guiltypotato.packdoctor.core.model.ModJar;
import java.nio.file.Path;
import java.util.List;

/**
 * Everything a scan found.
 *
 * @param modsFolder the folder that was scanned
 * @param options    what we knew about the pack
 * @param jars       every jar we looked at
 * @param findings   problems, worst first
 */
public record Report(Path modsFolder, ScanOptions options, List<ModJar> jars, List<Finding> findings) {

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
        sb.append("Pack Doctor report\n");
        sb.append("Folder: ").append(modsFolder).append('\n');
        sb.append("Checked as: ").append(switch (options.side()) {
            case CLIENT -> "client (player's game)";
            case SERVER -> "server";
            case UNKNOWN -> "unknown side";
        });
        if (options.minecraftVersion() != null) sb.append(", Minecraft ").append(options.minecraftVersion());
        if (options.neoforgeVersion() != null) sb.append(", NeoForge ").append(options.neoforgeVersion());
        sb.append('\n');
        sb.append("Jars: ").append(jars.size()).append('\n');
        sb.append('\n');
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
