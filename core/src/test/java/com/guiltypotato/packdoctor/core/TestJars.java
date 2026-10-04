package com.guiltypotato.packdoctor.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Builds tiny fake mod jars for tests. */
public final class TestJars {
    private final Map<String, byte[]> files = new LinkedHashMap<>();

    public static TestJars jar() {
        return new TestJars();
    }

    /** A NeoForge mod with optional extra toml (dependency blocks) appended. */
    public static TestJars neoMod(String id, String version, String extraToml) {
        return jar().with("META-INF/neoforge.mods.toml", modsToml(id, version, extraToml));
    }

    public static String modsToml(String id, String version, String extraToml) {
        return """
                modLoader = "javafml"
                loaderVersion = "[1,)"
                license = "MIT"

                [[mods]]
                modId = "%s"
                version = "%s"
                displayName = "%s"
                """.formatted(id, version, Character.toUpperCase(id.charAt(0)) + id.substring(1))
                + (extraToml == null ? "" : "\n" + extraToml);
    }

    public static String dep(String owner, String id, String type, String range, String side) {
        return """
                [[dependencies.%s]]
                modId = "%s"
                type = "%s"
                versionRange = "%s"
                ordering = "NONE"
                side = "%s"
                """.formatted(owner, id, type, range, side);
    }

    public TestJars with(String path, String text) {
        files.put(path, text.getBytes(StandardCharsets.UTF_8));
        return this;
    }

    public TestJars with(String path, byte[] bytes) {
        files.put(path, bytes);
        return this;
    }

    /** Puts another jar inside this one and lists it in META-INF/jarjar/metadata.json. */
    public TestJars nest(String path, TestJars inner) throws IOException {
        files.put(path, inner.bytes());
        files.put("META-INF/jarjar/metadata.json", """
                {"jars": [{"identifier": {"group": "x", "artifact": "y"},
                  "version": {"range": "[1,)", "artifactVersion": "1"}, "path": "%s"}]}
                """.formatted(path).getBytes(StandardCharsets.UTF_8));
        return this;
    }

    public byte[] bytes() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out);
        return out.toByteArray();
    }

    public Path writeTo(Path dir, String name) throws IOException {
        Path p = dir.resolve(name);
        try (OutputStream out = Files.newOutputStream(p)) {
            write(out);
        }
        return p;
    }

    private void write(OutputStream out) throws IOException {
        ZipOutputStream zip = new ZipOutputStream(out);
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            zip.putNextEntry(new ZipEntry(e.getKey()));
            zip.write(e.getValue());
            zip.closeEntry();
        }
        zip.finish();
    }
}
