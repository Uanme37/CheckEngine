package com.guiltypotato.checkengine.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guiltypotato.checkengine.core.scan.Changes;
import com.guiltypotato.checkengine.core.scan.PackJournal;
import com.guiltypotato.checkengine.core.scan.PackSnapshot;
import com.guiltypotato.checkengine.core.scan.UpdateNotes;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Notes since the last release: net changes, so a mod added and removed in between doesn't show. */
class UpdateNotesTest {
    @TempDir
    Path ce;

    private static PackSnapshot pack(String time, Map<String, PackSnapshot.Entry> mods) {
        return new PackSnapshot(time, "1.21.1", "21.1.252", mods);
    }

    @Test
    void netChangesSinceTheLastRelease() throws Exception {
        assertNull(UpdateNotes.write(ce));
        pack("2026-10-01T10:00", Map.of(
                "create", new PackSnapshot.Entry("Create", "6.0.3", "create.jar"),
                "oldie", new PackSnapshot.Entry("Oldie", "1.0", "oldie.jar"),
                "jei", new PackSnapshot.Entry("JEI", "19.1", "jei.jar"))).save(ce);
        UpdateNotes.markReleased(ce);
        assertNull(PackJournal.read(ce));

        // Since then: Create updated, Oldie removed, JEI rolled back, Shiny added, a config edited twice
        PackJournal.record(ce, new Changes("x", List.of(new Changes.Change(Changes.Type.CONFIG, "config",
                "config/a.toml", null, "changed", "config/a.toml"))), true);
        PackJournal.record(ce, new Changes("x", List.of(new Changes.Change(Changes.Type.CONFIG, "config",
                "config/a.toml", null, "changed", "config/a.toml"))), true);
        pack("2026-10-14T10:00", Map.of(
                "create", new PackSnapshot.Entry("Create", "6.0.4", "create.jar"),
                "jei", new PackSnapshot.Entry("JEI", "19.0", "jei.jar"),
                "shiny", new PackSnapshot.Entry("Shiny", "2.0", "shiny.jar"),
                "checkengine", new PackSnapshot.Entry("Check Engine", "0.13.0", "ce.jar"))).save(ce);

        String notes = UpdateNotes.write(ce);
        assertTrue(notes.contains("### New mods\n- Shiny 2.0"), notes);
        assertTrue(notes.contains("### Updated\n- Create 6.0.3 → 6.0.4"), notes);
        assertTrue(notes.contains("### Rolled back\n- JEI 19.1 → 19.0"), notes);
        assertTrue(notes.contains("### Removed\n- Oldie"), notes);
        assertTrue(notes.contains("- 1 config file changed"), notes);
        assertFalse(notes.contains("Check Engine"), "Check Engine itself isn't news for players");
    }
}
