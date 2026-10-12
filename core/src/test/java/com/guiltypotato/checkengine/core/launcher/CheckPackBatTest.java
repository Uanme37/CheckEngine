package com.guiltypotato.checkengine.core.launcher;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CheckPackBatTest {
    @TempDir Path pack;

    @Test
    void bundledBatIsTheRealOne() throws Exception {
        String bat = new String(CheckPackBat.contents(), StandardCharsets.UTF_8);
        assertTrue(bat.contains("mods\\checkengine-*.jar"), "the .bat must find the checker inside the mod jar");
    }

    @Test
    void writesOnceThenOnlyWhenChanged() throws Exception {
        byte[] v1 = "@echo v1\r\n".getBytes(StandardCharsets.US_ASCII);
        assertTrue(CheckPackBat.install(pack, v1));
        assertFalse(CheckPackBat.install(pack, v1));
        byte[] v2 = "@echo v2\r\n".getBytes(StandardCharsets.US_ASCII);
        assertTrue(CheckPackBat.install(pack, v2));
        assertArrayEquals(v2, Files.readAllBytes(pack.resolve(CheckPackBat.FILE_NAME)));
    }
}
