package com.guiltypotato.checkengine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guiltypotato.checkengine.core.scan.Finding;
import com.guiltypotato.checkengine.core.scan.LoadBlockers;
import com.guiltypotato.checkengine.core.scan.LoadBlockers.Blocker;
import com.guiltypotato.checkengine.core.scan.LoadBlockers.Kind;
import java.util.List;
import org.junit.jupiter.api.Test;

class LoadBlockersTest {
    @Test
    void groupsModsThatNeedTheSameMissingMod() {
        List<Finding> f = LoadBlockers.explain(List.of(
                new Blocker(Kind.MISSING, "Addon A", "create", null, "[6.0,)", null, null),
                new Blocker(Kind.MISSING, "Addon B", "create", null, "[6.0,)", null, null),
                new Blocker(Kind.MISSING, "Addon C", "jei", null, "[19,)", null, null)));
        assertEquals(2, f.size());
        assertEquals("Missing mod: create", f.get(0).title());
        assertTrue(f.get(0).detail().startsWith("2 mods need create"), f.get(0).detail());
        assertTrue(f.get(0).fix().contains("remove the 2 mods"), f.get(0).fix());
        assertTrue(f.get(1).detail().contains("Addon C needs jei (19 or newer)"), f.get(1).detail());
    }

    @Test
    void wrongVersionSaysWhatToUpdate() {
        Finding f = LoadBlockers.explain(List.of(
                new Blocker(Kind.WRONG_VERSION, "Addon A", "create", "Create", "[6.0,)", "5.1.0", "needs the new API")))
                .get(0);
        assertEquals("Wrong version: Addon A needs Create 6.0 or newer", f.title());
        assertTrue(f.detail().contains("this pack has 5.1.0"), f.detail());
        assertTrue(f.detail().contains("The mod says: \"needs the new API\""), f.detail());
        assertTrue(f.fix().startsWith("Update Create to 6.0 or newer"), f.fix());
    }

    @Test
    void wrongMinecraftAsksForTheRightBuild() {
        Finding f = LoadBlockers.explain(List.of(
                new Blocker(Kind.WRONG_VERSION, "Old Mod", "minecraft", "Minecraft", "[1.20,1.21)", "1.21.1", null)))
                .get(0);
        assertEquals("Use a version of Old Mod made for Minecraft 1.21.1.", f.fix());
    }

    @Test
    void incompatibleNamesBoth() {
        Finding f = LoadBlockers.explain(List.of(
                new Blocker(Kind.INCOMPATIBLE, "Sodium", "embeddium", "Embeddium", "*", "1.0", null))).get(0);
        assertEquals("Sodium doesn't work with Embeddium", f.title());
        assertEquals("Remove Sodium or Embeddium.", f.fix());
    }
}
