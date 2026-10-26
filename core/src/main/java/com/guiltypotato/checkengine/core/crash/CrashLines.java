package com.guiltypotato.checkengine.core.crash;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The few lines of a crash a helper wants to see, word for word: what Minecraft said, the error, the loader's failure
 * messages, the deepest cause and the first line inside a mod. The one that names the mod at fault is marked, and only
 * when Check Engine can tell which mod that is.
 */
public final class CrashLines {
    /** At most this many lines: enough for a helper, short enough to read on the screen. */
    public static final int MAX = 6;
    private static final int MAX_CHARS = 200;
    private static final Pattern LOADING_ISSUE = Pattern.compile("^-- Mod loading issue for: ([a-z0-9_.-]+) --");

    /** One line from the crash, and whether it names the mod at fault. */
    public record KeyLine(String text, boolean culprit) {}

    private CrashLines() {}

    /** The key lines of a crash report (or a Java hs_err_pid log), at most {@link #MAX}. */
    public static List<KeyLine> pick(String report) {
        if (report == null || report.isBlank()) return List.of();
        if (NativeCrash.isNativeCrash(report)) return pickNative(report);
        String[] lines = CrashTranslator.tidy(report).split("\n");
        List<String> picked = new ArrayList<>();
        String culpritFrame = null;
        String lastCause = null;
        List<String> failures = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i];
            String s = l.strip();
            if (s.startsWith("Description: ") && picked.isEmpty()) {
                picked.add(s);
                for (int j = i + 1; j < lines.length; j++) {
                    if (!lines[j].isBlank()) {
                        picked.add(lines[j].strip());
                        break;
                    }
                }
            } else if (s.startsWith("Failure message: ") && failures.size() < 3) {
                // "Check Engine clue:" lines are our own notes from the early check, not part of the crash
                if (!s.contains("Check Engine clue:")) failures.add(s.substring("Failure message: ".length()));
            } else if (s.startsWith("Caused by: ")) {
                lastCause = s;
            } else if (culpritFrame == null && s.startsWith("at ")) {
                Matcher m = CrashTranslator.FRAME_MOD.matcher(l);
                if (m.find() && isMod(m.group(1))) culpritFrame = s;
            }
            // The system details at the end only list mods and settings
            if (s.startsWith("-- System Details --")) break;
        }
        for (String f : failures) add(picked, f);
        if (lastCause != null) add(picked, lastCause);
        if (culpritFrame != null) add(picked, culpritFrame);
        return mark(picked, culprit(report));
    }

    /**
     * The id of the mod the crash happened in: the first mod in the stack trace, else the mod the loader names in
     * "Mod loading issue for: x". Null when it can't tell.
     */
    public static String culprit(String report) {
        if (report == null || NativeCrash.isNativeCrash(report)) return null;
        String loading = null;
        for (String l : CrashTranslator.tidy(report).split("\n")) {
            if (l.strip().startsWith("-- System Details --")) break;
            Matcher m = CrashTranslator.FRAME_MOD.matcher(l);
            if (m.find() && isMod(m.group(1))) return m.group(1);
            Matcher li = LOADING_ISSUE.matcher(l.strip());
            if (loading == null && li.find() && !li.group(1).equals("checkengine")) loading = li.group(1);
        }
        return loading;
    }

    /** The version of mod {@code modId} in the report's mod list ("file.jar |Name |id |1.2.3 |..."), or null. */
    public static String versionInReport(String report, String modId) {
        if (report == null || modId == null) return null;
        for (String l : report.split("\n")) {
            if (!l.contains("|")) continue;
            String[] cols = l.split("\\|");
            if (cols.length >= 4 && cols[2].strip().equals(modId)) return cols[3].strip();
        }
        return null;
    }

    /** A silent crash: the error type, the frame it died in and the Java version. */
    private static List<KeyLine> pickNative(String log) {
        List<String> picked = new ArrayList<>();
        String frame = null;
        String[] lines = log.replace("\r", "").split("\n");
        for (int i = 0; i < lines.length; i++) {
            String s = lines[i].strip();
            if (!s.startsWith("#")) continue;
            if (s.matches("#\\s+(EXCEPTION_\\w+|SIG[A-Z]+).*")) add(picked, s);
            else if (s.startsWith("# JRE version")) add(picked, s);
            else if (s.startsWith("# Problematic frame:") && i + 1 < lines.length && lines[i + 1].strip().length() > 2) {
                frame = lines[i + 1].strip();
                add(picked, frame);
            }
        }
        List<KeyLine> out = new ArrayList<>();
        for (String p : picked) out.add(new KeyLine(p, p.equals(frame)));
        return out;
    }

    /**
     * The lines with no mark: for a knock-on crash, where the mod in the stack trace isn't the one at fault, so
     * marking it would point the player at the wrong mod.
     */
    public static List<KeyLine> unmarked(List<KeyLine> lines) {
        return lines.stream().map(l -> l.culprit() ? new KeyLine(l.text(), false) : l).toList();
    }

    /** A mod id, not the game, the loader or a Java library (com.google.common: mod ids have no dots). */
    private static boolean isMod(String module) {
        return !module.contains(".") && !CrashTranslator.NOT_CULPRITS.contains(module);
    }

    private static void add(List<String> picked, String line) {
        // The loader's notes after a stack frame ("{re:mixin,...}") only add noise
        line = line.replaceFirst("\\s*\\{[a-z]{2}:[^}]*}\\s*$", "").replaceFirst("\\s*\\{}\\s*$", "");
        if (picked.size() >= MAX || picked.contains(line)) return;
        picked.add(line);
    }

    /** Marks the first line that names the culprit; the frame inside the mod wins over the loader's messages. */
    private static List<KeyLine> mark(List<String> picked, String culprit) {
        int at = -1;
        if (culprit != null) {
            for (int i = picked.size() - 1; i >= 0 && at < 0; i--) {
                if (picked.get(i).startsWith("at ") && picked.get(i).contains(culprit + "@")) at = i;
            }
            for (int i = 0; i < picked.size() && at < 0; i++) {
                if (picked.get(i).matches(".*(?<![a-z0-9_])" + Pattern.quote(culprit) + "(?![a-z0-9_]).*")) at = i;
            }
        }
        List<KeyLine> out = new ArrayList<>();
        for (int i = 0; i < picked.size(); i++) {
            String t = picked.get(i);
            if (t.length() > MAX_CHARS) t = t.substring(0, MAX_CHARS - 3) + "...";
            out.add(new KeyLine(t, i == at));
        }
        return out;
    }
}
