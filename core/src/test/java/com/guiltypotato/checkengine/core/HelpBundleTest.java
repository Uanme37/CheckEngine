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

    /** Made-up values, in the forms a JVM crash file and logs write them. */
    @Test
    void loginAndPcDetailsAreBlanked() {
        String token = "eyJhbGciOiJIUzI1NiJ9.FAKEFAKEFAKEFAKEFAKE.FAKEFAKEFAKE";
        String text = new HelpBundle.Redactor("C:\\Users\\Alex").apply(String.join("\n",
                "java_command: Launcher --username Steve99 --accessToken " + token + " --uuid 0f1e2d3c4b5a69788796a5b4c3d2e1f0 --xuid 2535 --clientId abc --userType msa",
                "sun.java.command=[--accessToken, plainfaketoken123, --version, 1.21.1]",
                "args \"--accessToken\", \"quotedfaketoken456\"",
                "(Session ID is token:abcdefghijklmnopqrstuvwxyz012345:0f1e2d3c4b5a69788796a5b4c3d2e1f0)",
                "USERNAME=Alex", "COMPUTERNAME=DEMO-PC", "  USER = Alex", "HOSTNAME=demo-host",
                "file:/C:/Users/Bob/a.jar", "/home/Carol/.minecraft/mods", "/Users/Dana/Library", "C:\\\\Users\\\\Erin\\\\x",
                "UUID of player Steve99 is 0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0"));
        for (String leak : new String[] {token, "0f1e2d3c", "2535", "plainfaketoken123", "quotedfaketoken456", "abcdefghijkl",
                "Alex", "DEMO-PC", "demo-host", "Bob", "Carol", "Dana", "Erin", "Steve99"}) {
            assertFalse(text.contains(leak), leak + " left in:\n" + text);
        }
        assertTrue(text.contains("--version, 1.21.1"), text);
        assertTrue(text.contains("--userType msa"), text);
    }

    @Test
    void resourcePathsAndVersionsStay() {
        String line = "assets/home/button.png mod/users/list.json mod-1.20.1.2.jar";
        assertEquals(line, new HelpBundle.Redactor("C:\\Users\\Alex").apply(line));
    }

    @Test
    void bundlesReportsAndLogsWithPersonalBitsBlanked() throws IOException {
        Path mods = Files.createDirectories(pack.resolve("mods"));
        neoMod("create", "6.0.4", null).writeTo(mods, "create-1.21.1-6.0.4.jar");
        String home = System.getProperty("user.home");
        Files.createDirectories(pack.resolve("logs"));
        Files.writeString(pack.resolve("logs/latest.log"), """
                [main/INFO]: Setting user: Steve
                [main/INFO]: Loading %s/curseforge/mods/x.jar
                [main/INFO]: Connecting to 203.0.113.7, 25565 (local 127.0.0.1)
                [main/INFO]: Found mod file somemod-1.20.1.2.jar version 2.0.1.4
                """.formatted(home.replace('\\', '/')));

        Path zip = HelpBundle.create(pack, PackScanner.scan(mods, new ScanOptions(Side.CLIENT, "1.21.1", "21.1.251",
                Set.of())));
        Map<String, String> files = unzip(zip);
        assertTrue(files.containsKey("README.txt"));
        assertTrue(files.get("mods.txt").contains("create-1.21.1-6.0.4.jar  [create 6.0.4]"), files.get("mods.txt"));
        String log = files.get("logs/latest.log");
        assertTrue(log.contains("Setting user: <player>"), log);
        assertTrue(log.contains("Loading <home>/curseforge/mods/x.jar"), log);
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
