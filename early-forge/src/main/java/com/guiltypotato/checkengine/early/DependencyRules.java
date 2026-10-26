package com.guiltypotato.checkengine.early;

import com.guiltypotato.checkengine.core.model.Dependency;
import com.guiltypotato.checkengine.core.model.ModInfo;
import com.guiltypotato.checkengine.core.scan.LoadBlockers.Blocker;
import com.guiltypotato.checkengine.core.scan.LoadBlockers.Kind;
import com.guiltypotato.checkengine.core.version.VersionRange;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraftforge.fml.loading.VersionSupportMatrix;
import net.minecraftforge.forgespi.language.IModInfo;
import net.minecraftforge.forgespi.locating.IModFile;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;

/**
 * Forge's own dependency check (ModSorter.verifyDependencyVersions in Forge 47 for 1.20.1), run a moment early.
 * Using the loader's exact rules means Check Engine only reports what Forge is really about to refuse. Forge has no
 * "incompatible" rules or fml.toml overrides, unlike NeoForge.
 */
final class DependencyRules {
    private DependencyRules() {}

    /**
     * @param extra mods Forge will also see that may not be in {@code files} yet (packed inside other jars), id ->
     *              version, so a rule they satisfy isn't reported
     */
    static List<Blocker> check(Iterable<IModFile> files, Map<String, String> extra) {
        // Like Forge, when a mod id shows up twice only the newest copy counts.
        Map<String, IModInfo> mods = new LinkedHashMap<>();
        for (IModFile file : files) {
            if (file.getModFileInfo() == null) continue; // libraries: no mods in them
            for (IModInfo mod : file.getModInfos()) {
                mods.merge(mod.getModId(), mod, (a, b) -> a.getVersion().compareTo(b.getVersion()) >= 0 ? a : b);
            }
        }
        Map<String, ArtifactVersion> versions = new HashMap<>();
        extra.forEach((id, v) -> versions.put(id, new DefaultArtifactVersion(v)));
        mods.forEach((id, mod) -> versions.put(id, mod.getVersion()));

        List<Blocker> out = new ArrayList<>();
        for (IModInfo mod : mods.values()) {
            for (IModInfo.ModVersion dep : mod.getDependencies()) {
                if (!dep.getSide().isCorrectSide() || dep.getModId().equals(mod.getModId())) continue;
                boolean installed = versions.containsKey(dep.getModId());
                if ((dep.isMandatory() || installed) && notContained(dep, versions)) {
                    out.add(blocker(installed ? Kind.WRONG_VERSION : Kind.MISSING, mod, dep, mods, versions));
                }
            }
        }
        return out;
    }

    /** Forge's view of every mod, in core's terms, so the root-cause engine can follow who needs whom. */
    static List<ModInfo> coreMods(Iterable<IModFile> files) {
        List<ModInfo> out = new ArrayList<>();
        for (IModFile file : files) {
            if (file.getModFileInfo() == null) continue; // libraries: no mods in them
            for (IModInfo mod : file.getModInfos()) {
                List<Dependency> deps = mod.getDependencies().stream()
                        .map(d -> new Dependency(d.getModId(), d.isMandatory() ? Dependency.Type.REQUIRED
                                : Dependency.Type.OPTIONAL, VersionRange.parseLenient(d.getVersionRange().toString()),
                                Dependency.DepSide.BOTH, null))
                        .toList();
                out.add(new ModInfo(mod.getModId(), mod.getVersion().toString(), mod.getDisplayName(), deps));
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
        return new Blocker(kind, mod.getModId(), mod.getDisplayName(), dep.getModId(),
                target == null ? null : target.getDisplayName(), dep.getVersionRange().toString(),
                have == null ? null : have.toString(), null);
    }
}
