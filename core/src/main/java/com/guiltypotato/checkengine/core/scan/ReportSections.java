package com.guiltypotato.checkengine.core.scan;

import java.util.ArrayList;
import java.util.List;

/**
 * A saved report ({@link Report#appendFindings}) read back into findings and lines, so the in-game report screen can
 * show the file as panels. Works on the text alone: a report saved by an older Check Engine reads fine too.
 */
public final class ReportSections {
    /** What a line is: a list item, its second line, plain text, a small heading, or the fix. */
    public enum Kind { ITEM, MORE, TEXT, HEADING, FIX }

    public record Entry(Kind kind, String text) {}

    /** One finding: "WARNING", its title, and its lines. */
    public record Block(String severity, String title, List<Entry> lines) {}

    /** @param summary "0 problems, 2 warnings", or "No problems found." */
    public record Parsed(String summary, List<Block> blocks) {}

    private ReportSections() {}

    public static Parsed parse(String report) {
        String summary = "";
        List<Block> blocks = new ArrayList<>();
        Block current = null;
        for (String raw : report.replace("\r\n", "\n").split("\n")) {
            String line = raw.stripTrailing();
            if (line.startsWith("[") && line.indexOf("] ") > 1) {
                current = new Block(line.substring(1, line.indexOf(']')), line.substring(line.indexOf("] ") + 2),
                        new ArrayList<>());
                blocks.add(current);
                continue;
            }
            if (current == null) {
                if (line.matches("\\d+ problems?, \\d+ warnings?") || line.equals("No problems found.")) summary = line;
                continue;
            }
            if (line.isBlank() || !line.startsWith("  ")) continue;
            String t = line.substring(2);
            if (t.startsWith("Fix: ")) current.lines().add(new Entry(Kind.FIX, t.substring(5)));
            else if (t.startsWith("- ")) current.lines().add(new Entry(Kind.ITEM, t.substring(2)));
            else if (t.startsWith("  ")) current.lines().add(new Entry(Kind.MORE, t.strip()));
            else if (t.startsWith("...and ")) current.lines().add(new Entry(Kind.MORE, t));
            else if (t.endsWith(":") && t.length() < 60) current.lines().add(new Entry(Kind.HEADING, t));
            else current.lines().add(new Entry(Kind.TEXT, t));
        }
        return new Parsed(summary, blocks);
    }
}
