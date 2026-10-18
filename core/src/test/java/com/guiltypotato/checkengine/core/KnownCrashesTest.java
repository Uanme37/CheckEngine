package com.guiltypotato.checkengine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guiltypotato.checkengine.core.crash.CrashTranslator;
import com.guiltypotato.checkengine.core.crash.KnownCrashes;
import com.guiltypotato.checkengine.core.scan.Finding;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KnownCrashesTest {
    @Test
    void jarCarriesTheList() {
        KnownCrashes list = KnownCrashes.bundled();
        assertTrue(list.version() >= 1);
        assertTrue(list.entries().size() >= 4);
        // Every entry must be able to match something, or it can never help anyone.
        list.entries().forEach(e -> assertTrue(!e.all().isEmpty() || !e.any().isEmpty(), e.id()));
    }

    @Test
    void matchingNeedsAllAnyAndNone() {
        KnownCrashes list = KnownCrashes.parse("""
                {"version": 3, "crashes": [
                  {"id": "a", "title": "A", "fix": "do a", "all": ["foo", "bar"], "none": ["baz"]},
                  {"id": "b", "title": "B", "fix": "do b", "any": ["qux", "quux"]},
                  {"id": "broken", "title": "no fix"}
                ]}
                """);
        assertEquals(2, list.entries().size());
        assertEquals("a", list.match("foo and bar").id());
        assertEquals(null, list.match("foo bar baz"));
        assertEquals("b", list.match("only quux here").id());
        assertNull(list.match("foo only"));
    }

    @Test
    void knownCrashGoesFirstInATranslation() {
        String report = """
                ---- Minecraft Crash Report ----
                Time: 2026-10-18 06:00:00
                Description: Rendering overlay

                java.lang.IllegalStateException: boom
                	at com.example.Thing.run(Thing.java:1)

                A detailed walkthrough of the error
                Stacktrace:
                	at RTSSHooks64.dll+0x1234
                """;
        Finding main = CrashTranslator.translate(report).findings().get(0);
        assertEquals(KnownCrashes.CODE, main.code());
        assertTrue(main.title().contains("RivaTuner"), main.title());
    }

    @Test
    void downloadedListWinsOnlyWhenNewer(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(KnownCrashes.FILE), """
                {"version": 99999, "crashes": [{"id": "new", "title": "New", "fix": "x", "any": ["zzz"]}]}
                """);
        assertEquals("new", KnownCrashes.use(dir).match("zzz").id());
        Files.writeString(dir.resolve(KnownCrashes.FILE), """
                {"version": 0, "crashes": [{"id": "old", "title": "Old", "fix": "x", "any": ["zzz"]}]}
                """);
        assertNull(KnownCrashes.use(dir).match("zzz"));
        KnownCrashes.use(null);
    }

    @Test
    void updatesAreOffUnlessThePlayerTurnsThemOn(@TempDir Path dir) throws Exception {
        assertTrue(!KnownCrashes.updatesOn(dir));
        Files.writeString(dir.resolve("settings.properties"), "launch_popup=true\n");
        assertTrue(!KnownCrashes.updatesOn(dir));
        Files.writeString(dir.resolve("settings.properties"), "crash_fix_updates=true\n");
        assertTrue(KnownCrashes.updatesOn(dir));
    }

    @Test
    void signatureKeepsTheErrorAndModFrames() {
        String sig = KnownCrashes.signature("""
                ---- Minecraft Crash Report ----
                Description: Ticking entity

                java.lang.NullPointerException: x is null
                	at java.base/java.util.Objects.requireNonNull(Objects.java:1)
                	at TRANSFORMER/alexsmobs@1.22.9/com.github.alexthe666.alexsmobs.Foo.tick(Foo.java:10)
                	at net.minecraft.world.entity.Entity.tick(Entity.java:1)
                """);
        assertTrue(sig.startsWith("Description: Ticking entity\njava.lang.NullPointerException: x is null"), sig);
        assertTrue(sig.contains("alexsmobs"), sig);
        assertTrue(!sig.contains("requireNonNull") && !sig.contains("Entity.tick"), sig);
    }
}
