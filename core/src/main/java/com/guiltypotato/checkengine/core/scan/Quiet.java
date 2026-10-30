package com.guiltypotato.checkengine.core.scan;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * What the player chose to stop seeing, kept in the checkengine folder:
 * <ul>
 *   <li>"Got it": each warning is remembered on its own, so a new warning shows only itself, not the old ones too.</li>
 *   <li>"Only tell me when it breaks": no warnings at all; problems that stop the game and crashes still show.</li>
 * </ul>
 * The pack maker's own quiet mode is {@link PackInfo}.
 */
public final class Quiet {
    static final String SEEN = "seen-warnings.txt";
    static final String PLAYER = "player.properties";
    private static final String ONLY_WHEN_IT_BREAKS = "only_when_it_breaks";
    private static final String TITLE_BUTTON = "title_button";

    private Quiet() {}

    /**
     * The findings to show this launch: not the ones the player said "Got it" to, and only problems when they turned
     * warnings off. A remembered warning that's gone is forgotten, so if it comes back it shows again.
     */
    public static List<Finding> toShow(Path folder, List<Finding> findings) {
        Set<String> seen = seen(folder);
        Set<String> now = new LinkedHashSet<>();
        findings.forEach(f -> now.add(f.title()));
        if (seen.retainAll(now)) write(folder, seen);
        boolean onlyProblems = onlyWhenItBreaks(folder);
        List<Finding> out = new ArrayList<>();
        for (Finding f : findings) {
            if (seen.contains(f.title())) continue;
            if (onlyProblems && f.severity() != Finding.Severity.ERROR) continue;
            out.add(f);
        }
        return out;
    }

    /** "Got it": these stop showing until they're gone and come back. */
    public static void gotIt(Path folder, List<Finding> findings) {
        Set<String> seen = seen(folder);
        findings.forEach(f -> seen.add(f.title()));
        write(folder, seen);
    }

    /** Whether the player turned warnings off ("Only tell me when it breaks"). */
    public static boolean onlyWhenItBreaks(Path folder) {
        Properties p = new Properties();
        try (Reader in = Files.newBufferedReader(folder.resolve(PLAYER), StandardCharsets.UTF_8)) {
            p.load(in);
        } catch (IOException e) {
            return false;
        }
        return Boolean.parseBoolean(p.getProperty(ONLY_WHEN_IT_BREAKS, "false").strip());
    }

    /** Whether Check Engine's title screen button shows: the player's choice, else the pack maker's default. */
    public static boolean titleButton(Path folder, Path gameDir) {
        Properties p = new Properties();
        try (Reader in = Files.newBufferedReader(folder.resolve(PLAYER), StandardCharsets.UTF_8)) {
            p.load(in);
        } catch (IOException e) {
            // no choice made yet
        }
        String v = p.getProperty(TITLE_BUTTON);
        return v == null ? PackInfo.titleButtonByDefault(gameDir) : Boolean.parseBoolean(v.strip());
    }

    public static void setTitleButton(Path folder, boolean shown) throws IOException {
        set(folder, TITLE_BUTTON, Boolean.toString(shown));
    }

    public static void setOnlyWhenItBreaks(Path folder, boolean on) throws IOException {
        set(folder, ONLY_WHEN_IT_BREAKS, Boolean.toString(on));
    }

    private static void set(Path folder, String key, String value) throws IOException {
        Properties p = new Properties();
        Path file = folder.resolve(PLAYER);
        if (Files.exists(file)) {
            try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                p.load(in);
            }
        }
        p.setProperty(key, value);
        Files.createDirectories(folder);
        try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            p.store(out, "Check Engine: this player's choices. only_when_it_breaks=true hides warnings; "
                    + "title_button=false hides the title screen button; problems that stop the game and crashes still "
                    + "show.");
        }
    }

    private static Set<String> seen(Path folder) {
        try {
            Set<String> out = new LinkedHashSet<>();
            for (String l : Files.readAllLines(folder.resolve(SEEN), StandardCharsets.UTF_8)) {
                if (!l.isBlank()) out.add(l.strip());
            }
            return out;
        } catch (IOException e) {
            return new LinkedHashSet<>();
        }
    }

    private static void write(Path folder, Set<String> seen) {
        try {
            Files.createDirectories(folder);
            Files.write(folder.resolve(SEEN), seen, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // not remembered: the warning shows again next launch, nothing worse
        }
    }
}
