package com.guiltypotato.checkengine.early;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import net.neoforged.neoforgespi.ILaunchContext;
import net.neoforged.neoforgespi.locating.IDiscoveryPipeline;
import net.neoforged.neoforgespi.locating.IModFileCandidateLocator;
import net.neoforged.neoforgespi.locating.IncompatibleFileReporting;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;

/**
 * NeoForge loads this jar as a plugin, not as a mod, so this hands it the real Check Engine mod packed inside
 * (the same trick Monocle uses). NeoForge 26.1 only reads mods from real files, so the inner jar is unpacked to
 * checkengine/embedded/ first (like NeoForge does with its own jar-in-jar mods). Players still install one jar.
 */
public class EmbeddedModLocator implements IModFileCandidateLocator {
    static final String INNER_MOD = "/META-INF/jarjar/checkengine-mod.jar";

    @Override
    public void findCandidates(ILaunchContext context, IDiscoveryPipeline pipeline) {
        try (InputStream in = EmbeddedModLocator.class.getResourceAsStream(INNER_MOD)) {
            if (in == null) return;
            byte[] bytes = in.readAllBytes();
            String version = EmbeddedModLocator.class.getPackage().getImplementationVersion();
            Path dir = context.gameDirectory().resolve("checkengine").resolve("embedded");
            Path file = dir.resolve("checkengine-mod-" + (version == null ? "dev" : version) + ".jar");
            if (!Files.isRegularFile(file) || Files.size(file) != bytes.length) {
                Files.createDirectories(dir);
                Path tmp = Files.createTempFile(dir, "checkengine", ".tmp");
                Files.write(tmp, bytes);
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            pipeline.addPath(file, ModFileDiscoveryAttributes.DEFAULT, IncompatibleFileReporting.WARN_ALWAYS);
        } catch (IOException | RuntimeException e) {
            EarlyCheck.LOGGER.error("Check Engine: couldn't load the mod packed in its jar", e);
        }
    }
}
