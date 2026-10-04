package com.guiltypotato.packdoctor.core.scan;

import com.guiltypotato.packdoctor.core.model.Side;
import java.util.Set;

/**
 * What we know about the pack beyond its mods folder.
 *
 * @param side             client or server install
 * @param minecraftVersion e.g. "1.21.1", or null if unknown (then Minecraft version checks are skipped)
 * @param neoforgeVersion  e.g. "21.1.251", or null if unknown (then NeoForge version checks are skipped)
 * @param clientOnlyFiles  jar file names (lower case) a launcher says are client-only, e.g. from CurseForge
 */
public record ScanOptions(Side side, String minecraftVersion, String neoforgeVersion, Set<String> clientOnlyFiles) {

    public ScanOptions {
        if (side == null) side = Side.UNKNOWN;
        clientOnlyFiles = clientOnlyFiles == null ? Set.of() : Set.copyOf(clientOnlyFiles);
    }

    public static ScanOptions of(Side side) {
        return new ScanOptions(side, null, null, Set.of());
    }

    public ScanOptions withSide(Side newSide) {
        return new ScanOptions(newSide, minecraftVersion, neoforgeVersion, clientOnlyFiles);
    }
}
