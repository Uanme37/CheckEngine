package com.guiltypotato.checkengine.core;

import static com.guiltypotato.checkengine.core.TestJars.jar;
import static com.guiltypotato.checkengine.core.TestJars.neoMod;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.guiltypotato.checkengine.core.scan.Finding;
import com.guiltypotato.checkengine.core.scan.ServerCompare;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerCompareTest {
    @TempDir
    Path pack;
    @TempDir
    Path server;

    private Path packMods() throws IOException {
        Files.writeString(pack.resolve("minecraftinstance.json"),
                "{\"gameVersion\": \"1.21.1\", \"baseModLoader\": {\"name\": \"neoforge-21.1.255\"}}");
        return Files.createDirectories(pack.resolve("mods"));
    }

    private Path serverMods(String neoforge) throws IOException {
        Files.createDirectories(server.resolve("libraries/net/neoforged/neoforge/" + neoforge));
        return Files.createDirectories(server.resolve("mods"));
    }

    private static List<String> codes(List<Finding> f) {
        return f.stream().map(x -> x.code() + ": " + x.title()).toList();
    }

    @Test
    void matchingPackAndServer() throws IOException {
        neoMod("create", "6.0.10", null).writeTo(packMods(), "create.jar");
        neoMod("sodium", "0.8.13", null).writeTo(pack.resolve("mods"), "sodium.jar"); // client-only: fine
        neoMod("create", "6.0.10", null).writeTo(serverMods("21.1.255"), "create.jar");
        assertEquals(List.of(), ServerCompare.compare(pack, server));
    }

    @Test
    void missingAndMismatchedMods() throws IOException {
        Path pm = packMods();
        Path sm = serverMods("21.1.255");
        neoMod("create", "6.0.10", null).writeTo(pm, "create.jar");
        neoMod("sophisticatedcore", "1.5.1", null).writeTo(pm, "sc-1.5.1.jar");
        neoMod("sophisticatedcore", "1.5.2", null).writeTo(sm, "sc-1.5.2.jar");
        neoMod("petrolpark", "1.5.10", null).writeTo(sm, "petrolpark.jar");
        // A server-side utility that says players don't need it.
        jar().with("META-INF/neoforge.mods.toml", TestJars.modsToml("serverutil", "1.0", null)
                .replace("[[mods]]", "[[mods]]\ndisplayTest = \"IGNORE_ALL_VERSION\"")).writeTo(sm, "serverutil.jar");
        assertEquals(List.of(
                "missing-on-player: Players don't have: Petrolpark",
                "missing-on-server: Server doesn't have: Create",
                "version-mismatch: Different versions: Sophisticatedcore"), codes(ServerCompare.compare(pack, server)));
    }

    @Test
    void differentMinecraftVersion() throws IOException {
        packMods();
        serverMods("21.4.100");
        assertEquals(List.of("platform-mismatch: Different Minecraft versions",
                "platform-mismatch: Different NeoForge versions"), codes(ServerCompare.compare(pack, server)));
    }

    @Test
    void readsAServerPackZip(@TempDir Path tmp) throws IOException {
        neoMod("create", "6.0.10", null).writeTo(packMods(), "create.jar");
        Path sm = serverMods("21.1.255");
        neoMod("create", "6.0.10", null).writeTo(sm, "create.jar");
        neoMod("extra", "1.0", null).writeTo(sm, "extra.jar");
        Path zip = tmp.resolve("server.zip");
        try (OutputStream os = Files.newOutputStream(zip); ZipOutputStream z = new ZipOutputStream(os);
             Stream<Path> files = Files.walk(server)) {
            for (Path p : files.toList()) {
                String name = "MyPack-Server/" + server.relativize(p).toString().replace('\\', '/');
                z.putNextEntry(new ZipEntry(Files.isDirectory(p) ? name + "/" : name));
                if (Files.isRegularFile(p)) Files.copy(p, z);
                z.closeEntry();
            }
        }
        assertEquals(List.of("missing-on-player: Players don't have: Extra"), codes(ServerCompare.compare(pack, zip)));
    }
}
