package com.guiltypotato.checkengine.core.scan;

import java.util.List;

/**
 * The one toast on the title screen: what's fixed since last time and the slower-start notice, together, so two toasts
 * don't stack over the pack's logo. The toast wraps long text, so nothing is cut off.
 */
public final class StartNotice {
    private StartNotice() {}

    /**
     * @param fixed  problems gone since the last launch (FixedProblems), maybe empty
     * @param slower the slower-start text, or null
     * @return {title, message}, or null when there's nothing to say
     */
    public static String[] of(List<String> fixed, String slower) {
        boolean f = fixed != null && !fixed.isEmpty(), s = slower != null && !slower.isBlank();
        if (!f && !s) return null;
        List<String> lines = new java.util.ArrayList<>();
        if (f) lines.add(fixedLine(fixed));
        if (s) lines.add(slower);
        return new String[] {"Check Engine", String.join("\n", lines)};
    }

    /**
     * "Fixed: zip file in mods folder (ce-test-1.zip +1)": the first fixed problem, with how many more of the same
     * kind, then how many of other kinds.
     */
    static String fixedLine(List<String> fixed) {
        String first = fixed.get(0);
        int colon = first.indexOf(": ");
        String kind = colon < 0 ? first : first.substring(0, colon);
        String what = colon < 0 ? null : first.substring(colon + 2);
        int same = 0, other = 0;
        for (String f : fixed.subList(1, fixed.size())) {
            if (f.startsWith(kind + ": ")) same++;
            else other++;
        }
        StringBuilder sb = new StringBuilder("Fixed: ").append(lowerFirst(kind));
        if (what != null) sb.append(" (").append(what).append(same > 0 ? " +" + same : "").append(')');
        if (other > 0) sb.append(" +").append(other).append(" more");
        return sb.toString();
    }

    /** "Zip file" -> "zip file", but "AppleSkin" stays. */
    private static String lowerFirst(String s) {
        if (s.length() < 2 || !Character.isUpperCase(s.charAt(0)) || Character.isUpperCase(s.charAt(1))) return s;
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }
}
