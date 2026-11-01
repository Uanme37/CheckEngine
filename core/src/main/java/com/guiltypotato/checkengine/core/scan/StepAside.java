package com.guiltypotato.checkengine.core.scan;

import com.guiltypotato.checkengine.core.model.ModInfo;
import com.guiltypotato.checkengine.core.model.ModJar;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Other mods that already do part of Check Engine's job: when one is installed, that part goes quiet so players don't
 * get two of the same. Only checks whether the mod is there, never its settings. Fix buttons always stay.
 */
public final class StepAside {
    /** What goes quiet for a rival. */
    public enum Feature { CRASH_LINES, COPY_FOR_HELP }

    /**
     * @param name       shown in the report and log
     * @param modIds     its mod ids
     * @param filePrefix the start of its jar's name, lower case, for when the scan isn't done or found no id
     * @param quiet      what goes quiet
     */
    record Rival(String name, Set<String> modIds, String filePrefix, Set<Feature> quiet) {}

    static final List<Rival> RIVALS = List.of(new Rival("another crash helper mod", Set.of("crash_assistant"), "crashassistant",
            Set.of(Feature.CRASH_LINES, Feature.COPY_FOR_HELP)));

    private StepAside() {}

    /** Whether this part goes quiet. {@code report} may be null when the scan isn't done yet. */
    public static boolean quiet(Feature feature, Path gameDir, Report report) {
        if (!Modules.load(gameDir).stepAside()) return false;
        for (Rival r : RIVALS) {
            if (r.quiet().contains(feature) && installed(r, gameDir, report)) return true;
        }
        return false;
    }

    /** "another crash helper mod (crash lines, copy for help)" for each rival Check Engine steps aside for. */
    public static List<String> active(Path gameDir, Report report) {
        List<String> out = new ArrayList<>();
        if (!Modules.load(gameDir).stepAside()) return out;
        for (Rival r : RIVALS) {
            if (!installed(r, gameDir, report)) continue;
            List<String> parts = r.quiet().stream().map(f -> f.name().toLowerCase(Locale.ROOT).replace('_', ' ')).sorted()
                    .toList();
            out.add(r.name() + " (" + String.join(", ", parts) + ")");
        }
        return out;
    }

    private static boolean installed(Rival r, Path gameDir, Report report) {
        if (report != null) {
            for (ModJar jar : report.jars()) {
                for (ModInfo m : jar.mods()) if (r.modIds().contains(m.modId())) return true;
                for (ModInfo m : jar.nestedMods()) if (r.modIds().contains(m.modId())) return true;
            }
        }
        try (Stream<Path> files = Files.list(gameDir.resolve("mods"))) {
            return files.map(p -> p.getFileName().toString().toLowerCase(Locale.ROOT))
                    .anyMatch(n -> n.startsWith(r.filePrefix()) && n.endsWith(".jar"));
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}
