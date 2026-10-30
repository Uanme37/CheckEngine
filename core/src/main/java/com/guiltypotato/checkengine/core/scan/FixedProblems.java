package com.guiltypotato.checkengine.core.scan;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The "Fixed!" toast: problems and warnings from the last launch (checkengine/last-problems.txt) that this launch
 * doesn't have anymore. Compares titles, so a fixed missing mod reads "Fixed: Missing mod: Architectury API".
 */
public final class FixedProblems {
    public static final String FILE = "last-problems.txt";
    /**
     * Warnings about a change since the pack last worked: they go away by themselves on the next good launch,
     * so their going away isn't a fix (a mod that went back a version is still back a version).
     */
    private static final Set<String> SELF_CLEARING = Set.of(Changes.CODE, Changes.DOWNGRADE_CODE, SettingsReset.CODE);

    private FixedProblems() {}

    /** What the last launch had and this one doesn't; then saves this launch's list for next time. */
    public static List<String> update(Path checkEngineFolder, List<Finding> worthShowing) {
        Set<String> now = new LinkedHashSet<>();
        for (Finding f : worthShowing) if (!SELF_CLEARING.contains(f.code())) now.add(f.title());
        List<String> fixed = new ArrayList<>();
        Path file = checkEngineFolder.resolve(FILE);
        try {
            if (Files.isRegularFile(file)) {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (!line.isBlank() && !now.contains(line.strip())) fixed.add(line.strip());
                }
            }
            Files.createDirectories(checkEngineFolder);
            Files.write(file, now, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
        return fixed;
    }
}
