package com.guiltypotato.checkengine.core.version;

import java.util.ArrayList;
import java.util.List;

/**
 * Maven version range as used in neoforge.mods.toml, e.g. {@code [1.0,2.0)}, {@code [1.21.1]}, {@code [21.1.219,)}.
 * A bare version (no brackets), "*" or an empty string matches anything, same as NeoForge.
 */
public final class VersionRange {
    private final String raw;
    private final List<Bound> bounds; // empty = matches everything

    private record Bound(ModVersion lower, boolean lowerInclusive, ModVersion upper, boolean upperInclusive) {
        boolean contains(ModVersion v) {
            if (lower != null) {
                int c = v.compareTo(lower);
                if (c < 0 || (c == 0 && !lowerInclusive)) return false;
            }
            if (upper != null) {
                int c = v.compareTo(upper);
                if (c > 0 || (c == 0 && !upperInclusive)) return false;
            }
            return true;
        }
    }

    private VersionRange(String raw, List<Bound> bounds) {
        this.raw = raw;
        this.bounds = bounds;
    }

    public static final VersionRange ANY = new VersionRange("*", List.of());

    /** Parses a range. Throws {@link IllegalArgumentException} if it is malformed. */
    public static VersionRange parse(String spec) {
        if (spec == null) return ANY;
        String s = spec.replace(" ", "");
        if (s.isEmpty() || s.equals("*") || (s.charAt(0) != '[' && s.charAt(0) != '(')) {
            return new VersionRange(spec, List.of());
        }
        List<Bound> bounds = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            char open = s.charAt(i);
            if (open != '[' && open != '(') throw new IllegalArgumentException("bad range: " + spec);
            int close = i + 1;
            while (close < s.length() && s.charAt(close) != ']' && s.charAt(close) != ')') close++;
            if (close >= s.length()) throw new IllegalArgumentException("unclosed range: " + spec);
            String inner = s.substring(i + 1, close);
            boolean lowInc = open == '[';
            boolean highInc = s.charAt(close) == ']';
            int comma = inner.indexOf(',');
            if (comma < 0) {
                if (!lowInc || !highInc || inner.isEmpty()) throw new IllegalArgumentException("bad range: " + spec);
                ModVersion exact = ModVersion.parse(inner);
                bounds.add(new Bound(exact, true, exact, true));
            } else {
                String lo = inner.substring(0, comma);
                String hi = inner.substring(comma + 1);
                bounds.add(new Bound(
                        lo.isEmpty() ? null : ModVersion.parse(lo), lowInc,
                        hi.isEmpty() ? null : ModVersion.parse(hi), highInc));
            }
            i = close + 1;
            if (i < s.length() && s.charAt(i) == ',') i++;
        }
        return new VersionRange(spec, bounds);
    }

    /** Parses leniently: a malformed range is treated as "anything" rather than failing the scan. */
    public static VersionRange parseLenient(String spec) {
        try {
            return parse(spec);
        } catch (IllegalArgumentException e) {
            return new VersionRange(spec, List.of());
        }
    }

    public boolean matchesAnything() {
        return bounds.isEmpty();
    }

    public boolean contains(ModVersion v) {
        if (bounds.isEmpty()) return true;
        for (Bound b : bounds) if (b.contains(v)) return true;
        return false;
    }

    public boolean contains(String version) {
        return contains(ModVersion.parse(version));
    }

    /** Plain-English form: "1.0.0 or newer", "exactly 1.21.1", "1.0.0 up to (not including) 2.0". */
    public String describe() {
        if (bounds.isEmpty()) return "any version";
        List<String> parts = new ArrayList<>();
        for (Bound b : bounds) {
            if (b.lower != null && b.upper != null && b.lower.equals(b.upper) && b.lowerInclusive && b.upperInclusive) {
                parts.add("exactly " + b.lower);
            } else if (b.lower != null && b.upper != null) {
                parts.add(b.lower + (b.lowerInclusive ? "" : " (exclusive)") + " to " + b.upper
                        + (b.upperInclusive ? "" : " (not including " + b.upper + ")"));
            } else if (b.lower != null && b.lowerInclusive && b.lower.toString().matches("0(\\.0)*")) {
                parts.add("any version"); // "[0,)" is how many mods write "anything"
            } else if (b.lower != null) {
                parts.add(b.lower + (b.lowerInclusive ? " or newer" : " or newer (not " + b.lower + " itself)"));
            } else if (b.upper != null) {
                parts.add(b.upperInclusive ? b.upper + " or older" : "older than " + b.upper);
            } else {
                parts.add("any version");
            }
        }
        return String.join(", or ", parts);
    }

    @Override
    public String toString() {
        return raw;
    }
}
