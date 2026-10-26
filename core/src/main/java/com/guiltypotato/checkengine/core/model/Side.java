package com.guiltypotato.checkengine.core.model;

/** Which kind of install is being checked. */
public enum Side {
    /** A player's game (CurseForge instance, launcher profile). */
    CLIENT,
    /** A dedicated server's mods folder. */
    SERVER,
    /** Not known: dependency checks cover both sides, client-only check is skipped. */
    UNKNOWN
}
