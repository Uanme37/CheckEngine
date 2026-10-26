package com.guiltypotato.checkengine.early;

import cpw.mods.jarhandling.JarContents;
import java.net.URL;
import java.nio.file.Paths;
import net.neoforged.neoforgespi.ILaunchContext;
import net.neoforged.neoforgespi.locating.IDiscoveryPipeline;
import net.neoforged.neoforgespi.locating.IModFileCandidateLocator;
import net.neoforged.neoforgespi.locating.IncompatibleFileReporting;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;

/**
 * NeoForge loads this jar as a plugin, not as a mod, so this hands it the real Check Engine mod packed inside
 * (the same trick Monocle uses). Players still install a single jar.
 */
public class EmbeddedModLocator implements IModFileCandidateLocator {
    static final String INNER_MOD = "/META-INF/jarjar/checkengine-mod.jar";

    @Override
    public void findCandidates(ILaunchContext context, IDiscoveryPipeline pipeline) {
        URL inner = EmbeddedModLocator.class.getResource(INNER_MOD);
        if (inner == null) return;
        try {
            pipeline.addJarContent(JarContents.of(Paths.get(inner.toURI())), ModFileDiscoveryAttributes.DEFAULT,
                    IncompatibleFileReporting.WARN_ALWAYS);
        } catch (Exception e) {
            EarlyCheck.LOGGER.error("Check Engine: couldn't load the mod packed in its jar", e);
        }
    }
}
