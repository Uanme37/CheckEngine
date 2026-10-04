package com.guiltypotato.packdoctor.core.model;

import com.guiltypotato.packdoctor.core.version.VersionRange;

/** One {@code [[dependencies.<mod>]]} entry from neoforge.mods.toml. */
public record Dependency(String modId, Type type, VersionRange versionRange, DepSide side, String reason) {

    public enum Type {
        /** Must be installed (and in range). */
        REQUIRED,
        /** May be missing, but if installed it must be in range. */
        OPTIONAL,
        /** Must not be installed (in range). */
        INCOMPATIBLE,
        /** Works, but the author warns against it (in range). */
        DISCOURAGED;

        public static Type parse(String s) {
            if (s == null) return REQUIRED;
            return switch (s.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "optional" -> OPTIONAL;
                case "incompatible" -> INCOMPATIBLE;
                case "discouraged" -> DISCOURAGED;
                default -> REQUIRED;
            };
        }
    }

    /** Which physical side the dependency applies on. */
    public enum DepSide {
        BOTH, CLIENT, SERVER;

        public static DepSide parse(String s) {
            if (s == null) return BOTH;
            return switch (s.trim().toUpperCase(java.util.Locale.ROOT)) {
                case "CLIENT" -> CLIENT;
                case "SERVER" -> SERVER;
                default -> BOTH;
            };
        }

        public boolean appliesTo(Side side) {
            return this == BOTH || side == Side.UNKNOWN
                    || (this == CLIENT && side == Side.CLIENT)
                    || (this == SERVER && side == Side.SERVER);
        }
    }
}
