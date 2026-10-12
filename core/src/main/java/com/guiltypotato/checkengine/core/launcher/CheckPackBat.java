package com.guiltypotato.checkengine.core.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Puts "Check Pack.bat" in the pack folder, so players who got the mod through CurseForge can check the pack
 * before the next launch. The .bat runs the checker from the mod jar in mods/, so nothing else is downloaded.
 */
public final class CheckPackBat {
    public static final String FILE_NAME = "Check Pack.bat";
    private static final String RESOURCE = "/com/guiltypotato/checkengine/core/launcher/Check Pack.bat";

    private CheckPackBat() {}

    /** Writes or updates the .bat in {@code gameDir}. Returns true if the file changed. Only on Windows. */
    public static boolean install(Path gameDir) throws IOException {
        if (!System.getProperty("os.name", "").startsWith("Windows")) return false;
        return install(gameDir, contents());
    }

    static boolean install(Path gameDir, byte[] bat) throws IOException {
        Path target = gameDir.resolve(FILE_NAME);
        if (Files.isRegularFile(target) && Arrays.equals(Files.readAllBytes(target), bat)) return false;
        Files.write(target, bat);
        return true;
    }

    static byte[] contents() throws IOException {
        try (InputStream in = CheckPackBat.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IOException(FILE_NAME + " is missing from the jar");
            return in.readAllBytes();
        }
    }
}
