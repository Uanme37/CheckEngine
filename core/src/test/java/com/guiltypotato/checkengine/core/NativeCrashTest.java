package com.guiltypotato.checkengine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guiltypotato.checkengine.core.crash.CrashDoctor;
import com.guiltypotato.checkengine.core.crash.CrashTranslator;
import com.guiltypotato.checkengine.core.scan.Finding;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Silent crashes: no crash report, just Java's hs_err_pid file. */
class NativeCrashTest {
    private static String hsErr(String frame, String extra) {
        return """
                #
                # A fatal error has been detected by the Java Runtime Environment:
                #
                #  EXCEPTION_ACCESS_VIOLATION (0xc0000005) at pc=0x00007ffb1a2b3c4d, pid=1234, tid=5678
                #
                # JRE version: OpenJDK Runtime Environment Microsoft-8035246 (21.0.7+6) (build 21.0.7+6-LTS)
                # Problematic frame:
                %s
                #
                """.formatted(frame) + extra;
    }

    private static Finding first(String text) {
        return CrashTranslator.translate(text).findings().get(0);
    }

    @Test
    void graphicsDriversAreNamed() {
        assertEquals("Graphics driver crash (AMD)", first(hsErr("# C  [atio6axx.dll+0x1c2d3e]", "")).title());
        assertEquals("Graphics driver crash (NVIDIA)", first(hsErr("# C  [nvoglv64.dll+0x9a1b2c]", "")).title());
        assertEquals("Graphics driver crash (Intel)", first(hsErr("# C  [ig9icd64.dll+0x16a27]", "")).title());
        assertTrue(first(hsErr("# C  [atio6axx.dll+0x1]", "")).fix().contains("amd.com"));
    }

    /** It jumped to an empty address: the stack below names the driver that called it. */
    @Test
    void anEmptyAddressFallsBackToTheStack() {
        String text = hsErr("# C  0x0000000000000000", """
                Native frames: (J=compiled Java code, j=interpreted, Vv=VM code, C=native code)
                C  0x0000000000000000
                C  [nvoglv64.dll+0x123456]
                """);
        assertEquals("Graphics driver crash (NVIDIA)", first(text).title());
    }

    @Test
    void javaItselfNamesTheModThatWasRunning() {
        String text = hsErr("# V  [jvm.dll+0x3b1f2a]", """
                Java frames: (J=compiled Java code, j=interpreted, Vv=VM code)
                J 77366 c1 top.leonx.irisflw.vertex.IrisBlockVertexListUnsafe$Shaded.getNX(I)F (9 bytes)
                """);
        Finding f = first(text);
        assertEquals("Java itself crashed", f.title());
        assertTrue(f.detail().contains("top.leonx.irisflw"), f.detail());
    }

    @Test
    void outOfMemoryIsThePc() {
        String text = """
                #
                # There is insufficient memory for the Java Runtime Environment to continue.
                # Native memory allocation (malloc) failed to allocate 1048576 bytes. Error detail: AllocateHeap
                # A fatal error has been detected by the Java Runtime Environment
                """;
        assertEquals("The PC ran out of memory", first(text).title());
    }

    /** Crash Doctor and the help file find hs_err_pid files next to the crash reports. */
    @Test
    void hsErrFilesAreCrashFiles(@TempDir Path game) throws Exception {
        Files.createDirectories(game.resolve("crash-reports"));
        Files.writeString(game.resolve("crash-reports").resolve("crash-1.txt"), "Description: x");
        Files.writeString(game.resolve("hs_err_pid4242.log"), hsErr("# C  [atio6axx.dll+0x1]", ""));
        Files.writeString(game.resolve("notes.log"), "not a crash");
        assertEquals(2, CrashDoctor.crashFiles(game).size());
    }
}
