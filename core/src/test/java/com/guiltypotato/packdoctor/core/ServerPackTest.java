package com.guiltypotato.packdoctor.core;

import static com.guiltypotato.packdoctor.core.TestJars.dep;
import static com.guiltypotato.packdoctor.core.TestJars.neoMod;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guiltypotato.packdoctor.core.scan.ServerPack;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerPackTest {
    @TempDir
    Path pack;
    @TempDir
    Path outRoot;

    private void makePack() throws IOException {
        Path mods = Files.createDirectory(pack.resolve("mods"));
        neoMod("create", "6.0.10", null).writeTo(mods, "create.jar");
        neoMod("sodium", "0.6", null).writeTo(mods, "sodium.jar"); // known client-only
        neoMod("myhud", "1.0", dep("myhud", "neoforge", "required", "*", "CLIENT")).writeTo(mods, "myhud.jar");
        // Tagged Client on CurseForge, but a server mod needs it.
        neoMod("sharedlib", "1.0", null).writeTo(mods, "SharedLib.jar");
        neoMod("servermod", "1.0", dep("servermod", "sharedlib", "required", "*", "BOTH")).writeTo(mods, "servermod.jar");
        Files.writeString(pack.resolve("minecraftinstance.json"), """
                {"gameVersion": "1.21.1", "baseModLoader": {"name": "neoforge-21.1.251"},
                 "installedAddons": [
                   {"installedFile": {"fileName": "SharedLib.jar", "gameVersion": ["Client", "1.21.1"]}}]}
                """);
        Files.createDirectories(pack.resolve("config")).resolve("create-common.toml").toFile().createNewFile();
        Files.createDirectories(pack.resolve("kubejs/server_scripts")).resolve("recipes.js").toFile().createNewFile();
        Files.createDirectories(pack.resolve("saves/World")).resolve("level.dat").toFile().createNewFile();
        Files.writeString(pack.resolve("options.txt"), "fov:1.0");
    }

    private static Set<String> files(Path dir) throws IOException {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile).map(p -> dir.relativize(p).toString().replace('\\', '/'))
                    .collect(Collectors.toSet());
        }
    }

    @Test
    void removesClientOnlyButKeepsWhatServerModsNeed() throws IOException {
        makePack();
        ServerPack.Plan plan = ServerPack.plan(pack);
        assertEquals(Set.of("sodium.jar", "myhud.jar"), plan.removed().keySet());
        assertEquals("Servermod", plan.keptAnyway().get("SharedLib.jar"));

        Path out = outRoot.resolve("server");
        String readme = ServerPack.write(plan, out);
        assertEquals(Set.of("mods/create.jar", "mods/SharedLib.jar", "mods/servermod.jar",
                "config/create-common.toml", "kubejs/server_scripts/recipes.js", "SERVER-PACK-README.txt"), files(out));
        assertTrue(readme.contains("NeoForge 21.1.251 for Minecraft 1.21.1"), readme);
        assertTrue(readme.contains("sodium.jar"));
    }

    @Test
    void neverWritesIntoAFolderThatHasFiles() throws IOException {
        makePack();
        Path out = Files.createDirectories(outRoot.resolve("server"));
        Files.writeString(out.resolve("keep-me.txt"), "mine");
        assertThrows(IOException.class, () -> ServerPack.write(ServerPack.plan(pack), out));
        assertEquals("mine", Files.readString(out.resolve("keep-me.txt")));
    }

    @Test
    void zipHasTheSameFiles() throws IOException {
        makePack();
        Path out = outRoot.resolve("server");
        ServerPack.write(ServerPack.plan(pack), out);
        Path zip = outRoot.resolve("server.zip");
        ServerPack.zip(out, zip);
        assertTrue(Files.size(zip) > 0);
        assertFalse(files(out).isEmpty());
    }
}
