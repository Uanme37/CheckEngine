package com.guiltypotato.checkengine.early;

import java.nio.file.Path;

/**
 * The notes a closing game left: jars to switch off that were still locked (StripMine), old versions Go back couldn't
 * put in yet and configs it puts back now that no mod is running (GoBack). In that order, before mods are read.
 */
final class Pending {
    private Pending() {}

    static void apply(Path gameDir) {
        try {
            Path mods = gameDir.resolve("mods");
            com.guiltypotato.checkengine.core.strip.StripMine.applyPending(mods);
            com.guiltypotato.checkengine.core.strip.GoBack.applyPending(mods, gameDir.resolve("checkengine"));
        } catch (RuntimeException e) {
            EarlyCheck.LOGGER.error("Check Engine: couldn't finish what the last session left to do", e);
        }
    }
}
