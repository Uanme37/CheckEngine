package com.guiltypotato.checkengine.core.crash;

import com.guiltypotato.checkengine.core.crash.CrashDoctor.LastCrash;
import com.guiltypotato.checkengine.core.scan.HelpBundle;
import com.guiltypotato.checkengine.core.scan.Report;
import com.guiltypotato.checkengine.core.scan.ScanOptions;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * "Copy for help": one message to paste in a Discord help channel or an issue. The cause and fix (or "not sure"), the
 * crash's key lines in a code block, what changed since the pack last worked and the versions. The user folder, the
 * player name and IP addresses are blanked out, and it stays under Discord's 2,000 characters. Nothing is uploaded.
 */
public final class CrashHelp {
    /** Discord's limit for one message. */
    public static final int MAX_CHARS = 2000;
    static final String SIGNATURE = "Diagnosed by Check Engine";
    private static final int MAX_CHANGES = 6;

    private CrashHelp() {}

    /**
     * @param crash  the crash
     * @param report a scan of the pack (for the versions and mod count), or null
     * @param player the player's name, blanked out wherever it shows up, or null
     */
    public static String message(LastCrash crash, Report report, String player) {
        List<String> keys = new ArrayList<>(crash.keyLines().stream().map(CrashLines.KeyLine::text).toList());
        List<String> changes = new ArrayList<>();
        if (!crash.beforeInstall() && crash.changes() != null && !crash.changes().isEmpty()) {
            for (String l : crash.changes().summary().split("\n")) if (!l.isBlank()) changes.add(l.strip());
        }
        while (changes.size() > MAX_CHANGES) changes.remove(changes.size() - 1);
        int cut = 0;
        String text = build(crash, report, keys, changes, cut, player);
        // Too long for one message: fewer changes first, then shorter lines, then fewer key lines.
        while (text.length() > MAX_CHARS) {
            if (!changes.isEmpty()) changes.remove(changes.size() - 1);
            else if (cut < 3) cut++;
            else if (keys.size() > 1) keys.remove(keys.size() - 1);
            else return text.substring(0, MAX_CHARS - SIGNATURE.length() - 6) + "...\n" + SIGNATURE;
            text = build(crash, report, keys, changes, cut, player);
        }
        return text;
    }

    private static String build(LastCrash crash, Report report, List<String> keys, List<String> changes, int cut,
                                String player) {
        int lineMax = cut == 0 ? 100_000 : 160 - cut * 40;
        StringBuilder sb = new StringBuilder();
        sb.append("**Minecraft crashed** (").append(crash.when()).append(")");
        if (crash.beforeInstall()) sb.append(", before Check Engine was installed");
        sb.append('\n');
        if (crash.sure()) {
            sb.append("**Cause:** ").append(crash.main().title()).append('\n');
            if (crash.main().fix() != null) sb.append("**Fix:** ").append(shorten(crash.main().fix(), lineMax * 2)).append('\n');
        } else {
            sb.append("**Cause:** not sure");
            if (crash.culprit() != null) sb.append(" (it happened inside ").append(crash.culprit()).append(')');
            sb.append('\n');
        }
        if (!keys.isEmpty()) {
            sb.append("```\n");
            for (String k : keys) sb.append(shorten(k.replace("```", "'''"), lineMax)).append('\n');
            sb.append("```\n");
        }
        if (!changes.isEmpty()) {
            sb.append("**Changed since it last worked");
            if (crash.changes().when() != null) sb.append(" (").append(crash.changes().when()).append(')');
            sb.append(":**\n");
            for (String c : changes) sb.append("- ").append(shorten(c, lineMax)).append('\n');
        }
        // Versions go on after the blanking: "Java 21.0.8.1" isn't an IP address
        String versions = versions(report);
        return blank(sb.toString(), player) + (versions.isEmpty() ? "" : versions + "\n") + SIGNATURE;
    }

    /** "Minecraft 1.21.1 | NeoForge 21.1.209 | Java 21.0.8 | 312 mods" */
    static String versions(Report report) {
        List<String> parts = new ArrayList<>();
        if (report != null) {
            ScanOptions o = report.options();
            if (o != null && o.minecraftVersion() != null) parts.add("Minecraft " + o.minecraftVersion());
            if (o != null && o.neoforgeVersion() != null) parts.add(o.loaderName() + " " + o.neoforgeVersion());
        }
        String java = System.getProperty("java.version");
        if (java != null) parts.add("Java " + java);
        if (report != null && !report.jars().isEmpty()) parts.add(report.jars().size() + " mods");
        return String.join(" | ", parts);
    }

    private static String blank(String text, String player) {
        String t = new HelpBundle.Redactor(System.getProperty("user.home")).apply(text);
        if (player != null && player.length() >= 3) {
            t = Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(player) + "(?![A-Za-z0-9_])").matcher(t)
                    .replaceAll("<player>");
        }
        return t;
    }

    private static String shorten(String s, int max) {
        return s.length() <= max ? s : s.substring(0, Math.max(10, max - 3)) + "...";
    }
}
