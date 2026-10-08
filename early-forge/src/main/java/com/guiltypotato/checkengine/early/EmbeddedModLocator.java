package com.guiltypotato.checkengine.early;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.stream.Stream;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.fml.loading.moddiscovery.AbstractJarFileModLocator;

/**
 * Forge loads this jar as a plugin, not as a mod, so this hands it the real Check Engine mod packed inside (the
 * same trick Monocle uses on NeoForge). Forge only reads mods from real files, so the inner jar is unpacked to
 * checkengine/embedded/ first. Players still install one jar.
 */
public class EmbeddedModLocator extends AbstractJarFileModLocator {
    static final String INNER_MOD = "/META-INF/jarjar/checkengine-mod.jar";

    @Override
    public Stream<Path> scanCandidates() {
        try (InputStream in = EmbeddedModLocator.class.getResourceAsStream(INNER_MOD)) {
            if (in == null) return Stream.empty();
            byte[] bytes = in.readAllBytes();
            String version = EmbeddedModLocator.class.getPackage().getImplementationVersion();
            Path dir = FMLPaths.GAMEDIR.get().resolve("checkengine").resolve("embedded");
            Path file = dir.resolve("checkengine-mod-" + (version == null ? "dev" : version) + ".jar");
            if (!Files.isRegularFile(file) || Files.size(file) != bytes.length) {
                Files.createDirectories(dir);
                Path tmp = Files.createTempFile(dir, "checkengine", ".tmp");
                Files.write(tmp, bytes);
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            return Stream.of(file);
        } catch (IOException | RuntimeException e) {
            EarlyCheck.LOGGER.error("Check Engine: couldn't load the mod packed in its jar", e);
            return Stream.empty();
        }
    }

    @Override
    public String name() {
        return "checkengine embedded mod";
    }

    @Override
    public void initArguments(Map<String, ?> arguments) {
    }
}
