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

    /** From a test in 3 packs: RivaTuner running is in every crash report's module list, even an F3+C crash. */
    @Test
    void anOverlayThatIsOnlyLoadedIsNotBlamed() {
        String report = """
                ---- Minecraft Crash Report ----
                Description: Manually triggered debug crash

                java.lang.Throwable: Manually triggered debug crash
                	at TRANSFORMER/minecraft@1.21.1/net.minecraft.client.KeyboardHandler.tick(KeyboardHandler.java:509)

                -- Manual crash details --
                Details:
                	Modules:\s
                		ADVAPI32.dll:Advanced Windows 32 Base API:10.0.26100.3624:Microsoft Corporation
                		RTSSHooks64.dll
                	Something else: here

                -- System Details --
                Details:
                	Loaded modules:
                		RPCRT4.dll:Remote Procedure Call Runtime:10.0.26100.1:Microsoft Corporation
                		RTSSHooks64.dll
                		DiscordHook64.dll
                """;
        Finding main = CrashTranslator.translate(report).findings().get(0);
        assertTrue(!main.code().equals(KnownCrashes.CODE), main.title());
        assertNull(KnownCrashes.current().match("""
                # Problematic frame:
                # C  [atio6axx.dll+0x192b60]

                Dynamic libraries:
                0x00007ffb0f650000 - 0x00007ffb0f700000 	C:\\RTSS\\RTSSHooks64.dll
                """));
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
