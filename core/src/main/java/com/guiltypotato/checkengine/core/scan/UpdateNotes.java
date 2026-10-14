package com.guiltypotato.checkengine.core.scan;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

/**
 * Update notes for a pack release, ready to paste: what's new, updated and gone since the last release, in player
 * words. Compares the pack as it last worked with the one marked at the last release ({@link #markReleased}), so a mod
 * added and removed again in between doesn't show.
 */
public final class UpdateNotes {
    /** The pack as it was at the last release (a {@link PackSnapshot}). */
    public static final String BASELINE = "notes-baseline.properties";

    private UpdateNotes() {}

    /**
     * The notes, or null when there's nothing to compare yet (no launch that worked, or no release marked: then
     * {@link #fromJournal} is the fallback).
     */
    public static String write(Path checkEngineFolder) {
        PackSnapshot now = PackSnapshot.load(checkEngineFolder);
        PackSnapshot then = PackSnapshot.load(checkEngineFolder, BASELINE);
        if (now == null || then == null) return null;
        Changes changes = then.changesTo(now);
        List<Changes.Change> mods = changes.changes().stream().filter(c -> !c.id().equals("checkengine")).toList();
        StringBuilder sb = new StringBuilder("## Update " + LocalDate.now() + "\n");
        section(sb, "New mods", mods, Changes.Type.ADDED, false);
        section(sb, "Updated", mods, Changes.Type.UPDATED, false);
        section(sb, "Rolled back", mods, Changes.Type.UPDATED, true);
        section(sb, "Removed", mods, Changes.Type.REMOVED, false);
        int configs = configsChangedSince(checkEngineFolder, then.time());
        if (configs > 0) {
            sb.append("\n### Tweaks\n- ").append(configs).append(" config file").append(configs == 1 ? "" : "s")
                    .append(" changed\n");
        }
        if (mods.isEmpty() && configs == 0) sb.append("\nNo mod changes since the last update.\n");
        return sb.toString();
    }

    private static void section(StringBuilder sb, String title, List<Changes.Change> mods, Changes.Type type,
                                boolean downgrades) {
        List<Changes.Change> list = mods.stream().filter(c -> c.type() == type && c.downgrade() == downgrades)
                .sorted((a, b) -> a.name().compareToIgnoreCase(b.name())).toList();
        if (list.isEmpty()) return;
        sb.append("\n### ").append(title).append('\n');
        for (Changes.Change c : list) {
            sb.append("- ").append(c.name());
            switch (type) {
                case ADDED -> sb.append(' ').append(c.to());
                case UPDATED -> sb.append(' ').append(c.from()).append(" → ").append(c.to());
                default -> { }
            }
            sb.append('\n');
        }
    }

    /** Config files listed in the journal after {@code since} ("2026-10-12T10:00"), counted once each. */
    static int configsChangedSince(Path checkEngineFolder, String since) {
        String journal;
        try {
            journal = PackJournal.read(checkEngineFolder);
        } catch (IOException e) {
            return 0;
        }
        if (journal == null) return 0;
        String cut = since == null ? "" : since.replace('T', ' ');
        java.util.Set<String> files = new java.util.HashSet<>();
        String when = null;
        for (String line : journal.split("\r?\n")) {
            if (line.startsWith("== ") && line.endsWith(" ==")) {
                when = line.substring(3, line.length() - 3);
            } else if (line.startsWith("Configs: ") && when != null && when.compareTo(cut) > 0) {
                // "Configs: config/a.toml (changed), config/b.toml (added), and 3 more"
                for (String part : line.substring("Configs: ".length()).split(", ")) {
                    if (part.startsWith("and ") && part.endsWith(" more")) {
                        try {
                            int more = Integer.parseInt(part.substring(4, part.length() - 5).strip());
                            for (int i = 0; i < more; i++) files.add(when + "#" + i);
                        } catch (NumberFormatException ignored) {
                            // not a count
                        }
                    } else {
                        files.add(part.replaceAll(" \\(.*\\)$", ""));
                    }
                }
            }
        }
        return files.size();
    }

    /** First notes (no release marked yet): the journal so far, newest first. Null if there's no journal either. */
    public static String fromJournal(Path checkEngineFolder) throws IOException {
        String journal = PackJournal.read(checkEngineFolder);
        if (journal == null) return null;
        int start = journal.indexOf("\n== ");
        return "## Changes so far (from the pack journal)\n" + (start < 0 ? journal : journal.substring(start + 1));
    }

    /** From this many mod changes since the last launch it looks like a pack update (not one mod the player added). */
    public static final int PLAYER_MIN_CHANGES = 3;

    /**
     * What a player sees after a pack update: the mod changes since the last launch that worked (no configs, not
     * Check Engine itself). Empty when it doesn't look like a pack update.
     */
    public static List<Changes.Change> forPlayers(Changes changes) {
        if (changes == null) return List.of();
        List<Changes.Change> mods = changes.changes().stream().filter(c -> c.type() != Changes.Type.CONFIG
                && !c.id().equals("checkengine")).toList();
        return mods.size() >= PLAYER_MIN_CHANGES ? mods : List.of();
    }

    /** "This version is out": the next notes start from the pack as it is now. */
    public static void markReleased(Path checkEngineFolder) throws IOException {
        PackSnapshot now = PackSnapshot.load(checkEngineFolder);
        if (now == null) throw new IOException("The pack hasn't started with Check Engine installed yet.");
        now.save(checkEngineFolder, BASELINE);
    }

    /** Saves the notes as checkengine/update-notes-&lt;date&gt;.md and returns the file. */
    public static Path save(Path checkEngineFolder, String notes) throws IOException {
        Path out = checkEngineFolder.resolve("update-notes-" + LocalDate.now() + ".md");
        Files.createDirectories(checkEngineFolder);
        Files.writeString(out, notes, StandardCharsets.UTF_8);
        return out;
    }
}
