package com.guiltypotato.checkengine.core.scan;

import com.guiltypotato.checkengine.core.model.Dependency;
import com.guiltypotato.checkengine.core.model.ModInfo;
import java.util.Set;

/** Guesses whether a mod only belongs on the client. Mods don't declare this, so we combine a few signals. */
final class ClientOnlyMods {

    /** Mods that are known to be client-only (rendering, UI, sound). Several of these crash a dedicated server. */
    static final Set<String> KNOWN = Set.of(
            // renderers and shaders
            "sodium", "embeddium", "rubidium", "iris", "oculus", "sodium_extra", "sodiumextra", "embeddiumplus",
            "reeses_sodium_options", "sodiumoptionsapi", "sodiumdynamiclights", "lambdynlights", "ryoamiclights",
            "entityculling", "immediatelyfast", "cullleaves", "dynamic_fps", "fpsreducer",
            "entity_model_features", "entity_texture_features", "citresewn", "continuity",
            // menus, HUD and screens
            "fancymenu", "drippyloadingscreen", "betterf3", "legendarytooltips", "betteradvancements",
            "catalogue", "toastcontrol", "blur", "cherishedworlds", "chat_heads", "zoomify", "mousetweaks",
            "controlling",
            // animations and visuals
            "notenoughanimations", "eatinganimation", "skinlayers3d", "visuality", "particlerain", "fallingleaves");

    /** Mods whose mods.toml says client-side but that servers commonly run on purpose (e.g. to sync data to players). */
    static final Set<String> FINE_ON_SERVER = Set.of("appleskin");

    private ClientOnlyMods() {}

    /** Returns why we think it's client-only, or null if we don't. */
    static String reason(ModInfo mod, String fileName, Set<String> launcherClientOnly) {
        if (KNOWN.contains(mod.modId())) return "it's a known client-side mod";
        if (FINE_ON_SERVER.contains(mod.modId())) return null;
        if (launcherClientOnly.contains(fileName.toLowerCase(java.util.Locale.ROOT))) {
            return "CurseForge lists it as client-only";
        }
        boolean sawLoaderDep = false;
        for (Dependency d : mod.dependencies()) {
            if (d.modId().equals("minecraft") || d.modId().equals("neoforge")) {
                if (d.side() != Dependency.DepSide.CLIENT) return null;
                sawLoaderDep = true;
            }
        }
        return sawLoaderDep ? "its mods.toml marks it as client-side" : null;
    }
}
