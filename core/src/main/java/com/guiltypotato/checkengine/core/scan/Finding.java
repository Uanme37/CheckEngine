package com.guiltypotato.checkengine.core.scan;

import java.util.List;

/**
 * One problem found in a pack, written for pack makers, not programmers.
 *
 * @param severity how bad it is
 * @param code     stable id for the kind of problem (handy for tests and for linking docs later)
 * @param title    one line, e.g. "Duplicate mod: Create"
 * @param detail   what's wrong, in plain English
 * @param fix      what to do about it
 * @param files    jar file names involved
 */
public record Finding(Severity severity, String code, String title, String detail, String fix, List<String> files) {

    public enum Severity {
        /** The game will crash or the mod won't load. */
        ERROR,
        /** Probably a problem; worth a look. */
        WARNING,
        /** Good to know, nothing broken. */
        INFO
    }

    public static final String DUPLICATE_MOD = "duplicate-mod";
    public static final String MISSING_DEPENDENCY = "missing-dependency";
    public static final String WRONG_VERSION = "wrong-version";
    public static final String INCOMPATIBLE_MOD = "incompatible-mod";
    public static final String DISCOURAGED_MOD = "discouraged-mod";
    public static final String CLIENT_ONLY_ON_SERVER = "client-only-on-server";
    public static final String WRONG_LOADER = "wrong-loader";
    public static final String BROKEN_JAR = "broken-jar";
    public static final String NOT_A_MOD = "not-a-mod";
    public static final String UNREADABLE_METADATA = "unreadable-metadata";
    public static final String STRAY_FILE = "stray-file";
    public static final String NO_MODS = "no-mods";
    public static final String ITEMS_FROM_MISSING_MOD = "items-from-missing-mod";
    public static final String BROKEN_QUEST_ITEM = "broken-quest-item";
}
