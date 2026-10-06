package com.guiltypotato.checkengine.core.scan;

import com.guiltypotato.checkengine.core.json.Json;
import com.guiltypotato.checkengine.core.model.Dependency;
import com.guiltypotato.checkengine.core.model.ModInfo;
import com.guiltypotato.checkengine.core.model.ModJar;
import com.guiltypotato.checkengine.core.toml.Toml;
import com.guiltypotato.checkengine.core.version.VersionRange;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/** Reads mod metadata out of a jar: neoforge.mods.toml, mods.toml, fabric.mod.json, manifest, jar-in-jar. */
public final class JarReader {
    static final String NEO_TOML = "META-INF/neoforge.mods.toml";
    static final String FORGE_TOML = "META-INF/mods.toml";
    static final String FABRIC_JSON = "fabric.mod.json";
    static final String QUILT_JSON = "quilt.mod.json";
    static final String JARJAR = "META-INF/jarjar/metadata.json";
    static final String MANIFEST = "META-INF/MANIFEST.MF";

    /** How deep to follow jars inside jars inside jars. */
    private static final int MAX_NESTING = 3;

    private JarReader() {}

    /** Random access to a jar's files, whether it's on disk or nested inside another jar. */
    private interface Entries {
        byte[] get(String name) throws IOException;
    }

    /** Loader plug-ins (ModLauncher services): not mods, but they load fine and often matter (e.g. Connector). */
    static final List<String> SERVICES = List.of(
            "META-INF/services/cpw.mods.modlauncher.api.ITransformationService",
            "META-INF/services/net.minecraftforge.forgespi.locating.IModLocator",
            "META-INF/services/net.neoforged.neoforgespi.locating.IModFileCandidateLocator");

    /** Reads a jar that's already in memory (e.g. inside a server pack zip). {@code file} is only for display. */
    public static ModJar read(Path file, byte[] jarBytes) {
        return read(file, jarBytes, false);
    }

    /** @param legacy true for Minecraft 1.20.4 and older (Forge, or NeoForge 1.20.1): prefer META-INF/mods.toml */
    public static ModJar read(Path file, byte[] jarBytes, boolean legacy) {
        try {
            return read(file, inMemory(jarBytes), 0, legacy);
        } catch (IOException | RuntimeException e) {
            return new ModJar(file, ModJar.Kind.BROKEN, List.of(), List.of(), describe(e));
        }
    }

    public static ModJar read(Path file) {
        return read(file, false);
    }

    /** @param legacy true for Minecraft 1.20.4 and older (Forge, or NeoForge 1.20.1): prefer META-INF/mods.toml */
    public static ModJar read(Path file, boolean legacy) {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            Entries entries = name -> {
                ZipEntry e = zip.getEntry(name);
                if (e == null) return null;
                try (InputStream in = zip.getInputStream(e)) {
                    return in.readAllBytes();
                }
            };
            return read(file, entries, 0, legacy);
        } catch (IOException | RuntimeException e) {
            return new ModJar(file, ModJar.Kind.BROKEN, List.of(), List.of(), describe(e));
        }
    }

    private static ModJar read(Path file, Entries entries, int depth, boolean legacy) throws IOException {
        Manifest manifest = manifest(entries.get(MANIFEST));
        String jarVersion = manifest == null ? null : manifest.getMainAttributes().getValue("Implementation-Version");
        String modType = manifest == null ? null : manifest.getMainAttributes().getValue("FMLModType");

        List<ModInfo> nested = depth < MAX_NESTING ? nestedMods(file, entries, depth, legacy) : List.of();
        String moduleName = manifest == null ? null : manifest.getMainAttributes().getValue("Automatic-Module-Name");
        if ("org.sinytra.connector".equals(moduleName)) {
            // Sinytra Connector: a loader plug-in that runs Fabric mods. Its own mod jar isn't listed as jar-in-jar.
            String v = jarVersion != null ? jarVersion : "unknown";
            nested = new java.util.ArrayList<>(nested);
            nested.add(new ModInfo("connector", v, "Sinytra Connector", List.of()));
            nested.add(new ModInfo("connectormod", v, "Sinytra Connector", List.of()));
            return new ModJar(file, ModJar.Kind.LIBRARY, List.of(), List.copyOf(nested), null);
        }

        // Some jars carry both: mods.toml for 1.20.1 Forge and neoforge.mods.toml for 1.21 NeoForge.
        // The loader only reads its own, so read the one this pack's loader would.
        byte[] neo = entries.get(NEO_TOML);
        byte[] forge = entries.get(FORGE_TOML);
        if (legacy && forge != null) {
            return parsedToml(file, ModJar.Kind.FORGE, forge, jarVersion, nested);
        }
        if (neo != null) {
            return parsedToml(file, ModJar.Kind.NEOFORGE, neo, jarVersion, nested);
        }
        if (forge != null) {
            return parsedToml(file, ModJar.Kind.FORGE, forge, jarVersion, nested);
        }
        // Loader plug-ins that also carry fabric.mod.json (e.g. Preloading Tricks) load on (Neo)Forge as plug-ins.
        boolean service = false;
        for (String s : SERVICES) service |= entries.get(s) != null;
        if (service) return new ModJar(file, ModJar.Kind.LIBRARY, List.of(), nested, null);

        byte[] fabric = entries.get(FABRIC_JSON);
        if (fabric == null) fabric = entries.get(QUILT_JSON);
        if (fabric != null) {
            return new ModJar(file, ModJar.Kind.FABRIC, fabricMods(fabric), nested, null);
        }
        if (modType != null || entries.get(JARJAR) != null) {
            return new ModJar(file, ModJar.Kind.LIBRARY, List.of(), nested, null);
        }
        return new ModJar(file, ModJar.Kind.UNKNOWN, List.of(), nested, null);
    }

    private static ModJar parsedToml(Path file, ModJar.Kind kind, byte[] toml, String jarVersion, List<ModInfo> nested) {
        try {
            return new ModJar(file, kind, modsFromToml(text(toml), jarVersion, kind == ModJar.Kind.NEOFORGE), nested, null);
        } catch (RuntimeException e) {
            return new ModJar(file, kind, List.of(), nested, "its mods.toml can't be read: " + describe(e));
        }
    }

    /** Turns neoforge.mods.toml text into mods. Public so the in-game side can reuse it. */
    public static List<ModInfo> modsFromToml(String tomlText, String jarVersion) {
        return modsFromToml(tomlText, jarVersion, true);
    }

    /**
     * @param neoforgeToml true for neoforge.mods.toml, where NeoForge ignores the old "mandatory" key (a dependency
     *                     with no "type" is required); false for Forge's mods.toml, where "mandatory" still counts
     */
    @SuppressWarnings("unchecked")
    static List<ModInfo> modsFromToml(String tomlText, String jarVersion, boolean neoforgeToml) {
        Map<String, Object> toml = Toml.parse(tomlText);
        Map<String, Object> depsTable = toml.get("dependencies") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();
        List<ModInfo> mods = new ArrayList<>();
        for (Map<String, Object> mod : tables(toml.get("mods"))) {
            String id = str(mod.get("modId"));
            if (id == null) continue;
            String version = str(mod.get("version"));
            if (version == null) version = "unknown";
            if (version.contains("${file.jarVersion}")) {
                version = version.replace("${file.jarVersion}", jarVersion != null ? jarVersion : "unknown");
            }
            List<Dependency> deps = new ArrayList<>();
            for (Map<String, Object> d : tables(depsTable.get(id))) {
                String depId = str(d.get("modId"));
                if (depId == null) continue;
                Dependency.Type type;
                if (d.containsKey("type")) {
                    type = Dependency.Type.parse(str(d.get("type")));
                } else if (!neoforgeToml && d.get("mandatory") instanceof Boolean b) { // Forge format
                    type = b ? Dependency.Type.REQUIRED : Dependency.Type.OPTIONAL;
                } else {
                    type = Dependency.Type.REQUIRED;
                }
                deps.add(new Dependency(depId, type, VersionRange.parseLenient(str(d.get("versionRange"))),
                        Dependency.DepSide.parse(str(d.get("side"))), str(d.get("reason"))));
            }
            String displayTest = str(mod.get("displayTest"));
            mods.add(new ModInfo(id, version, str(mod.get("displayName")), List.copyOf(deps),
                    displayTest != null ? displayTest : "MATCH_VERSION"));
        }
        return List.copyOf(mods);
    }

    private static List<ModInfo> fabricMods(byte[] json) {
        try {
            Map<String, Object> obj = Json.parseObject(text(json));
            Object quilt = obj.get("quilt_loader");
            if (quilt instanceof Map<?, ?> q) obj = castMap(q);
            String id = str(obj.get("id"));
            if (id == null) return List.of();
            Object meta = obj.get("metadata");
            String name = str(obj.get("name"));
            if (name == null && meta instanceof Map<?, ?> mm) name = str(mm.get("name"));
            String version = str(obj.get("version"));
            return List.of(new ModInfo(id, version == null ? "unknown" : version, name, List.of()));
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /** Mods inside jar-in-jar libraries listed in META-INF/jarjar/metadata.json. */
    private static List<ModInfo> nestedMods(Path outer, Entries entries, int depth, boolean legacy) {
        List<ModInfo> found = new ArrayList<>();
        try {
            byte[] meta = entries.get(JARJAR);
            if (meta == null) return List.of();
            for (Map<String, Object> jar : tables(Json.parseObject(text(meta)).get("jars"))) {
                String path = str(jar.get("path"));
                if (path == null) continue;
                byte[] bytes = entries.get(path);
                if (bytes == null) continue;
                ModJar inner = read(outer.resolve(path), inMemory(bytes), depth + 1, legacy);
                if (inner.kind() == ModJar.Kind.NEOFORGE || inner.kind() == ModJar.Kind.FORGE) {
                    found.addAll(inner.mods());
                }
                found.addAll(inner.nestedMods());
            }
        } catch (IOException | RuntimeException ignored) {
            // A broken nested jar shouldn't sink the outer one. NeoForge would complain about it itself.
        }
        return List.copyOf(found);
    }

    private static Entries inMemory(byte[] jarBytes) throws IOException {
        Map<String, byte[]> files = new HashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(jarBytes))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                String n = e.getName();
                if (n.equals(NEO_TOML) || n.equals(FORGE_TOML) || n.equals(JARJAR) || n.equals(MANIFEST)
                        || n.equals(FABRIC_JSON) || n.endsWith(".jar") || SERVICES.contains(n)) {
                    files.put(n, in.readAllBytes());
                }
            }
        }
        return files::get;
    }

    private static Manifest manifest(byte[] bytes) {
        if (bytes == null) return null;
        try {
            return new Manifest(new ByteArrayInputStream(bytes));
        } catch (IOException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> tables(Object o) {
        if (o instanceof List<?> list) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object item : list) if (item instanceof Map) out.add((Map<String, Object>) item);
            return out;
        }
        if (o instanceof Map) return List.of((Map<String, Object>) o);
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    private static String str(Object o) {
        if (o == null) return null;
        String s = o.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static String text(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }

    private static String describe(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }
}
