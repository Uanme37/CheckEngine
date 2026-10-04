package com.guiltypotato.packdoctor.core.scan;

import com.guiltypotato.packdoctor.core.model.Side;
import com.guiltypotato.packdoctor.core.version.ModVersion;
import java.util.Set;

/**
 * What we know about the pack beyond its mods folder.
 *
 * @param side             client or server install
 * @param minecraftVersion e.g. "1.21.1", or null if unknown (then Minecraft version checks are skipped)
 * @param neoforgeVersion  the mod loader's version, e.g. "21.1.251" (NeoForge) or "47.4.20" (Forge), or null if
 *                         unknown (then loader version checks are skipped)
 * @param clientOnlyFiles  jar file names (lower case) a launcher says are client-only, e.g. from CurseForge
 * @param loader           which mod loader the pack uses
 */
public record ScanOptions(Side side, String minecraftVersion, String neoforgeVersion, Set<String> clientOnlyFiles,
                          Loader loader) {

    public enum Loader { NEOFORGE, FORGE, FABRIC, UNKNOWN }

    public ScanOptions {
        if (side == null) side = Side.UNKNOWN;
        if (loader == null) loader = Loader.UNKNOWN;
        clientOnlyFiles = clientOnlyFiles == null ? Set.of() : Set.copyOf(clientOnlyFiles);
    }

    public ScanOptions(Side side, String minecraftVersion, String neoforgeVersion, Set<String> clientOnlyFiles) {
        this(side, minecraftVersion, neoforgeVersion, clientOnlyFiles, Loader.UNKNOWN);
    }

    public static ScanOptions of(Side side) {
        return new ScanOptions(side, null, null, Set.of());
    }

    public ScanOptions withSide(Side newSide) {
        return new ScanOptions(newSide, minecraftVersion, neoforgeVersion, clientOnlyFiles, loader);
    }

    /** Minecraft 1.20.4 and older: mods use META-INF/mods.toml (Forge, or NeoForge for 1.20.1). */
    public boolean legacy() {
        if (minecraftVersion != null) {
            return ModVersion.parse(minecraftVersion).compareTo(ModVersion.parse("1.20.5")) < 0;
        }
        return loader == Loader.FORGE;
    }

    /** "Forge" or "NeoForge", for messages. */
    public String loaderName() {
        return loader == Loader.FORGE ? "Forge" : "NeoForge";
    }
}
