package checkengine.core;

import static checkengine.core.TestJars.neoMod;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import checkengine.core.model.Side;
import checkengine.core.scan.HelpBundle;
import checkengine.core.scan.PackScanner;
import checkengine.core.scan.ScanOptions;
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

    /** The name from "Setting user:" is blanked on every line it shows up on, not only that one. */
    @Test
    void playerNameBlankedEverywhere() {
        String text = new HelpBundle.Redactor("C:\\Users\\Alex").apply(String.join("\n",
                "[main/INFO]: Setting user: Steve99",
                "[Render thread/INFO]: [System] [CHAT] Steve99 joined the game",
                "[Render thread/INFO]: [System] [CHAT] <Steve99> hi",
                "[Server thread/INFO]: Steve99 lost connection: Disconnected",
                "[Server thread/INFO]: Steve99_alt joined the game"));
        assertFalse(text.contains("Steve99 "), text);
        assertFalse(text.contains("<Steve99>"), text);
        assertTrue(text.contains("<player> joined the game"), text);
        assertTrue(text.contains("<<player>> hi"), text);
        assertTrue(text.contains("Steve99_alt joined"), "only the whole name: " + text);
    }

    /** Names on a server log come from its "UUID of player" and "logged in" lines. */
    @Test
    void serverPlayerNamesBlanked() {
        String text = new HelpBundle.Redactor(null).apply(String.join("\n",
                "[User Authenticator #1/INFO]: UUID of player Bob_77 is 0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0",
                "[Server thread/INFO]: Bob_77[/203.0.113.7:51234] logged in with entity id 12",
                "[Server thread/INFO]: Bob_77 has made the advancement [Stone Age]"));
        assertFalse(text.contains("Bob_77"), text);
        assertFalse(text.contains("203.0.113.7"), text);
    }

    @Test
    void serverHostsAndIpv6Blanked() {
        String text = new HelpBundle.Redactor(null).apply(String.join("\n",
                "[Render thread/INFO]: Connecting to play.myserver.net, 25565",
                "[Netty Client IO #1/INFO]: play.myserver.net/203.0.113.7:25565",
                "Server address: mc.example.org:25570",
                "Check Engine: why you couldn't join join.example.com",
                "--quickPlayMultiplayer quick.example.net:25565",
                "Connecting to homebox, 25565",
                "peer 2001:db8:85a3::8a2e:370:7334 and [fe80::1%eth0]:25565 and 2001:0db8:0000:0000:0000:ff00:0042:8329"));
        for (String leak : new String[] {"myserver", "example", "homebox", "2001:db8", "fe80", "0042:8329"}) {
            assertFalse(text.contains(leak), leak + " left in:\n" + text);
        }
        assertTrue(text.contains("Connecting to <server>, 25565"), text);
    }

    @Test
    void timesAndCodeStayNextToIpv6() {
        String line = "[12:34:56] [main/INFO]: Foo::add at net.minecraft.Bar::lambda$0 sha1 ab:cd:ef deadbeef::";
        assertEquals(line, new HelpBundle.Redactor(null).apply(line));
    }

    /** A mod's own libraries in the loader's mod tree, indented with spaces and bars. Made-up mods and address. */
    @Test
    void modTreeVersionsStayAndAnAddressInTheSameTextGoes() {
        String tree = String.join("\n",
                "\t- demo_ui 0.12.15.4",
                "\t   |-- demo_lib 0.1.5.1",
                "\t   |-- demo_lib_gson 0.1.3.1",
                "\t   |    \\-- demo_deep 2.0.0.7",
                "\t   \\-- demo_lib_netty 10.20.30.40");
        String text = new HelpBundle.Redactor(null).apply(String.join("\n", tree,
                "[12:01:23] [Render thread/INFO]: Connecting to 203.0.113.9, 25565",
                "   |-- 203.0.113.10 is not a mod tree line without the tab",
                "\t   |-- joined 203.0.113.11 198.51.100.4"));
        assertTrue(text.startsWith(tree + "\n"), text);
        assertTrue(text.contains("Connecting to <ip>, 25565"), text);
        // only the one number right after a mod id on a tree line is kept
        for (String leak : new String[] {"203.0.113.9", "203.0.113.10", "198.51.100.4"}) {
            assertFalse(text.contains(leak), leak + " left in:\n" + text);
        }
    }

    /** Only the zone goes, and only on the map mod's own date line. Made-up lines. */
    @Test
    void timeZoneInTheMapModDateLineIsBlanked() {
        String before = "[12:00:01] [Render thread/INFO]: Loading Demo Minimap - Stage 2/2";
        String after = "[12:00:03] [Render thread/INFO]: Build date: Thu Nov 26 22:14:01 CET 2026";
        String text = new HelpBundle.Redactor(null).apply(String.join("\r\n", before,
                "[12:00:02] [Worker-3/WARN]: io exception while checking patreon: Online mod data expired! Date: Thu Nov 26 22:14:01 CET 2026",
                "[12:00:02] [Render thread/ERROR]: io exception while checking versions: Online mod data expired! Date: Sat Dec 5 09:03:56 GMT+05:30 2026",
                after));
        assertEquals(String.join("\r\n", before,
                "[12:00:02] [Worker-3/WARN]: io exception while checking patreon: Online mod data expired! Date: Thu Nov 26 22:14:01 <hidden> 2026",
                "[12:00:02] [Render thread/ERROR]: io exception while checking versions: Online mod data expired! Date: Sat Dec 5 09:03:56 <hidden> 2026",
                after), text);
    }

    /** Only the device name goes, and only on the line where the game names it. Made-up device. */
    @Test
    void audioDeviceNameIsBlanked() {
        String before = "[12:00:01] [Render thread/WARN]: Missing sound for event: demo:forge_crackle";
        String after = "[12:00:03] [Render thread/INFO]: Sound engine started on device Demo Speakers";
        String text = new HelpBundle.Redactor(null).apply(String.join("\r\n", before,
                "[12:00:02] [Render thread/INFO]: OpenAL initialized on device OpenAL Soft on Headset (2- Demo Wireless 7)",
                after));
        assertEquals(String.join("\r\n", before,
                "[12:00:02] [Render thread/INFO]: OpenAL initialized on device <hidden>",
                after), text);
    }

    /** Four-part mod versions aren't IPs, real addresses still are. */
    @Test
    void modVersionsStayAddressesGo() {
        HelpBundle.Redactor r = new HelpBundle.Redactor(null);
        assertEquals("jei.jar  [jei 19.21.0.247]", r.apply("jei.jar  [jei 19.21.0.247]"));
        String table = "\t\tjei-1.21.1-neoforge-19.21.0.247.jar |Just Enough Items |jei |19.21.0.247 |Manifest: NOSIGNATURE";
        assertEquals(table, r.apply(table));
        assertEquals("Connecting to <ip>, 25565", r.apply("Connecting to 203.0.113.7, 25565"));
        // Lines from real 1.20.1 / 1.21.1 / 26.1 logs
        for (String line : new String[] {
                "Mod jei matches versions: 15.20.0.106 in [15.0.0.0,)",
                "[mod/INFO]: Found status: BETA Current: 1.0.0.57 Target: 1.0.0.60",
                "libraries/net/neoforged/neoforge/26.1.2.121/neoforge-26.1.2.121-universal.jar"}) {
            assertEquals(line, r.apply(line));
        }
    }

    /** Version lines from real help files (Check Engine history, loader mod lists, crash reports, check-report). */
    @Test
    void versionListsStay() {
        HelpBundle.Redactor r = new HelpBundle.Redactor(null);
        for (String line : new String[] {
                "mod.jei=15.20.0.134|jei-1.20.1-forge-15.20.0.134.jar|Just Enough Items",
                "loader=26.1.2.121",
                "\t\tFTB Chunks 2601.1.2.4 (ftbchunks)",
                "\t\tAgritech: Evolved 1.2.3.4+neoforge-mc26.1.2 (agritechevolved)",
                "\t- insanelib 1.23.4.6",
                "\t\t|-- architectury 9.2.14.1",
                "\t\\-- yet_another_config_lib_v3 3.5.0.1",
                "Checked as: client (player's game), Minecraft 26.1.2, NeoForge 26.1.2.121",
                "\tNeoForge: 26.1.2.121",
                "Forge 47.4.0.10 for Minecraft 1.20.1",
                "Java: 21.0.8.1, OS: Windows 11"}) {
            assertEquals(line, r.apply(line));
        }
    }

    /** The same spots with a real address in them, and addresses anywhere else, still go. */
    @Test
    void addressesInVersionSpotsStillGo() {
        HelpBundle.Redactor r = new HelpBundle.Redactor(null);
        for (String line : new String[] {
                "server-ip=203.0.113.7",
                "ip=203.0.113.7",
                "address=203.0.113.7",
                "mod.jei=x 203.0.113.7",
                "[Server thread/INFO]: Bob[/203.0.113.7:51234] logged in",
                "Connecting to 203.0.113.7, 25565",
                "Pinging 203.0.113.7 (server)",
                "[12:00:00] [main/INFO]: from 203.0.113.7 (bob)",
                "lan 203.0.113.7",
                "host 203.0.113.7:25565",
                "joined from 203.0.113.7.",
                "\t\tsomething 203.0.113.7 happened",
                "peer 2001:db8::7"}) {
            String out = r.apply(line);
            assertFalse(out.contains("203.0.113.7") || out.contains("2001:db8"), line + " -> " + out);
        }
    }

    /** Every file in the zip loses the name, also when only the game knows it (no "Setting user" line). */
    @Test
    void zipBlanksTheNameInEveryFile() throws IOException {
        Path mods = Files.createDirectories(pack.resolve("mods"));
        neoMod("jei", "19.21.0.247", null).writeTo(mods, "jei-19.21.0.247.jar");
        Files.createDirectories(pack.resolve("logs"));
        Files.writeString(pack.resolve("logs/latest.log"), """
                [main/INFO]: Setting user: Steve99
                [Render thread/INFO]: Connecting to play.myserver.net, 25565
                [Render thread/INFO]: [System] [CHAT] <Alex42> hi Steve99
                """);
        Files.createDirectories(pack.resolve("crash-reports"));
        Files.writeString(pack.resolve("crash-reports/crash-2026-11-11_10.00.00-client.txt"),
                "---- Minecraft Crash Report ----\nDescription: Steve99 crashed\n");
        Files.createDirectories(pack.resolve("checkengine"));
        Files.writeString(pack.resolve("checkengine/join-report.txt"),
                "Check Engine: why you couldn't join play.myserver.net\nSteve99 needs jei\n");

        Map<String, String> files = unzip(HelpBundle.create(pack, PackScanner.scan(mods, new ScanOptions(Side.CLIENT,
                "1.21.1", "21.1.251", Set.of())), "Alex42"));
        for (Map.Entry<String, String> f : files.entrySet()) {
            for (String leak : new String[] {"Steve99", "Alex42", "myserver"}) {
                assertFalse(f.getValue().contains(leak), leak + " left in " + f.getKey() + ":\n" + f.getValue());
            }
        }
        assertTrue(files.get("mods.txt").contains("[jei 19.21.0.247]"), files.get("mods.txt"));
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
