package com.guiltypotato.checkengine.core.strip;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * A small separate program the game starts during a Strip Mine search. A running game keeps its mod jars open, so
 * Windows won't let them be renamed; this waits for the game to close, then reads how the round went and switches
 * the next round's mods off ({@link StripMine#advance}). It never runs any game code.
 *
 * <p>Usage: {@code java -cp <check engine jar> ...StripMineHelper <game pid> <game dir> <mods dir> <checkengine dir>}
 */
public final class StripMineHelper {
    static final String LOG_FILE = "strip-mine.log";
    static final String LOCK_FILE = "strip-mine-helper.pid";

    private StripMineHelper() {}

    public static void main(String[] args) throws Exception {
        Path folder = Path.of(args[3]);
        try {
            run(Long.parseLong(args[0]), Path.of(args[1]), Path.of(args[2]), folder);
        } catch (Throwable t) {
            log(folder, "helper failed: " + t);
        }
    }

    private static void run(long pid, Path gameDir, Path mods, Path folder) throws Exception {
        log(folder, "waiting for game " + pid + " to close");
        Optional<ProcessHandle> game = ProcessHandle.of(pid);
        if (game.isPresent()) game.get().onExit().join();
        // Windows can take a moment to let go of the files after the process is gone.
        for (int attempt = 0; attempt < 10; attempt++) {
            try {
                StripMine.State s = StripMine.advance(gameDir, mods, folder, null);
                log(folder, s == null ? "no search running" : "round " + s.round() + ": " + s.status() + ", "
                        + s.suspects().size() + " suspects, " + s.disabled().size() + " switched off");
                return;
            } catch (IOException e) {
                log(folder, "attempt " + (attempt + 1) + " failed: " + e);
                Thread.sleep(1000);
            }
        }
    }

    /**
     * Starts the helper for this game: it outlives the game and sets up the next round when it closes. Returns false
     * if it couldn't be started (the next round is then set up by Check Pack.bat, or on the next launch's check).
     */
    public static boolean launch(Path gameDir, Path modsFolder, Path checkEngineFolder) {
        try {
            // One helper per game: a second one would count the same round twice.
            Path lock = checkEngineFolder.resolve(LOCK_FILE);
            String me = Long.toString(ProcessHandle.current().pid());
            if (Files.isRegularFile(lock) && Files.readString(lock).strip().equals(me)) return true;
            String java = ProcessHandle.current().info().command().orElse(null);
            Path jar = checkEngineJar(modsFolder);
            if (java == null || jar == null) return false;
            // Copy the jar out of the mods folder, so the helper doesn't hold a file the search might look at.
            Path copy = checkEngineFolder.resolve("strip-mine-helper.jar");
            Files.createDirectories(checkEngineFolder);
            Files.copy(jar, copy, StandardCopyOption.REPLACE_EXISTING);
            List<String> command = List.of(java, "-Xmx64m", "-cp", copy.toString(), StripMineHelper.class.getName(),
                    me, gameDir.toString(), modsFolder.toString(), checkEngineFolder.toString());
            boolean started = isWindows() ? startOutsideGame(command, checkEngineFolder) : false;
            if (!started) {
                new ProcessBuilder(command).redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.appendTo(checkEngineFolder.resolve(LOG_FILE).toFile()))
                        .start();
            }
            Files.writeString(lock, me);
            log(checkEngineFolder, "helper started for game " + me + (started ? " (outside the game's job)" : ""));
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /**
     * Launchers (and Gradle) put the game in a Windows job that closes every process it started when the game exits,
     * which would take the helper down with it. Windows' own process service (WMI) starts it outside that job.
     */
    private static boolean startOutsideGame(List<String> command, Path checkEngineFolder) {
        try {
            List<String> cmd = new java.util.ArrayList<>(command);
            Path javaw = Path.of(cmd.get(0)).resolveSibling("javaw.exe");
            if (Files.isRegularFile(javaw)) cmd.set(0, javaw.toString()); // no console window
            StringBuilder line = new StringBuilder();
            for (String part : cmd) line.append('"').append(part.replace("\"", "")).append("\" ");
            ProcessBuilder pb = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command",
                    "$r = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments "
                            + "@{CommandLine=$env:CE_HELPER_CMD; CurrentDirectory=$env:CE_HELPER_DIR}; "
                            + "exit $r.ReturnValue");
            pb.environment().put("CE_HELPER_CMD", line.toString().strip());
            pb.environment().put("CE_HELPER_DIR", checkEngineFolder.toString());
            pb.redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(checkEngineFolder.resolve(LOG_FILE).toFile()));
            Process p = pb.start();
            return p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException | RuntimeException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** The Check Engine jar in the mods folder: the one that holds this class. */
    static Path checkEngineJar(Path modsFolder) throws IOException {
        String entry = StripMineHelper.class.getName().replace('.', '/') + ".class";
        try (Stream<Path> files = Files.list(modsFolder)) {
            for (Path p : files.filter(f -> f.getFileName().toString().toLowerCase().startsWith("checkengine")
                    && f.getFileName().toString().endsWith(".jar")).toList()) {
                try (var zip = new java.util.zip.ZipFile(p.toFile())) {
                    if (zip.getEntry(entry) != null) return p;
                } catch (IOException ignored) {
                    // not ours
                }
            }
        }
        return null;
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
