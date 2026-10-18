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

    /** A pretend Modrinth: answers by URL prefix, serves files from a map. */
    private static final class FakeModrinth implements JoinFix.Web {
        final Map<String, String> pages = new HashMap<>();
        final Map<String, byte[]> files = new HashMap<>();

        @Override
        public String get(String url) {
            return pages.entrySet().stream().filter(e -> url.startsWith(e.getKey())).map(Map.Entry::getValue)
                    .findFirst().orElse(null);
        }

        @Override
        public void download(String url, Path to) throws IOException {
            byte[] b = files.get(url);
            if (b == null) throw new IOException("404");
            Files.write(to, b);
        }
    }

    @Test
    void switchesOffExtrasDownloadsExactVersionsAndCanUndo() throws Exception {
        Path mods = Files.createDirectories(dir.resolve("mods"));
        Path ce = dir.resolve("checkengine");
        Path oldCreate = TestJars.neoMod("create", "6.0.4", "").writeTo(mods, "create-6.0.4.jar");
        Path minimap = TestJars.neoMod("xaerominimap", "25.0", "").writeTo(mods, "xaerominimap.jar");
        byte[] newCreate = TestJars.neoMod("create", "6.0.6", "").bytes();

        FakeModrinth web = new FakeModrinth();
        web.pages.put("https://api.modrinth.com/v2/version_file/", "{\"project_id\": \"LNytGWDc\"}");
        web.pages.put("https://api.modrinth.com/v2/project/LNytGWDc/version", """
                [{"version_number": "6.0.60", "files": []},
                 {"version_number": "mc1.21.1-6.0.6", "files": [
                   {"url": "https://cdn.modrinth.com/create-6.0.6.jar", "filename": "create-6.0.6.jar", "primary": true}]}]
                """);
        web.files.put("https://cdn.modrinth.com/create-6.0.6.jar", newCreate);

        List<Failure> failures = List.of(
                new Failure("create:main", Kind.DIFFERENT, "", "Create", "6.0.6"),
                new Failure("xaerominimap:main", Kind.MISSING_ON_SERVER, ""),
                new Failure("sophisticatedbackpacks:main", Kind.MISSING_ON_PLAYER, ""));
        List<Step> steps = JoinFix.plan(failures, Map.of("create", oldCreate, "xaerominimap", minimap),
                Map.of("create", "Create"), "1.21.1", "neoforge", web);
        assertEquals(List.of(Action.DOWNLOAD, Action.SWITCH_OFF, Action.OPEN_PAGE),
                steps.stream().map(Step::action).toList());
        assertTrue(steps.get(2).url().contains("modrinth.com/mods?q=sophisticatedbackpacks"), steps.get(2).url());

        List<String> done = JoinFix.apply(steps, mods, ce, web);
        assertEquals("Installed Create 6.0.6", done.get(0));
        assertTrue(Files.exists(mods.resolve("create-6.0.6.jar")));
        assertTrue(Files.exists(mods.resolve("create-6.0.4.jar.disabled")));
        assertTrue(Files.exists(mods.resolve("xaerominimap.jar.disabled")));

        assertEquals(3, JoinFix.undo(mods, ce));
        assertTrue(Files.exists(mods.resolve("create-6.0.4.jar")));
        assertTrue(Files.exists(mods.resolve("xaerominimap.jar")));
        assertTrue(Files.exists(mods.resolve("create-6.0.6.jar.disabled")));
    }

    @Test
    void wrongFileFromModrinthIsNotInstalled() throws Exception {
        Path mods = Files.createDirectories(dir.resolve("mods"));
        FakeModrinth web = new FakeModrinth();
        web.pages.put("https://api.modrinth.com/v2/project/jei/version", """
                [{"version_number": "19.0.0", "files": [{"url": "https://cdn/x.jar", "filename": "x.jar"}]}]
                """);
        web.pages.put("https://api.modrinth.com/v2/project/jei", "{\"id\": \"jei\"}");
        web.files.put("https://cdn/x.jar", TestJars.neoMod("notjei", "19.0.0", "").bytes());
        List<Step> steps = JoinFix.plan(List.of(new Failure("jei:main", Kind.MISSING_ON_PLAYER, "", "JEI", "19.0.0")),
                Map.of(), Map.of(), "1.21.1", "neoforge", web);
        assertEquals(Action.DOWNLOAD, steps.get(0).action());
        List<String> done = JoinFix.apply(steps, mods, dir.resolve("checkengine"), web);
        assertTrue(done.get(0).startsWith("Didn't install JEI"), done.get(0));
        try (var s = Files.list(mods)) {
            assertEquals(0, s.count());
        }
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
