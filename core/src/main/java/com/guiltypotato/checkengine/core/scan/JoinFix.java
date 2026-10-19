package com.guiltypotato.checkengine.core.scan;

import com.guiltypotato.checkengine.core.scan.JoinMismatch.Failure;
import com.guiltypotato.checkengine.core.scan.JoinMismatch.Kind;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The "Fix" button on the can't-join screen. Mods the server doesn't have are switched off (renamed to .disabled,
 * nothing deleted). For mods the server has in another version, or that the player lacks, the player gets the Modrinth
 * search page for the mod, with the version the server wants. Check Engine never goes online and never downloads mods
 * (mod sites don't allow mods that install other mods): the player's browser does the rest.
 */
public final class JoinFix {
    static final String API = "https://api.modrinth.com/v2/";
    /** Lists what the last fix changed, so it can be undone. */
    public static final String UNDO_FILE = "join-fix-undo.txt";

    private JoinFix() {}

    public enum Action { SWITCH_OFF, OPEN_PAGE }

    /**
     * One thing the fix will do.
     *
     * @param jar the player's jar to switch off (SWITCH_OFF), else null
     * @param url the page to open (OPEN_PAGE)
     */
    public record Step(Action action, String modId, String name, String version, Path jar, String url) {
        public String describe() {
            return switch (action) {
                case SWITCH_OFF -> "Switch off " + name + " (the server doesn't have it)";
                case OPEN_PAGE -> "Get " + name + (version != null ? " " + version : "") + " yourself: " + url;
            };
        }
    }

    /**
     * Works out the steps. Offline: nothing is looked up.
     *
     * @param jars the player's mod jars by mod id
     */
    public static List<Step> plan(List<Failure> failures, Map<String, Path> jars, Map<String, String> names) {
        Map<String, Failure> byMod = new LinkedHashMap<>();
        for (Failure f : failures) byMod.putIfAbsent(f.namespace(), f);
        List<Step> steps = new ArrayList<>();
        byMod.forEach((id, f) -> {
            if (id.isEmpty() || List.of("minecraft", "neoforge", "forge", "fml", "c").contains(id)) return;
            Path jar = jars.get(id);
            String name = names.getOrDefault(id, f.modName() != null && !f.modName().isBlank() ? f.modName() : id);
            String serverVersion = f.serverVersion() == null || f.serverVersion().isBlank() ? null : f.serverVersion();
            Kind kind = f.kind() == Kind.MISSING_ON_PLAYER && jar != null ? Kind.DIFFERENT : f.kind();
            if (kind == Kind.MISSING_ON_SERVER) {
                if (jar != null) steps.add(new Step(Action.SWITCH_OFF, id, name, null, jar, null));
                return;
            }
            if (kind != Kind.MISSING_ON_PLAYER && kind != Kind.DIFFERENT) return;
            // Modrinth's search for the mod: it's usually the first result.
            String page = "https://modrinth.com/mods?q=" + URLEncoder.encode(name, StandardCharsets.UTF_8);
            steps.add(new Step(Action.OPEN_PAGE, id, name, serverVersion, null, page));
        });
        return steps;
    }

    /**
     * Does the steps that need no player: switch-offs. Writes an undo list.
     *
     * @return one line per step: what was done, or why not
     */
    public static List<String> apply(List<Step> steps, Path modsDir, Path checkEngineFolder) throws IOException {
        List<String> done = new ArrayList<>();
        List<String> undo = new ArrayList<>();
        for (Step s : steps) {
            try {
                switch (s.action()) {
                    case SWITCH_OFF -> {
                        undo.add("enable\t" + switchOff(s.jar()).getFileName());
                        done.add("Switched off " + s.name());
                    }
                    case OPEN_PAGE -> done.add("Get " + s.name() + (s.version() != null ? " " + s.version() : "") + " yourself: " + s.url());
                }
            } catch (IOException | RuntimeException e) {
                done.add("Couldn't fix " + s.name() + ": " + e.getMessage());
            }
        }
        if (!undo.isEmpty()) {
            Files.createDirectories(checkEngineFolder);
            Files.write(checkEngineFolder.resolve(UNDO_FILE), undo, StandardCharsets.UTF_8);
        }
        return done;
    }

    /** Puts back what the last fix changed: re-enables switched-off jars (and switches off jars older versions downloaded). */
    public static int undo(Path modsDir, Path checkEngineFolder) throws IOException {
        Path file = checkEngineFolder.resolve(UNDO_FILE);
        if (!Files.isRegularFile(file)) return 0;
        int n = 0;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String[] p = line.split("\t", 2);
            if (p.length < 2) continue;
            Path jar = modsDir.resolve(p[1]);
            if (p[0].equals("enable") && Files.isRegularFile(jar)) {
                String name = p[1].endsWith(".disabled") ? p[1].substring(0, p[1].length() - ".disabled".length()) : p[1];
                Files.move(jar, modsDir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                n++;
            } else if (p[0].equals("remove") && Files.isRegularFile(jar)) {
                switchOff(jar);
                n++;
            }
        }
        Files.delete(file);
        return n;
    }

    /** Never deletes a player's file: renames it to .disabled. */
    private static Path switchOff(Path jar) throws IOException {
        Path off = jar.resolveSibling(jar.getFileName() + ".disabled");
        return Files.move(jar, off, StandardCopyOption.REPLACE_EXISTING);
    }
}
