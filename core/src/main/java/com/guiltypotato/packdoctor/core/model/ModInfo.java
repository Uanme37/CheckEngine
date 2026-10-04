package com.guiltypotato.packdoctor.core.model;

import java.util.List;

/**
 * One {@code [[mods]]} entry: a mod id with its version and what it depends on.
 *
 * @param displayTest how the mod wants client/server versions compared ("MATCH_VERSION" when unset,
 *                    "IGNORE_SERVER_VERSION" for client-side mods, "IGNORE_ALL_VERSION"/"NONE" for either-side
 *                    mods), or null for non-NeoForge mods
 */
public record ModInfo(String modId, String version, String displayName, List<Dependency> dependencies,
                      String displayTest) {

    public ModInfo(String modId, String version, String displayName, List<Dependency> dependencies) {
        this(modId, version, displayName, dependencies, null);
    }

    /** Display name if the mod has one, otherwise its id. */
    public String name() {
        return displayName == null || displayName.isBlank() ? modId : displayName;
    }

    /** True if the mod says it's fine for the other side not to have it (or to have another version). */
    public boolean optionalOnOtherSide() {
        return "IGNORE_SERVER_VERSION".equals(displayTest) || "IGNORE_ALL_VERSION".equals(displayTest)
                || "NONE".equals(displayTest);
    }
}