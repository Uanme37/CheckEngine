package com.guiltypotato.checkengine.early;

import com.guiltypotato.checkengine.core.scan.LoadBlockers.Blocker;
import com.guiltypotato.checkengine.core.scan.LoadBlockers.Kind;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.neoforged.fml.loading.FMLConfig;
import net.neoforged.fml.loading.VersionSupportMatrix;
import net.neoforged.neoforgespi.language.IModInfo;
import net.neoforged.neoforgespi.language.IModInfo.DependencyType;
import net.neoforged.neoforgespi.locating.IModFile;
import org.apache.maven.artifact.versioning.ArtifactVersion;

/**
 * NeoForge's own dependency check (ModSorter.verifyDependencyVersions in FML 4), run a moment early. Using the
 * loader's exact rules, fml.toml overrides and version support matrix means Check Engine only reports what
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

    private static boolean notContained(IModInfo.ModVersion dep, Map<String, ArtifactVersion> versions) {
        return !VersionSupportMatrix.testVersionSupportMatrix(dep.getVersionRange(), dep.getModId(), "mod",
                (id, range) -> versions.containsKey(id)
                        && (range.containsVersion(versions.get(id)) || versions.get(id).toString().equals("0.0NONE")));
    }

    private static Blocker blocker(Kind kind, IModInfo mod, IModInfo.ModVersion dep, Map<String, IModInfo> mods,
                                   Map<String, ArtifactVersion> versions) {
        IModInfo target = mods.get(dep.getModId());
        ArtifactVersion have = versions.get(dep.getModId());
        return new Blocker(kind, mod.getDisplayName(), dep.getModId(),
                target == null ? null : target.getDisplayName(), dep.getVersionRange().toString(),
                have == null ? null : have.toString(), dep.getReason().orElse(null));
    }
}
