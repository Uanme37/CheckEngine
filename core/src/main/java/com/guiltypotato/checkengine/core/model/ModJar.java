package com.guiltypotato.checkengine.core.model;

import java.nio.file.Path;
import java.util.List;

/**
 * What we learned from one jar in the mods folder.
 *
 * @param file       the jar
 * @param kind       what sort of jar it is
 * @param mods       mods declared by the jar itself
 * @param nestedMods mods shipped inside it (jar-in-jar); these satisfy dependencies but are never "duplicates"
 * @param error      why the jar couldn't be read, or null
 */
public record ModJar(Path file, Kind kind, List<ModInfo> mods, List<ModInfo> nestedMods, String error, int java) {

    /** @param java the Java its code was built for (0 when unknown or too few classes to tell) */
    public ModJar(Path file, Kind kind, List<ModInfo> mods, List<ModInfo> nestedMods, String error) {
        this(file, kind, mods, nestedMods, error, 0);
    }

    public ModJar withJava(int java) {
        return new ModJar(file, kind, mods, nestedMods, error, java);
    }

    public enum Kind {
        /** Has META-INF/neoforge.mods.toml. */
        NEOFORGE,
        /** Only has META-INF/mods.toml: a Forge mod (or NeoForge for 1.20.1). */
        FORGE,
        /** Only has fabric.mod.json (or quilt.mod.json). */
        FABRIC,
        /** A library jar NeoForge loads without a mods.toml (FMLModType in the manifest, or jar-in-jar only). */
        LIBRARY,
        /** No mod metadata at all. */
        UNKNOWN,
        /** Couldn't be opened: corrupt or not a zip. */
        BROKEN
    }

    public String fileName() {
        return file.getFileName().toString();
    }
}
