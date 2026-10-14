package com.guiltypotato.checkengine.core.strip;

import com.guiltypotato.checkengine.core.model.ModJar;
import com.guiltypotato.checkengine.core.scan.PackSnapshot;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * "Undo new mods": switches off the mods added since the pack last worked (with the mods that need them), so the
 * pack is back to how it last started. Renamed to .disabled, never deleted, and {@link #putBack} switches them on
 * again. Updated mods can't be undone this way: their old jars are gone.
 */
public final class UndoNewMods {
    /** checkengine/undo-new-mods.properties: what was switched off, for putting it back. */
    public static final String FILE = "undo-new-mods.properties";

    private UndoNewMods() {}

    /** The jars to switch off: every jar whose mods are all new since the last good launch, plus what needs them. */
    public static List<String> plan(Path modsFolder, Path checkEngineFolder) throws IOException {
        PackSnapshot last = PackSnapshot.load(checkEngineFolder);
        if (last == null || !Files.isDirectory(modsFolder)) return List.of();
        Map<String, ModJar> jars = StripMine.readJars(modsFolder, List.of());
        Set<String> out = new LinkedHashSet<>();
        jars.values().stream().filter(j -> !StripMine.isCheckEngine(j) && !j.mods().isEmpty()
                        && j.mods().stream().noneMatch(m -> last.mods().containsKey(m.modId())))
                .map(ModJar::fileName).sorted().forEach(f -> out.addAll(StripMine.withDependents(f, jars)));
        return new ArrayList<>(out);
    }

    /**
     * Switches {@code jars} off and remembers them. Returns the ones the game holds open (NeoForge 26.1): rename
     * those once it has closed.
     */
    public static List<String> apply(Path modsFolder, Path checkEngineFolder, List<String> jars) throws IOException {
        Set<String> all = new LinkedHashSet<>(switchedOff(checkEngineFolder));
        all.addAll(jars);
        Properties p = new Properties();
        p.setProperty("time", LocalDateTime.now().withNano(0).toString());
        p.setProperty("jars", String.join("|", all));
        Files.createDirectories(checkEngineFolder);
        try (Writer out = Files.newBufferedWriter(checkEngineFolder.resolve(FILE), StandardCharsets.UTF_8)) {
            p.store(out, "Check Engine: mods switched off by Undo new mods (put back with the CLI's putback command)");
        }
        return StripMine.switchOff(modsFolder, jars);
    }

    /** The jars Undo new mods has switched off (empty if none). */
    public static List<String> switchedOff(Path checkEngineFolder) {
        Path file = checkEngineFolder.resolve(FILE);
        if (!Files.isRegularFile(file)) return List.of();
        Properties p = new Properties();
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(in);
        } catch (IOException | IllegalArgumentException e) {
            return List.of();
        }
        String jars = p.getProperty("jars", "").strip();
        return jars.isEmpty() ? List.of() : Arrays.asList(jars.split("\\|"));
    }

    /** Switches them back on. Returns the jars put back; a jar the player removed meanwhile is just skipped. */
    public static List<String> putBack(Path modsFolder, Path checkEngineFolder) throws IOException {
        List<String> back = new ArrayList<>();
        for (String f : switchedOff(checkEngineFolder)) {
            Path off = modsFolder.resolve(f + StripMine.DISABLED);
            if (!Files.isRegularFile(off) || Files.exists(modsFolder.resolve(f))) continue;
            StripMine.rename(off, modsFolder.resolve(f));
            back.add(f);
        }
        Files.deleteIfExists(checkEngineFolder.resolve(FILE)); // our own record, not a player file
        return back;
    }
}
