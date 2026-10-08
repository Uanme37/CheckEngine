package com.guiltypotato.checkengine.early;

import com.guiltypotato.checkengine.core.model.Dependency;
import com.guiltypotato.checkengine.core.model.ModInfo;
import com.guiltypotato.checkengine.core.scan.LoadBlockers.Blocker;
import com.guiltypotato.checkengine.core.scan.LoadBlockers.Kind;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import com.guiltypotato.checkengine.core.version.VersionRange;
import net.neoforged.fml.loading.FMLConfig;
import net.neoforged.neoforgespi.language.IModInfo;
import net.neoforged.neoforgespi.language.IModInfo.DependencyType;
import net.neoforged.neoforgespi.locating.IModFile;
import org.apache.maven.artifact.versioning.ArtifactVersion;

/**
 * NeoForge's own dependency check (ModSorter.verifyDependencyVersions in FML 11, NeoForge 26.1), run a moment early.
 * Using the loader's exact rules and fml.toml overrides means Check Engine only reports what
 * NeoForge is really about to refuse: no false alarms, and never the reason a working pack stops.
 */
final class DependencyRules {
    private DependencyRules() {}

    static List<Blocker> check(List<IModFile> files) {
        // Like NeoForge, when a mod id shows up twice only the newest copy counts.
        Map<String, IModInfo> mods = new LinkedHashMap<>();
        for (IModFile file : files) {
            for (IModInfo mod : file.getModInfos()) {
                mods.merge(mod.getModId(), mod, (a, b) -> a.getVersion().compareTo(b.getVersion()) >= 0 ? a : b);
            }
        }
        Map<String, ArtifactVersion> versions = new HashMap<>();
        mods.forEach((id, mod) -> versions.put(id, mod.getVersion()));

        List<Blocker> out = new ArrayList<>();
        for (IModInfo mod : mods.values()) {
            Set<String> removed = FMLConfig.getOverrides(mod.getModId()).stream()
                    .filter(FMLConfig.DependencyOverride::remove)
                    .map(FMLConfig.DependencyOverride::modId)
                    .collect(Collectors.toSet());
            for (IModInfo.ModVersion dep : mod.getDependencies()) {
                if (removed.contains(dep.getModId()) || !dep.getSide().isCorrectSide()) continue;
                String id = dep.getModId();
                boolean installed = versions.containsKey(id);
                DependencyType type = dep.getType();
                if (type == DependencyType.REQUIRED || (type == DependencyType.OPTIONAL && installed)) {
                    if (notContained(dep, versions)) {
                        out.add(blocker(installed ? Kind.WRONG_VERSION : Kind.MISSING, mod, dep, mods, versions));
                    }
                } else if (type == DependencyType.INCOMPATIBLE && installed && !notContained(dep, versions)) {
                    out.add(blocker(Kind.INCOMPATIBLE, mod, dep, mods, versions));
                }
            }
        }
        return out;
    }

    /** NeoForge's view of every mod, in core's terms, so the root-cause engine can follow who needs whom. */
    static List<ModInfo> coreMods(List<IModFile> files) {
        List<ModInfo> out = new ArrayList<>();
        for (IModFile file : files) {
            for (IModInfo mod : file.getModInfos()) {
                List<Dependency> deps = mod.getDependencies().stream()
                        .map(d -> new Dependency(d.getModId(), Dependency.Type.parse(d.getType().name()),
                                VersionRange.parseLenient(d.getVersionRange().toString()), Dependency.DepSide.BOTH,
                                d.getReason().orElse(null)))
                        .toList();
                out.add(new ModInfo(mod.getModId(), mod.getVersion().toString(), mod.getDisplayName(), deps));
            }
        }
        return out;
    }

    // FML 11's version support matrix only adds exceptions for Minecraft 1.21.8, so on 26.1 this is the plain rule.
    private static boolean notContained(IModInfo.ModVersion dep, Map<String, ArtifactVersion> versions) {
        String id = dep.getModId();
        return !(versions.containsKey(id) && (dep.getVersionRange().containsVersion(versions.get(id))
                || versions.get(id).toString().equals("0.0NONE")));
    }

    private static Blocker blocker(Kind kind, IModInfo mod, IModInfo.ModVersion dep, Map<String, IModInfo> mods,
                                   Map<String, ArtifactVersion> versions) {
        IModInfo target = mods.get(dep.getModId());
        ArtifactVersion have = versions.get(dep.getModId());
        return new Blocker(kind, mod.getModId(), mod.getDisplayName(), dep.getModId(),
                target == null ? null : target.getDisplayName(), dep.getVersionRange().toString(),
                have == null ? null : have.toString(), dep.getReason().orElse(null));
    }
}
