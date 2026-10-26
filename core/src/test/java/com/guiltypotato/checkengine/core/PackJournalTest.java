package com.guiltypotato.checkengine.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guiltypotato.checkengine.core.scan.Changes;
import com.guiltypotato.checkengine.core.scan.Changes.Change;
import com.guiltypotato.checkengine.core.scan.Changes.Type;
import com.guiltypotato.checkengine.core.scan.PackJournal;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Every launch that worked adds what changed, newest first; nothing changed means no entry. */
class PackJournalTest {
    @TempDir
    Path ce;

    @Test
    void newestFirstAndOnlyRealChanges() throws Exception {
        assertNull(PackJournal.read(ce));
        PackJournal.record(ce, new Changes("2026-10-12T10:00", List.of()), true);
        assertNull(PackJournal.read(ce), "nothing changed: no entry");

        PackJournal.record(ce, new Changes("2026-10-12T10:00", List.of(
                new Change(Type.ADDED, "create", "Create", null, "6.0.4", "create-6.0.4.jar"))), true);
        PackJournal.record(ce, new Changes("2026-10-13T10:00", List.of(
                new Change(Type.UPDATED, "jei", "JEI", "19.0", "19.1", "jei.jar"),
                new Change(Type.CONFIG, "config", "config/x.toml", null, "changed", "config/x.toml"))), false);

        String text = PackJournal.read(ce);
        assertTrue(text.indexOf("Updated: JEI 19.0 -> 19.1") < text.indexOf("Added: Create 6.0.4"), text);
        assertFalse(text.contains("config/x.toml"), "configs left out when the checkpoint isn't saved");
        assertTrue(text.startsWith("Check Engine pack journal"), text);
    }
}
