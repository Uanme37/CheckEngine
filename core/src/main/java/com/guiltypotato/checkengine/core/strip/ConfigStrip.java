package com.guiltypotato.checkengine.core.strip;

import com.guiltypotato.checkengine.core.scan.Changes;
import com.guiltypotato.checkengine.core.scan.ConfigCheckpoint;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Strip Mine over config files: when no mod changed since the pack last worked but several configs did, each round
 * puts half of the changed configs back as they were then. The player's own versions wait in
 * checkengine/config-strip/ and always come back.
 */
public final class ConfigStrip {
    /** Where the player's own versions wait while a round has the old ones in place. */
    static final String KEEP = "config-strip";

    private ConfigStrip() {}

    /**
     * The changed config files a search would go through, or empty when a mod search fits better: a mod changed
     * since the pack last worked, or fewer than 2 configs did ("Undo config changes" covers one).
     */
    public static List<String> suspects(Path gameDir, Path checkEngineFolder, Changes modChanges) {
        if (modChanges != null && modChanges.changes().stream().anyMatch(c -> c.type() != Changes.Type.CONFIG
                && !c.id().equals("checkengine"))) {
            return List.of();
        }
        List<String> out = ConfigCheckpoint.changes(gameDir, checkEngineFolder).stream()
                .filter(c -> c.kind() != ConfigCheckpoint.Kind.REMOVED).map(ConfigCheckpoint.Change::path).sorted()
                .toList();
        return out.size() < 2 ? List.of() : out;
    }

    /** As {@link #suspects(Path, Path, Changes)}, reading the mods folder itself (game closed: Check Pack.bat). */
    public static List<String> suspects(Path gameDir, Path checkEngineFolder) {
        com.guiltypotato.checkengine.core.scan.PackSnapshot last =
                com.guiltypotato.checkengine.core.scan.PackSnapshot.load(checkEngineFolder);
        if (last == null) return List.of();
        try {
            var jars = new ArrayList<>(StripMine.readJars(gameDir.resolve("mods"), List.of()).values());
            var now = com.guiltypotato.checkengine.core.scan.PackSnapshot.of(jars,
                    new com.guiltypotato.checkengine.core.scan.ScanOptions(
                            com.guiltypotato.checkengine.core.model.Side.CLIENT, null, null, Set.of()));
            return suspects(gameDir, checkEngineFolder, last.changesTo(now));
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    /** Starts a search over these config files (see {@link #suspects}). */
    public static StripMine.State start(Path gameDir, Path checkEngineFolder, List<String> suspects, String crash,
                                        boolean startup, boolean applyNow) throws IOException {
        if (suspects.size() < 2) throw new IOException("Strip Mine needs at least 2 changed config files to search.");
        List<String> notes = List.of("Searching the " + suspects.size() + " config files changed since the pack "
                + "last worked (no mod changed)");
        StripMine.State s = new StripMine.State(crash, startup, StripMine.Status.PENDING, 1, suspects,
                new ArrayList<>(suspects.subList(0, suspects.size() / 2)), List.of(), 0, suspects.size(), notes,
                List.of(), 0, List.of(), true);
        StripMine.save(checkEngineFolder, s);
        return applyNow ? apply(gameDir, checkEngineFolder, s) : s;
    }

    /** Puts this round's files back to their old versions and the others back to the player's. */
    static StripMine.State apply(Path gameDir, Path checkEngineFolder, StripMine.State s) throws IOException {
        Set<String> want = new LinkedHashSet<>(s.toDisable());
        List<String> nowOff = new ArrayList<>();
        for (String f : s.disabled()) {
            if (want.contains(f)) {
                nowOff.add(f);
            } else {
                putBack(gameDir, checkEngineFolder, f);
            }
        }
        for (String f : want) {
            if (nowOff.contains(f)) continue;
            Path live = gameDir.resolve(f);
            Path keep = checkEngineFolder.resolve(KEEP).resolve(f);
            if (!Files.isRegularFile(live)) continue; // gone meanwhile: nothing to test
            Files.createDirectories(keep.getParent());
            Files.move(live, keep, StandardCopyOption.REPLACE_EXISTING);
            Path old = ConfigCheckpoint.savedCopy(checkEngineFolder, f);
            if (Files.isRegularFile(old)) Files.copy(old, live, StandardCopyOption.REPLACE_EXISTING); // new file: none
            nowOff.add(f);
        }
        StripMine.State running = s.with(StripMine.Status.RUNNING, s.round(), s.suspects(), s.toDisable(), nowOff,
                System.currentTimeMillis(), s.notes());
        StripMine.save(checkEngineFolder, running);
        return running;
    }

    /** The player's own version of {@code f} goes back in place (over the old one a round put there). */
    static void putBack(Path gameDir, Path checkEngineFolder, String f) throws IOException {
        Path keep = checkEngineFolder.resolve(KEEP).resolve(f);
        if (!Files.isRegularFile(keep)) return;
        Path live = gameDir.resolve(f);
        Files.createDirectories(live.getParent());
        Files.move(keep, live, StandardCopyOption.REPLACE_EXISTING);
    }
}
