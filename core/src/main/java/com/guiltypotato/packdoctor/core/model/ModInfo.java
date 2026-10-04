package com.guiltypotato.packdoctor.core.model;

import java.util.List;

/** One {@code [[mods]]} entry: a mod id with its version and what it depends on. */
public record ModInfo(String modId, String version, String displayName, List<Dependency> dependencies) {

    /** Display name if the mod has one, otherwise its id. */
    public String name() {
        return displayName == null || displayName.isBlank() ? modId : displayName;
    }
}
