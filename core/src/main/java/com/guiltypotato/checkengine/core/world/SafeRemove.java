package com.guiltypotato.checkengine.core.world;

import com.guiltypotato.checkengine.core.scan.Changes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Before a world opens without a mod it still uses: how much of the world goes with it ("1,402 Create blocks, 18
 * of them chests or machines"), so the player can back it up first or put the mod back.
 */
public final class SafeRemove {
    /** Only the most recently played worlds are read: old ones can wait for the CLI. */
    static final int MAX_WORLDS = 8;

    /**
     * A world that still has things from removed mods.
     *
     * @param name   the world's folder name
     * @param folder the world folder
     * @param byMod  mod name -> what of it is in the world
     */
    public record WorldHit(String name, Path folder, Map<String, WorldUsage.Usage> byMod) {}

    private SafeRemove() {}

    /** The mods removed since the pack last worked: mod id -> name. */
    public static Map<String, String> removed(Changes changes) {
        Map<String, String> out = new LinkedHashMap<>();
        if (changes == null) return out;
        for (Changes.Change c : changes.changes()) {
            if (c.type() == Changes.Type.REMOVED && !c.id().equals("minecraft") && !c.id().equals("loader")
                    && !c.id().equals("checkengine")) {
                out.put(c.id(), c.name());
            }
        }
        return out;
    }

    /** The worlds (newest played first) that still use these mods (mod id -> name). */
    public static List<WorldHit> check(Path gameDir, Map<String, String> mods) {
        List<WorldHit> out = new ArrayList<>();
        if (mods.isEmpty()) return out;
        for (Path world : worlds(gameDir)) {
            try {
                Map<String, WorldUsage.Usage> found = WorldUsage.count(world, Set.copyOf(mods.keySet()));
                if (found.isEmpty()) continue;
                Map<String, WorldUsage.Usage> byName = new LinkedHashMap<>();
                found.forEach((id, u) -> byName.put(mods.getOrDefault(id, id), u));
                out.add(new WorldHit(world.getFileName().toString(), world, byName));
            } catch (IOException | RuntimeException ignored) {
                // a world we can't read: nothing to say about it
            }
        }
        return out;
    }

    /** saves/* with a level.dat, most recently played first; on a server, its world folder (level-name). */
    static List<Path> worlds(Path gameDir) {
        List<Path> out = new ArrayList<>();
        Path server = gameDir.resolve(serverWorldName(gameDir));
        if (Files.isRegularFile(server.resolve("level.dat"))) out.add(server);
        Path saves = gameDir.resolve("saves");
        if (!Files.isDirectory(saves)) return out;
        try (Stream<Path> s = Files.list(saves)) {
            s.filter(w -> Files.isRegularFile(w.resolve("level.dat")))
                    .sorted(Comparator.comparingLong(SafeRemove::played).reversed()).limit(MAX_WORLDS).forEach(out::add);
        } catch (IOException e) {
            // no saves to read
        }
        return out;
    }

    /** server.properties' level-name, or "world". */
    private static String serverWorldName(Path gameDir) {
        Path props = gameDir.resolve("server.properties");
        if (!Files.isRegularFile(props)) return "world";
        java.util.Properties p = new java.util.Properties();
        try (java.io.Reader in = Files.newBufferedReader(props, java.nio.charset.StandardCharsets.UTF_8)) {
            p.load(in);
        } catch (IOException | IllegalArgumentException e) {
            return "world";
        }
        String name = p.getProperty("level-name", "world").strip();
        return name.isEmpty() || name.contains("..") ? "world" : name;
    }

    private static long played(Path world) {
        try {
            return Files.getLastModifiedTime(world.resolve("level.dat")).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    /** "1,402 blocks (18 chests or machines), 3 mobs" */
    public static String describe(WorldUsage.Usage u) {
        List<String> parts = new ArrayList<>();
        if (u.blocks() > 0) {
            parts.add(String.format(Locale.ROOT, "%,d block%s", u.blocks(), u.blocks() == 1 ? "" : "s")
                    + (u.blockEntities() > 0 ? String.format(Locale.ROOT, " (%,d chests or machines)", u.blockEntities())
                    : ""));
        } else if (u.blockEntities() > 0) {
            parts.add(String.format(Locale.ROOT, "%,d chests or machines", u.blockEntities()));
        }
        if (!u.topBlocks().isEmpty()) {
            parts.set(0, parts.get(0) + ", mostly " + String.join(", ", u.topBlocks().stream()
                    .map(b -> b.replace('_', ' ')).toList()));
        }
        if (u.entities() > 0) parts.add(String.format(Locale.ROOT, "%,d mob%s", u.entities(), u.entities() == 1 ? "" : "s"));
        return String.join(", ", parts);
    }

    public static String toText(List<WorldHit> hits) {
        if (hits.isEmpty()) return "No world uses those mods.\n";
        StringBuilder sb = new StringBuilder();
        for (WorldHit h : hits) {
            sb.append("World \"").append(h.name()).append("\" still has:\n");
            h.byMod().forEach((mod, u) -> sb.append("  ").append(mod).append(": ").append(describe(u)).append('\n'));
        }
        sb.append("Opening a world without the mod removes these for good (with what's inside them). Back the world "
                + "up first, or put the mod back.\n");
        return sb.toString();
    }

    /** Copies the world next to itself in saves/ (it shows in the world list). Returns the copy. */
    public static Path backup(Path world) throws IOException {
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm"));
        Path to = world.resolveSibling(world.getFileName() + " - backup before removing mods " + stamp);
        com.guiltypotato.checkengine.core.crash.WorldRescue.copyFolder(world, to);
        return to;
    }
}
