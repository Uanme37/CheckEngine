package com.guiltypotato.checkengine.core.crash;

import com.guiltypotato.checkengine.core.scan.Finding;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * World rescue: a broken mob or machine crashes the world every time it loads ("Ticking entity"). The loaders have a
 * setting that removes whatever errors instead of crashing (removeErroringEntities / removeErroringBlockEntities), but
 * hardly anyone knows it, and leaving it on is risky. This backs the world up, turns the setting on for one session,
 * and turns it back off the next time the game starts.
 */
public final class WorldRescue {
    static final String STATE = "world-rescue.properties";
    private static final Pattern KEY = Pattern.compile("(?m)^(\\s*removeErroring(?:Block)?Entities\\s*=\\s*)(true|false)");

    private WorldRescue() {}

    /** Whether this crash is one a world rescue can fix: something in the world crashes every time it updates. */
    public static boolean fits(Finding main) {
        return main != null && "ticking-crash".equals(main.code());
    }

    /** The world the player played last (the one that crashed), or null. */
    public static Path newestWorld(Path gameDir) {
        Path saves = gameDir.resolve("saves");
        if (!Files.isDirectory(saves)) return null;
        try (Stream<Path> s = Files.list(saves)) {
            return s.filter(p -> Files.isRegularFile(p.resolve("level.dat")))
                    .max(Comparator.comparingLong(p -> p.resolve("level.dat").toFile().lastModified())).orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    /** NeoForge keeps the setting in config/, Forge 1.20.1 inside each world. */
    static Path configFile(Path gameDir, Path world) {
        Path neo = gameDir.resolve("config").resolve("neoforge-server.toml");
        if (Files.isRegularFile(neo)) return neo;
        return world.resolve("serverconfig").resolve("forge-server.toml");
    }

    /**
     * Backs the world up (a copy next to it in saves/, so it shows in the world list) and turns the setting on.
     * Returns the backup folder.
     */
    public static Path start(Path gameDir, Path checkEngineFolder, Path world) throws IOException {
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm"));
        Path backup = world.resolveSibling(world.getFileName() + " - backup before rescue " + stamp);
        copyFolder(world, backup);
        Path config = configFile(gameDir, world);
        String before = Files.isRegularFile(config) ? Files.readString(config, StandardCharsets.UTF_8) : null;
        if (before != null && KEY.matcher(before).find()) {
            Files.writeString(config, KEY.matcher(before).replaceAll("$1true"), StandardCharsets.UTF_8);
        } else {
            // Forge writes the rest of its settings with their defaults when the world loads.
            Files.createDirectories(config.getParent());
            Files.writeString(config, (before == null ? "" : before + "\n") + "[server]\n\tremoveErroringBlockEntities = "
                    + "true\n\tremoveErroringEntities = true\n", StandardCharsets.UTF_8);
        }
        Properties p = new Properties();
        p.setProperty("config", config.toString());
        p.setProperty("world", world.toString());
        p.setProperty("backup", backup.toString());
        p.setProperty("started", LocalDateTime.now().withNano(0).toString());
        Files.createDirectories(checkEngineFolder);
        try (Writer out = Files.newBufferedWriter(checkEngineFolder.resolve(STATE), StandardCharsets.UTF_8)) {
            p.store(out, "Check Engine world rescue: removeErroring settings are on for one session");
        }
        return backup;
    }

    /**
     * Call when the game starts: if a rescue ran last session, turns the setting back off. Returns a line for the log
     * (and the player), or null when there was nothing to finish.
     */
    public static String finish(Path checkEngineFolder) {
        Path state = checkEngineFolder.resolve(STATE);
        if (!Files.isRegularFile(state)) return null;
        Properties p = new Properties();
        try (Reader in = Files.newBufferedReader(state, StandardCharsets.UTF_8)) {
            p.load(in);
            Path config = Path.of(p.getProperty("config"));
            if (Files.isRegularFile(config)) {
                String text = Files.readString(config, StandardCharsets.UTF_8);
                Files.writeString(config, KEY.matcher(text).replaceAll("$1false"), StandardCharsets.UTF_8);
            }
            // Kept as a record of what was done, never deleted.
            Files.move(state, checkEngineFolder.resolve("world-rescue-done.properties"),
                    StandardCopyOption.REPLACE_EXISTING);
            return "World rescue finished: the remove-erroring setting is off again. The backup is in "
                    + p.getProperty("backup") + ".";
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Whether a rescue is waiting for its world to be opened. */
    public static boolean active(Path checkEngineFolder) {
        return Files.isRegularFile(checkEngineFolder.resolve(STATE));
    }

    private static void copyFolder(Path from, Path to) throws IOException {
        Files.walkFileTree(from, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(to.resolve(from.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (file.getFileName().toString().equals("session.lock")) return FileVisitResult.CONTINUE;
                Files.copy(file, to.resolve(from.relativize(file).toString()), StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** "Ticking entity: Zombie at 120.5, 64.0, -33.2": what the crash says is broken, for the confirm screen. */
    public static String what(Finding main) {
        Matcher m = Pattern.compile("Something in the world crashes every time it updates: (.+?)\\.(?: |$)")
                .matcher(main.detail() == null ? "" : main.detail());
        return m.find() ? m.group(1) : "the broken mob or machine from this crash";
    }
}
