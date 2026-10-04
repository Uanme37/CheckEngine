package com.guiltypotato.packdoctor.core.scan;

import com.guiltypotato.packdoctor.core.json.Json;
import com.guiltypotato.packdoctor.core.model.Side;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Works out what a user pointed us at: a pack/instance folder or a mods folder,
 * and reads anything a launcher left behind (CurseForge's minecraftinstance.json).
 *
 * @param modsFolder the folder with the jars
 * @param options    what we could learn about the pack
 * @param source     where the extra info came from, for the report header (e.g. "CurseForge instance"), or null
 */
public record PackFolder(Path modsFolder, ScanOptions options, String source) {

    public static PackFolder locate(Path path, Side side) throws IOException {
        Path root = path.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) throw new IOException("Not a folder: " + root);
        Path mods = root;
        if (Files.isDirectory(root.resolve("mods"))) {
            mods = root.resolve("mods");
        } else if (root.getFileName() != null && root.getFileName().toString().equalsIgnoreCase("mods")
                && root.getParent() != null) {
            root = root.getParent();
        }
        Path cf = root.resolve("minecraftinstance.json");
        if (Files.isRegularFile(cf)) {
            try {
                return new PackFolder(mods, curseForge(Files.readString(cf, StandardCharsets.UTF_8), side),
                        "CurseForge instance");
            } catch (RuntimeException e) {
                // unreadable instance file: fall back to just the jars
            }
        }
        return new PackFolder(mods, ScanOptions.of(side), null);
    }

    /** Reads Minecraft/NeoForge versions and CurseForge's client-only tags from minecraftinstance.json. */
    @SuppressWarnings("unchecked")
    static ScanOptions curseForge(String json, Side side) {
        Map<String, Object> obj = Json.parseObject(json);
        String mc = obj.get("gameVersion") instanceof String s ? s : null;
        String neo = null;
        if (obj.get("baseModLoader") instanceof Map<?, ?> loader && loader.get("name") instanceof String name
                && name.startsWith("neoforge-")) {
            neo = name.substring("neoforge-".length());
        }
        Set<String> clientOnly = new HashSet<>();
        if (obj.get("installedAddons") instanceof List<?> addons) {
            for (Object a : addons) {
                if (!(a instanceof Map<?, ?> addon) || !(addon.get("installedFile") instanceof Map<?, ?> file)) continue;
                if (!(file.get("fileName") instanceof String fileName)) continue;
                if (file.get("gameVersion") instanceof List<?> tags
                        && tags.contains("Client") && !tags.contains("Server")) {
                    clientOnly.add(fileName.toLowerCase(Locale.ROOT));
                }
            }
        }
        return new ScanOptions(side, mc, neo, clientOnly);
    }
}
