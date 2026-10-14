package com.guiltypotato.checkengine.core.scan;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

/**
 * checkengine/pack-journal.txt: what changed between good launches, newest first. A changelog pack makers get for
 * free ("Oct 12: added 3 mods, changed 2 configs"), and the base for update notes.
 */
public final class PackJournal {
    public static final String FILE = "pack-journal.txt";
    /** Old entries past this size are dropped. */
    static final int MAX_CHARS = 200_000;
    private static final String HEAD = "Check Engine pack journal: what changed between launches that worked, newest first.\n";

    private PackJournal() {}

    /**
     * Adds an entry for a launch that worked, if anything changed. {@code configs}: include config changes (only when
     * the config checkpoint is saved too, or the same edits would be listed again next time).
     */
    public static void record(Path checkEngineFolder, Changes changes, boolean configs) throws IOException {
        if (changes == null) return;
        List<Changes.Change> list = changes.changes().stream()
                .filter(c -> configs || c.type() != Changes.Type.CONFIG).toList();
        if (list.isEmpty()) return;
        String when = LocalDateTime.now().withSecond(0).withNano(0).toString().replace('T', ' ');
        String entry = "== " + when + " ==\n" + new Changes(changes.since(), list).summary() + "\n\n";
        Path file = checkEngineFolder.resolve(FILE);
        String old = Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        if (old.startsWith(HEAD)) old = old.substring(HEAD.length()).stripLeading();
        String text = HEAD + "\n" + entry + old;
        if (text.length() > MAX_CHARS) {
            int cut = text.lastIndexOf("\n== ", MAX_CHARS);
            text = text.substring(0, cut > 0 ? cut + 1 : MAX_CHARS);
        }
        Files.createDirectories(checkEngineFolder);
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    /** The journal's text, or null if nothing was recorded yet. */
    public static String read(Path checkEngineFolder) throws IOException {
        Path file = checkEngineFolder.resolve(FILE);
        return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
    }
}
