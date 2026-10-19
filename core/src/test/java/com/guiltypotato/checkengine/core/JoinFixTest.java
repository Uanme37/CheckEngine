package com.guiltypotato.checkengine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guiltypotato.checkengine.core.scan.JoinFix;
import com.guiltypotato.checkengine.core.scan.JoinFix.Action;
import com.guiltypotato.checkengine.core.scan.JoinFix.Step;
import com.guiltypotato.checkengine.core.scan.JoinMismatch.Failure;
import com.guiltypotato.checkengine.core.scan.JoinMismatch.Kind;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JoinFixTest {
    @TempDir
    Path dir;

    @Test
    void switchesOffExtrasLinksTheRestAndCanUndo() throws Exception {
        Path mods = Files.createDirectories(dir.resolve("mods"));
        Path ce = dir.resolve("checkengine");
        Path oldCreate = TestJars.neoMod("create", "6.0.4", "").writeTo(mods, "create-6.0.4.jar");
        Path minimap = TestJars.neoMod("xaerominimap", "25.0", "").writeTo(mods, "xaerominimap.jar");

        List<Failure> failures = List.of(
                new Failure("create:main", Kind.DIFFERENT, "", "Create", "6.0.6"),
                new Failure("xaerominimap:main", Kind.MISSING_ON_SERVER, ""),
                new Failure("sophisticatedbackpacks:main", Kind.MISSING_ON_PLAYER, ""));
        List<Step> steps = JoinFix.plan(failures, Map.of("create", oldCreate, "xaerominimap", minimap),
                Map.of("create", "Create"));
        assertEquals(List.of(Action.OPEN_PAGE, Action.SWITCH_OFF, Action.OPEN_PAGE),
                steps.stream().map(Step::action).toList());
        assertEquals("https://modrinth.com/mods?q=Create", steps.get(0).url());
        assertTrue(steps.get(2).url().contains("modrinth.com/mods?q=sophisticatedbackpacks"), steps.get(2).url());

        // Never downloads a mod: the old Create stays, only the extra minimap is switched off.
        List<String> done = JoinFix.apply(steps, mods, ce);
        assertTrue(done.get(0).startsWith("Get Create 6.0.6 yourself"), done.get(0));
        assertTrue(Files.exists(mods.resolve("create-6.0.4.jar")));
        assertTrue(Files.exists(mods.resolve("xaerominimap.jar.disabled")));
        try (var list = Files.list(mods)) {
            assertEquals(2, list.count());
        }

        assertEquals(1, JoinFix.undo(mods, ce));
        assertTrue(Files.exists(mods.resolve("xaerominimap.jar")));
    }
}
