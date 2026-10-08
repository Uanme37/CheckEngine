package com.guiltypotato.checkengine.core;

import static com.guiltypotato.checkengine.core.TestJars.neoMod;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guiltypotato.checkengine.core.model.Side;
import com.guiltypotato.checkengine.core.scan.HelpBundle;
import com.guiltypotato.checkengine.core.scan.PackScanner;
import com.guiltypotato.checkengine.core.scan.ScanOptions;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HelpBundleTest {
    @TempDir
    Path pack;

    @Test
    void bundlesReportsAndLogsWithPersonalBitsBlanked() throws IOException {
        Path mods = Files.createDirectories(pack.resolve("mods"));
        neoMod("create", "6.0.4", null).writeTo(mods, "create-1.21.1-6.0.4.jar");
        String home = System.getProperty("user.home");
        Files.createDirectories(pack.resolve("logs"));
        Files.writeString(pack.resolve("logs/latest.log"), """
                [main/INFO]: Setting user: Uanmee
                [main/INFO]: Loading C:/Users/Gaming/curseforge/mods/x.jar and %s\\mods\\y.jar
                [main/INFO]: Connecting to 203.0.113.7, 25565 (local 127.0.0.1)
                [main/INFO]: Found mod file somemod-1.20.1.2.jar version 2.0.1.4
                """.formatted(home));

        Path zip = HelpBundle.create(pack, PackScanner.scan(mods, new ScanOptions(Side.CLIENT, "1.21.1", "21.1.251",
                Set.of())));
        Map<String, String> files = unzip(zip);
        assertTrue(files.containsKey("README.txt"));
        assertTrue(files.get("mods.txt").contains("create-1.21.1-6.0.4.jar  [create 6.0.4]"), files.get("mods.txt"));
        String log = files.get("logs/latest.log");
        assertTrue(log.contains("Setting user: <player>"), log);
        assertTrue(log.contains("<home>/curseforge") || log.contains("C:/Users/<user>/curseforge"), log);
        assertFalse(log.contains(home), log);
        assertTrue(log.contains("Connecting to <ip>, 25565 (local 127.0.0.1)"), log);
        assertTrue(log.contains("somemod-1.20.1.2.jar version 2.0.1.4"), "versions aren't IPs: " + log);
        assertEquals(zip.getParent(), pack.resolve("checkengine"));
    }

    private static Map<String, String> unzip(Path zip) throws IOException {
        Map<String, String> out = new HashMap<>();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                out.put(e.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return out;
    }
}
