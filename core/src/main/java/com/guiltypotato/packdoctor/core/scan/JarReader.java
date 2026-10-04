package com.guiltypotato.packdoctor.core.scan;

import com.guiltypotato.packdoctor.core.json.Json;
import com.guiltypotato.packdoctor.core.model.Dependency;
import com.guiltypotato.packdoctor.core.model.ModInfo;
import com.guiltypotato.packdoctor.core.model.ModJar;
import com.guiltypotato.packdoctor.core.toml.Toml;
import com.guiltypotato.packdoctor.core.version.VersionRange;
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

    public static ModJar read(Path file) {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            Entries entries = name -> {
                ZipEntry e = zip.getEntry(name);
                if (e == null) return null;
                try (InputStream in = zip.getInputStream(e)) {
                    return in.readAllBytes();
                }
            };
            return read(file, entries, 0);
        } catch (IOException | RuntimeException e) {
            return new ModJar(file, ModJar.Kind.BROKEN, List.of(), List.of(), describe(e));
        }
    }

    private static ModJar read(Path file, Entries entries, int depth) throws IOException {
        Manifest manifest = manifest(entries.get(MANIFEST));
        String jarVersion = manifest == null ? null : manifest.getMainAttributes().getValue("Implementation-Version");
        String modType = manifest == null ? null : manifest.getMainAttributes().getValue("FMLModType");

        List<ModInfo> nested = depth < MAX_NESTING ? nestedMods(file, entries, depth) : List.of();

        byte[] neo = entries.get(NEO_TOML);
        if (neo != null) {
            return parsedToml(file, ModJar.Kind.NEOFORGE, neo, jarVersion, nested);
        }
        byte[] forge = entries.get(FORGE_TOML);
        if (forge != null) {
            return parsedToml(file, ModJar.Kind.FORGE, forge, jarVersion, nested);
        }
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
            mods.add(new ModInfo(id, version, str(mod.get("displayName")), List.copyOf(deps)));
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
    private static List<ModInfo> nestedMods(Path outer, Entries entries, int depth) {
        List<ModInfo> found = new ArrayList<>();
        try {
            byte[] meta = entries.get(JARJAR);
            if (meta == null) return List.of();
            for (Map<String, Object> jar : tables(Json.parseObject(text(meta)).get("jars"))) {
                String path = str(jar.get("path"));
                if (path == null) continue;
                byte[] bytes = entries.get(path);
                if (bytes == null) continue;
                ModJar inner = read(outer.resolve(path), inMemory(bytes), depth + 1);
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
                        || n.equals(FABRIC_JSON) || n.endsWith(".jar")) {
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
