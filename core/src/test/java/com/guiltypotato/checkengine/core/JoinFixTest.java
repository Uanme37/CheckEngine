package com.guiltypotato.checkengine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guiltypotato.checkengine.core.scan.JoinFix;
import com.guiltypotato.checkengine.core.scan.JoinFix.Action;
import com.guiltypotato.checkengine.core.scan.JoinFix.Step;
import com.guiltypotato.checkengine.core.scan.JoinMismatch.Failure;
import com.guiltypotato.checkengine.core.scan.JoinMismatch.Kind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JoinFixTest {
    @TempDir
    Path dir;

    /** A pretend Modrinth: answers by URL prefix. */
    private static final class FakeModrinth implements JoinFix.Web {
        final Map<String, String> pages = new HashMap<>();

        @Override
        public String get(String url) {
            return pages.entrySet().stream().filter(e -> url.startsWith(e.getKey())).map(Map.Entry::getValue)
                    .findFirst().orElse(null);
        }
    }

    @Test
    void switchesOffExtrasLinksExactVersionsAndCanUndo() throws Exception {
        Path mods = Files.createDirectories(dir.resolve("mods"));
        Path ce = dir.resolve("checkengine");
        Path oldCreate = TestJars.neoMod("create", "6.0.4", "").writeTo(mods, "create-6.0.4.jar");
        Path minimap = TestJars.neoMod("xaerominimap", "25.0", "").writeTo(mods, "xaerominimap.jar");

        FakeModrinth web = new FakeModrinth();
        web.pages.put("https://api.modrinth.com/v2/version_file/", "{\"project_id\": \"LNytGWDc\"}");
        web.pages.put("https://api.modrinth.com/v2/project/LNytGWDc/version", """
                [{"id": "x1", "version_number": "6.0.60"},
                 {"id": "abc", "version_number": "mc1.21.1-6.0.6"}]
                """);

        List<Failure> failures = List.of(
                new Failure("create:main", Kind.DIFFERENT, "", "Create", "6.0.6"),
                new Failure("xaerominimap:main", Kind.MISSING_ON_SERVER, ""),
                new Failure("sophisticatedbackpacks:main", Kind.MISSING_ON_PLAYER, ""));
        List<Step> steps = JoinFix.plan(failures, Map.of("create", oldCreate, "xaerominimap", minimap),
                Map.of("create", "Create"), "1.21.1", "neoforge", web);
        assertEquals(List.of(Action.OPEN_PAGE, Action.SWITCH_OFF, Action.OPEN_PAGE),
                steps.stream().map(Step::action).toList());
        assertTrue(steps.get(2).url().contains("modrinth.com/mods?q=sophisticatedbackpacks"), steps.get(2).url());

        assertEquals("https://modrinth.com/mod/LNytGWDc/version/abc", steps.get(0).url());

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

    @Test
    void noServerVersionMeansAPageNotAGuess() {
        FakeModrinth web = new FakeModrinth();
        web.pages.put("https://api.modrinth.com/v2/project/jei", "{\"id\": \"u6dRKJwZ\"}");
        List<Step> steps = JoinFix.plan(List.of(new Failure("jei:main", Kind.MISSING_ON_PLAYER, "")),
                Map.of(), Map.of(), "1.21.1", "neoforge", web);
        assertEquals(Action.OPEN_PAGE, steps.get(0).action());
        assertEquals("https://modrinth.com/mod/u6dRKJwZ", steps.get(0).url());
    }
}
