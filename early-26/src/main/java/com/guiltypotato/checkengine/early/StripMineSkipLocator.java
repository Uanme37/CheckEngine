package com.guiltypotato.checkengine.early;

import com.guiltypotato.checkengine.core.strip.StripMine;
import java.nio.file.Path;
import java.util.List;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforgespi.ILaunchContext;
import net.neoforged.neoforgespi.locating.IDiscoveryPipeline;
import net.neoforged.neoforgespi.locating.IModFileCandidateLocator;

/**
 * Strip Mine on NeoForge 26.1: the game keeps its mod jars locked while it runs, so the ones a round switches off
 * often can't be renamed when the game closes. Runs before NeoForge reads the mods folder and marks those jars as
 * already found, so NeoForge skips them: the round still tests the right mods, and nothing is renamed.
 */
public class StripMineSkipLocator implements IModFileCandidateLocator {
    @Override
    public void findCandidates(ILaunchContext context, IDiscoveryPipeline pipeline) {
        try {
            Path mods = FMLPaths.MODSDIR.get();
            // Mods the player switched off (Undo new mods, Strip Mine's fix) that were locked as the game closed
            List<String> pending = StripMine.applyPending(mods);
            for (String jar : pending) context.addLocated(mods.resolve(jar));
            // Old versions Go back couldn't put in while a new jar of the same name was locked
            com.guiltypotato.checkengine.core.strip.GoBack.applyPending(mods,
                    context.gameDirectory().resolve("checkengine"));
            List<String> skip = StripMine.lockedOn(mods, context.gameDirectory().resolve("checkengine"));
            for (String jar : skip) context.addLocated(mods.resolve(jar));
            if (!skip.isEmpty()) EarlyCheck.LOGGER.info("Check Engine: Strip Mine round, skipping {} mods", skip.size());
        } catch (RuntimeException e) {
            EarlyCheck.LOGGER.error("Check Engine: couldn't skip Strip Mine's switched-off mods", e);
        }
    }

    /** Before NeoForge's own mods folder reader (priority 0). */
    @Override
    public int getPriority() {
        return 100;
    }
}
