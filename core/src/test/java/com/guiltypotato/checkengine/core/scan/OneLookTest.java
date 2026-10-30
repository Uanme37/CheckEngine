package com.guiltypotato.checkengine.core.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guiltypotato.checkengine.core.model.ModInfo;
import com.guiltypotato.checkengine.core.model.ModJar;
import com.guiltypotato.checkengine.core.ui.UiText;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The small fixes behind one look: one row per mod, reordered JSON isn't a change, headings and dates. */
class OneLookTest {
    @TempDir
    Path dir;

    @Test
    void aDuplicateModIsOneRowTheNewest() {
        List<ModJar> jars = List.of(jar("old.jar", "2.0.2"), jar("new.jar", "2.0.10"));
        List<ModRows> rows = ModRows.of(jars, Map.of(), Map.of(), Map.of(), null);
        assertEquals(1, rows.size());
    }

    private ModJar jar(String file, String version) {
        return new ModJar(dir.resolve(file), ModJar.Kind.NEOFORGE,
                List.of(new ModInfo("alexscaves", version, "Alex's Caves", List.of())), List.of(), null);
    }

    @Test
    void jsonWithItsKeysMovedIsTheSame() throws Exception {
        assertTrue(same("{\"a\": 1, \"b\": {\"c\": [1, 2], \"d\": \"x y\"}}", "{\"b\":{\"d\":\"x y\",\"c\":[1,2]},\"a\":1}"));
    }

    @Test
    void jsonWithAChangedValueOrListOrderIsNot() throws Exception {
        assertFalse(same("{\"a\": 1}", "{\"a\": 2}"));
        assertFalse(same("{\"a\": [1, 2]}", "{\"a\": [2, 1]}"));
        assertFalse(same("{\"a\": \"x y\"}", "{\"a\": \"xy\"}"));
    }

    @Test
    void brokenJsonIsNotTheSame() throws Exception {
        assertFalse(same("{\"a\": 1, \"b\": 2", "{\"b\": 2, \"a\": 1"));
    }

    private boolean same(String a, String b) throws Exception {
        Path x = dir.resolve("x.json"), y = dir.resolve("y.json");
        Files.writeString(x, a);
        Files.writeString(y, b);
        return ConfigCheckpoint.sameButComments(x, y);
    }

    @Test
    void headingsDatesAndCloseLabels() {
        assertEquals("Strip Mine found it", UiText.sentence("STRIP MINE FOUND IT"));
        assertEquals("Already fine", UiText.sentence("Already fine"));
        assertEquals("30 Oct 09:15", UiText.date("2026-10-30 09:15:02"));
        assertEquals("30 Oct 09:15", UiText.date("2026-10-30T09:15"));
        assertTrue(UiText.closes("Continue") && UiText.closes("Cancel") && UiText.closes("Quit"));
        assertFalse(UiText.closes("Full error"));
    }
}
