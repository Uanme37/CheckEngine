package com.guiltypotato.checkengine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guiltypotato.checkengine.core.scan.ExportCheck;
import com.guiltypotato.checkengine.core.scan.Finding;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A pack maker's export, checked before upload. */
class ExportCheckTest {
    @TempDir
    Path dir;

    private Path zip(String name, Map<String, String> files) throws Exception {
        Path z = dir.resolve(name);
        try (OutputStream out = Files.newOutputStream(z); ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> e : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return z;
    }

    @Test
    void personalFilesJarsAndUserNamesAreFlagged() throws Exception {
        Path z = zip("My Pack 1.0.zip", Map.of(
                "manifest.json", "{}",
                "overrides/config/create-server.toml", "stress = 1.0\n",
                "overrides/options.txt", "key_key.jump:key.keyboard.space\n",
                "overrides/servers.dat", "x",
                "overrides/saves/My World/level.dat", "x",
                "overrides/mods/extra-1.0.jar", "x",
                "overrides/config/somemod.json", "{\"folder\": \"C:\\\\Users\\\\Alex\\\\Desktop\\\\stuff\"}"));
        List<Finding> f = ExportCheck.check(z);
        String titles = f.stream().map(Finding::title).toList().toString();
        assertTrue(titles.contains("your game settings"), titles);
        assertTrue(titles.contains("your server list"), titles);
        assertTrue(titles.contains("your worlds"), titles);
        assertTrue(titles.contains("1 mod jar is packed inside"), titles);
        assertTrue(titles.contains("Your Windows user name is in 1 config file"), titles);
        assertEquals(5, f.size(), titles);
    }

    @Test
    void checkEnginesKeptJarsAreFlaggedNotCountedAsBundledMods() throws Exception {
        Path z = zip("My Pack 1.1.zip", Map.of("manifest.json", "{}",
                "overrides/checkengine/lastgood/mods/create-6.0.3.jar", "x",
                "overrides/checkengine/last-good-launch.properties", "x"));
        List<Finding> f = ExportCheck.check(z);
        assertEquals(1, f.size(), f.toString());
        assertEquals("The export has Check Engine's files about your game", f.get(0).title());
        assertTrue(f.get(0).detail().contains("Leave the checkengine folder out"), f.get(0).detail());
    }

    @Test
    void aCleanExportIsClean() throws Exception {
        Path z = zip("Clean.mrpack", Map.of("modrinth.index.json", "{}",
                "overrides/config/create-server.toml", "stress = 1.0\n",
                "overrides/config/checkengine-pack.properties", "pack_name=Clean\n"));
        assertTrue(ExportCheck.check(z).isEmpty());
        assertTrue(ExportCheck.toText(z, List.of()).contains("Looks clean"));
    }
}
