package com.guiltypotato.checkengine.core.scan;

import com.guiltypotato.checkengine.core.crash.CrashDoctor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * When a launch counts as "it worked". Reaching the title screen isn't enough: a world can still crash later. So the
 * title screen saves the pack's state into checkengine/pending-good-launch/, and the next start moves it to the real
 * last-good place only when that session left no crash report and no hs_err file, and closed normally (the shutdown
 * hook wrote session-end.txt). Otherwise it's set aside, and the last launch that really worked stays the one to compare
 * against and go back to, with its kept jars.
 */
public final class GoodLaunch {
    /** Where the title screen saves; checked and moved at the next start. */
    public static final String PENDING = "pending-good-launch";
    /** The mods of a launch that reached the title screen but crashed later (kept to know it got that far). */
    public static final String CRASHED = "crashed-launch.properties";

    /**
     * The mods of a launch that reached the title screen but never closed normally: killed (Task Manager, the
     * launcher's Kill), frozen, or the PC lost power. Nobody knows if it worked, so it never becomes the last good one.
     */
    public static final String UNFINISHED = "unfinished-launch.properties";

    /** Written the moment the title screen shows: this launch got through startup, whatever happens after. */
    public static final String TITLE = "title-reached.txt";

    /** Written as the game closes: configs changed before then were changed while the pack worked. */
    public static final String SESSION_END = "session-end.txt";

    public enum Result { NOTHING, WORKED, CRASHED, UNFINISHED }

    private GoodLaunch() {}

    /** The folder the title screen saves into this launch. */
    public static Path pending(Path checkEngineFolder) {
        return checkEngineFolder.resolve(PENDING);
    }

    /** Called on the title screen (or when the server is ready), every time: marks that startup worked. */
    public static void reachedTitle(Path checkEngineFolder) throws IOException {
        Files.createDirectories(checkEngineFolder);
        Files.writeString(checkEngineFolder.resolve(TITLE), java.time.LocalDateTime.now().withNano(0).toString());
    }

    /** Called as the game closes (a shutdown hook set on the title screen): when this session ended. */
    public static void sessionEnded(Path checkEngineFolder) {
        try {
            Files.writeString(checkEngineFolder.resolve(SESSION_END), Long.toString(System.currentTimeMillis()));
        } catch (IOException | RuntimeException ignored) {
            // then only the configs from the title screen count
        }
    }

    /**
     * Called on the title screen (or when the server is ready), not on a launch that shows a crash: saves the configs,
     * keeps the jars (KeptJars) and the mods' list into the pending folder. The list goes last, so a save cut short
     * by the game closing never counts. Reads every new jar once, so run it off the game's thread. Returns the note
     * when old versions can't be kept here, or null.
     */
    public static String save(Path gameDir, Path checkEngineFolder, PackSnapshot snapshot) throws IOException {
        long title = System.currentTimeMillis();
        Path pending = pending(checkEngineFolder);
        clear(pending);
        ConfigCheckpoint.save(gameDir, checkEngineFolder, pending);
        String note = KeptJars.keep(gameDir.resolve("mods"), checkEngineFolder, pending);
        snapshot.save(pending);
        // Dated when the title screen showed, not when saving ended: a crash while the jars were read still counts
        Files.setLastModifiedTime(pending.resolve(PackSnapshot.FILE), java.nio.file.attribute.FileTime.fromMillis(title));
        return note;
    }

    /**
     * Called first thing at every start (early plugin, mod, CLI): settles what the last session's title screen saved.
     * Safe to call more than once: the second call finds nothing.
     */
    public static Result settle(Path gameDir, Path checkEngineFolder) {
        Path pending = pending(checkEngineFolder);
        Path snapshot = pending.resolve(PackSnapshot.FILE);
        if (!Files.isRegularFile(snapshot)) return Result.NOTHING;
        try {
            long saved = Files.getLastModifiedTime(snapshot).toMillis();
            boolean crashed = CrashDoctor.crashFiles(gameDir).stream().anyMatch(f -> modified(f) >= saved);
            if (crashed) {
                Files.move(snapshot, checkEngineFolder.resolve(CRASHED), StandardCopyOption.REPLACE_EXISTING);
                clear(pending);
                return Result.CRASHED;
            }
            long end = sessionEnd(checkEngineFolder);
            if (end < saved) { // no normal close after the title screen: killed, so not known to have worked
                Files.move(snapshot, checkEngineFolder.resolve(UNFINISHED), StandardCopyOption.REPLACE_EXISTING);
                clear(pending);
                return Result.UNFINISHED;
            }
            ConfigCheckpoint.promote(pending, checkEngineFolder);
            // what mods and the player changed in game while it worked counts too
            ConfigCheckpoint.refresh(gameDir, checkEngineFolder, saved, end);
            KeptJars.promote(pending, checkEngineFolder);
            Files.move(snapshot, checkEngineFolder.resolve(PackSnapshot.FILE), StandardCopyOption.REPLACE_EXISTING);
            clear(pending);
            return Result.WORKED;
        } catch (IOException | RuntimeException e) {
            return Result.NOTHING; // tried again next start
        }
    }

    private static long sessionEnd(Path checkEngineFolder) {
        try {
            return Long.parseLong(Files.readString(checkEngineFolder.resolve(SESSION_END)).strip());
        } catch (IOException | RuntimeException e) {
            return -1;
        }
    }

    /**
     * Drops what this session's title screen saved (Go back ran in game: the pack this session started with is
     * no longer the one on disk, so it must not become the last good one at the next start).
     */
    public static void discardPending(Path checkEngineFolder) throws IOException {
        clear(pending(checkEngineFolder));
    }

    /** Empties the pending folder: only Check Engine's own files (copies and links), never the player's. */
    static void clear(Path pending) throws IOException {
        if (!Files.exists(pending)) return;
        try (Stream<Path> s = Files.walk(pending)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }

    private static long modified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }
}
