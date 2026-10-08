package com.guiltypotato.checkengine.core.scan;

import com.guiltypotato.checkengine.core.model.ModInfo;
import com.guiltypotato.checkengine.core.model.ModJar;
import com.guiltypotato.checkengine.core.version.ModVersion;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.TreeMap;

/**
 * A fingerprint of a pack that worked: every mod's id, version and file, plus the Minecraft and loader versions.
 * The mod saves one after each successful launch (checkengine/last-good-launch.properties); when the pack later
 * breaks, comparing against it shows what changed ("it worked yesterday").
 *
 * @param time      when it was taken, e.g. "2026-10-08T12:30"
 * @param minecraft Minecraft version, or null
 * @param loader    NeoForge or Forge version, or null
 * @param mods      mod id -> what was installed
 */
public record PackSnapshot(String time, String minecraft, String loader, Map<String, Entry> mods) {
    public static final String FILE = "last-good-launch.properties";

    public record Entry(String name, String version, String file) {}

    /** The pack as it is on disk now (top-level mods only; when a mod is there twice, the newest counts). */
    public static PackSnapshot of(List<ModJar> jars, ScanOptions options) {
        Map<String, Entry> mods = new TreeMap<>();
        for (ModJar jar : jars) {
            for (ModInfo mod : jar.mods()) {
                Entry e = new Entry(mod.name(), mod.version(), jar.fileName());
                mods.merge(mod.modId(), e, (a, b) ->
                        ModVersion.parse(a.version()).compareTo(ModVersion.parse(b.version())) >= 0 ? a : b);
            }
        }
        return new PackSnapshot(LocalDateTime.now().withNano(0).withSecond(0).toString(), options.minecraftVersion(),
                options.neoforgeVersion(), mods);
    }

    public void save(Path checkEngineFolder) throws IOException {
        Properties p = new Properties();
        p.setProperty("time", time);
        if (minecraft != null) p.setProperty("minecraft", minecraft);
        if (loader != null) p.setProperty("loader", loader);
        mods.forEach((id, e) -> p.setProperty("mod." + id, e.version() + "|" + e.file() + "|" + e.name()));
        Files.createDirectories(checkEngineFolder);
        try (Writer out = Files.newBufferedWriter(checkEngineFolder.resolve(FILE), StandardCharsets.UTF_8)) {
            p.store(out, "Check Engine: the mods of the last launch that worked");
        }
    }

    /** The last good launch saved in this folder, or null if there isn't one (or it can't be read). */
    public static PackSnapshot load(Path checkEngineFolder) {
        Path file = checkEngineFolder.resolve(FILE);
        if (!Files.isRegularFile(file)) return null;
        Properties p = new Properties();
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(in);
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
        Map<String, Entry> mods = new TreeMap<>();
        for (String key : p.stringPropertyNames()) {
            if (!key.startsWith("mod.")) continue;
            String[] parts = p.getProperty(key).split("\\|", 3);
            if (parts.length < 3) continue;
            mods.put(key.substring(4), new Entry(parts[2], parts[0], parts[1]));
        }
        return new PackSnapshot(p.getProperty("time", "?"), p.getProperty("minecraft"), p.getProperty("loader"), mods);
    }

    /** What's different in {@code now} compared to this (older) snapshot. */
    public Changes changesTo(PackSnapshot now) {
        List<Changes.Change> list = new ArrayList<>();
        if (minecraft != null && now.minecraft != null && !minecraft.equals(now.minecraft)) {
            list.add(new Changes.Change(Changes.Type.UPDATED, "minecraft", "Minecraft", minecraft, now.minecraft, null));
        }
        if (loader != null && now.loader != null && !loader.equals(now.loader)) {
            list.add(new Changes.Change(Changes.Type.UPDATED, "loader", "the mod loader", loader, now.loader, null));
        }
        now.mods.forEach((id, e) -> {
            Entry old = mods.get(id);
            if (old == null) {
                list.add(new Changes.Change(Changes.Type.ADDED, id, e.name(), null, e.version(), e.file()));
            } else if (!Objects.equals(old.version(), e.version())) {
                list.add(new Changes.Change(Changes.Type.UPDATED, id, e.name(), old.version(), e.version(), e.file()));
            }
        });
        mods.forEach((id, old) -> {
            if (!now.mods.containsKey(id)) {
                list.add(new Changes.Change(Changes.Type.REMOVED, id, old.name(), old.version(), null, old.file()));
            }
        });
        return new Changes(time, List.copyOf(list));
    }
}
