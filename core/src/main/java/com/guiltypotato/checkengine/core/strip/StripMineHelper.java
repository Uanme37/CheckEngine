package com.guiltypotato.checkengine.core.strip;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Sets up the next Strip Mine round as the game closes. Nothing runs outside the game: when it exits (closed, quit
 * from the error screen, or crashed), Check Engine reads how the round went and switches the next round's mods off
 * ({@link StripMine#advance}). NeoForge 1.21.1 and Forge 1.20.1 let a running game's mod jars be renamed; NeoForge
 * 26.1 keeps them locked, so those stay as they are and the next launch has the loader skip them
 * ({@link StripMine#lockedOn}). If the game is killed instead, the hook doesn't run and the same round simply runs
 * again on the next launch.
 */
public final class StripMineHelper {
    static final String LOG_FILE = "strip-mine.log";
    private static final AtomicBoolean ARMED = new AtomicBoolean();

    private StripMineHelper() {}

    /** Arranges for the next round to be set up when this game closes. Safe to call more than once. */
    public static boolean launch(Path gameDir, Path modsFolder, Path checkEngineFolder) {
        if (!ARMED.compareAndSet(false, true)) return true;
        preloadCore();
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> onExit(gameDir, modsFolder, checkEngineFolder),
                    "Check Engine Strip Mine"));
            log(checkEngineFolder, "next round is set up when this game closes");
            return true;
        } catch (RuntimeException e) { // already shutting down
            ARMED.set(false);
            return false;
        }
    }

    private static synchronized void onExit(Path gameDir, Path modsFolder, Path checkEngineFolder) {
        try {
            StripMine.State s = StripMine.advance(gameDir, modsFolder, checkEngineFolder, null);
            log(checkEngineFolder, s == null ? "no search running" : "round " + s.round() + ": " + s.status() + ", "
                    + s.suspects().size() + " suspects, " + s.disabled().size() + " switched off");
        } catch (IOException | RuntimeException e) {
            log(checkEngineFolder, "couldn't set up the next round: " + e);
        }
    }

    /**
     * NeoForge 26.1 closes the class loader Check Engine's plugin code comes from before the game exits, so the exit
     * hook could no longer load anything new. Load every core class now, while it still can.
     */
    private static void preloadCore() {
        ClassLoader loader = StripMineHelper.class.getClassLoader();
        try {
            Path source = Path.of(StripMineHelper.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            String prefix = "com/guiltypotato/checkengine/core/";
            java.util.List<String> names = new java.util.ArrayList<>();
            if (Files.isDirectory(source)) { // dev and tests: plain class folders
                try (java.util.stream.Stream<Path> files = Files.walk(source.resolve(prefix))) {
                    files.map(p -> source.relativize(p).toString().replace('\\', '/')).forEach(names::add);
                }
            } else {
                try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(source.toFile())) {
                    zip.stream().map(java.util.zip.ZipEntry::getName).forEach(names::add);
                }
            }
            for (String n : names) {
                if (!n.startsWith(prefix) || !n.endsWith(".class")) continue;
                try {
                    Class.forName(n.substring(0, n.length() - 6).replace('/', '.'), false, loader);
                } catch (Throwable ignored) {
                    // not needed by Strip Mine
                }
            }
        } catch (Exception | LinkageError e) {
            // unknown layout: the hook still works wherever the class loader stays open
        }
    }

    static void log(Path folder, String line) {
        try {
            Files.createDirectories(folder);
            Files.writeString(folder.resolve(LOG_FILE), LocalDateTime.now().withNano(0) + " " + line + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // best effort
        }
    }
}
