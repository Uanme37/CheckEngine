package com.guiltypotato.checkengine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guiltypotato.checkengine.core.ui.EngineIcon;
import com.guiltypotato.checkengine.core.ui.ScreenHelp;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScreenHelpTest {
    @Test
    void headlinesGetTheirShortName() {
        assertEquals("Crash Doctor", ScreenHelp.place("YOUR LAST GAME CRASHED"));
        assertEquals("Crash Doctor", ScreenHelp.place("CRASHED BEFORE INSTALL"));
        assertEquals("Go back", ScreenHelp.place("GO BACK TO WHEN IT WORKED?"));
        assertEquals("Go back", ScreenHelp.place("COULDN'T GO BACK"));
        assertEquals("Strip Mine", ScreenHelp.place("STRIP MINE: FIND THE FAULTY MOD"));
        assertEquals("Put it back", ScreenHelp.place("PUT IT BACK?"));
        assertEquals("Before the game", ScreenHelp.place("Demo Pack won't start"));
        assertEquals("Pack updated", ScreenHelp.place("Demo Pack was updated"));
        assertNull(ScreenHelp.place("SOMETHING ELSE"));
        assertNull(ScreenHelp.place(null));
    }

    @Test
    void shortNamesFitTheColumn() {
        for (String h : List.of("YOUR LAST GAME CRASHED", "BACKING UP WORLDS...", "WORLDS STILL USE REMOVED MODS",
                "RESCUE WORLD: DEMO", "THE PACK WAS UPDATED", "Demo Pack won't start")) {
            assertTrue(ScreenHelp.place(h).length() <= 15, h);
        }
    }

    @Test
    void everyTabAndCodeHasWords() {
        for (String tab : List.of("Fix", "Crash lines", "Changed", "Get help")) {
            assertFalse(ScreenHelp.explain("Crash Doctor", tab, null).get(0).startsWith("This is a Check Engine"), tab);
        }
        for (String tab : List.of("Pit Stop", "Mods", "Go back", "Warnings", "Last crash")) {
            assertFalse(ScreenHelp.explain("Pit Stop", tab, null).get(0).startsWith("This is a Check Engine"), tab);
        }
        for (String code : ScreenHelp.codes()) {
            assertTrue(ScreenHelp.explain(null, null, code).get(1).startsWith(code), code);
        }
        assertTrue(ScreenHelp.codes().contains(ScreenHelp.CRASH_CODE));
    }

    @Test
    void unknownScreenStillSaysSomething() {
        List<String> words = ScreenHelp.explain(null, null, "ROUND 3");
        assertEquals(1, words.size());
        assertTrue(words.get(0).startsWith("This is a Check Engine"));
    }

    @Test
    void engineTextureHasThreeCells() throws java.io.IOException {
        try (java.io.InputStream in = ScreenHelp.class.getResourceAsStream("/checkengine_engine.png")) {
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(in);
            assertEquals(EngineIcon.CELL_W, img.getWidth());
            assertEquals(EngineIcon.CELL_H * 3, img.getHeight());
        }
    }
}
