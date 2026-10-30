package com.guiltypotato.checkengine.core.crash;

import com.guiltypotato.checkengine.core.crash.CrashDoctor.LastCrash;
import com.guiltypotato.checkengine.core.scan.HelpBundle;
import com.guiltypotato.checkengine.core.scan.PackSnapshot;
import com.guiltypotato.checkengine.core.scan.Report;
import com.guiltypotato.checkengine.core.scan.ScanOptions;
import java.nio.file.Path;
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
        return message(crash, report, player, null);
    }

    /**
     * As above; when the scan has no Minecraft or loader version (it can come too early on NeoForge 26.1), they come
     * from the last launch that worked, saved in {@code checkEngineFolder}.
     */
    public static String message(LastCrash crash, Report report, String player, Path checkEngineFolder) {
        PackSnapshot saved = checkEngineFolder == null ? null : PackSnapshot.load(checkEngineFolder);
        List<String> keys = new ArrayList<>(crash.keyLines().stream().map(CrashLines.KeyLine::text).toList());
        List<String> changes = new ArrayList<>();
        if (!crash.beforeInstall() && crash.changes() != null && !crash.changes().isEmpty()) {
            for (String l : crash.changes().summary().split("\n")) if (!l.isBlank()) changes.add(l.strip());
        }
        while (changes.size() > MAX_CHANGES) changes.remove(changes.size() - 1);
        int cut = 0;
        String text = build(crash, report, saved, keys, changes, cut, player);
        // Too long for one message: fewer changes first, then shorter lines, then fewer key lines.
        while (text.length() > MAX_CHARS) {
            if (!changes.isEmpty()) changes.remove(changes.size() - 1);
            else if (cut < 3) cut++;
            else if (keys.size() > 1) keys.remove(keys.size() - 1);
            else return text.substring(0, MAX_CHARS - SIGNATURE.length() - 6) + "...\n" + SIGNATURE;
            text = build(crash, report, saved, keys, changes, cut, player);
        }
        return text;
    }

    /**
     * Help > Copy for help on a screen without a crash: what the screen says, then the versions. Blanked out and kept
     * under Discord's limit the same way.
     *
     * @param code the fault code, or null
     */
    public static String forScreen(String state, String code, List<String> lines, Report report, String player,
                                   Path checkEngineFolder) {
        PackSnapshot saved = checkEngineFolder == null ? null : PackSnapshot.load(checkEngineFolder);
        StringBuilder sb = new StringBuilder("**Check Engine: ").append(state).append("**");
        if (code != null) sb.append(" (").append(code).append(')');
        sb.append('\n');
        for (String l : lines) sb.append(l).append('\n');
        String versions = versions(report, saved);
        String end = (versions.isEmpty() ? "" : versions + "\n") + SIGNATURE;
        String text = blank(sb.toString(), player);
        int room = MAX_CHARS - end.length() - 4;
        return (text.length() > room ? text.substring(0, room) + "...\n" : text) + end;
    }

    private static String build(LastCrash crash, Report report, PackSnapshot saved, List<String> keys, List<String> changes, int cut,
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
        String versions = versions(report, saved);
        return blank(sb.toString(), player) + (versions.isEmpty() ? "" : versions + "\n") + SIGNATURE;
    }

    /** "Minecraft 1.21.1 | NeoForge 21.1.209 | Java 21.0.8 | 312 mods" */
    static String versions(Report report, PackSnapshot saved) {
        List<String> parts = new ArrayList<>();
        ScanOptions o = report == null ? null : report.options();
        String minecraft = o != null && o.minecraftVersion() != null ? o.minecraftVersion()
                : saved != null ? saved.minecraft() : null;
        String loader = o != null && o.neoforgeVersion() != null ? o.neoforgeVersion()
                : saved != null ? saved.loader() : null;
        if (minecraft != null) parts.add("Minecraft " + minecraft);
        // no scan to say which loader: Check Engine's 1.20.1 build is the Forge one
        if (loader != null) parts.add((o != null ? o.loaderName() : "1.20.1".equals(minecraft) ? "Forge" : "NeoForge")
                + " " + loader);
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
