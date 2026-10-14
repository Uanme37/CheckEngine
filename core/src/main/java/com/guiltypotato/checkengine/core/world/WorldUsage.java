package com.guiltypotato.checkengine.core.world;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * How much of a world belongs to some mods: their blocks, block entities (chests, machines) and mobs, read straight
 * from the region files (every dimension). Only the parts needed are decoded, so a big world takes seconds.
 */
public final class WorldUsage {
    /**
     * One mod's share of a world.
     *
     * @param blocks        blocks of it placed in the world
     * @param blockEntities those that hold something: chests, machines, tanks
     * @param entities      its mobs and other entities
     * @param topBlocks     its most common blocks, most first ("zinc_ore", "shaft"), at most 3
     */
    public record Usage(long blocks, long blockEntities, long entities, List<String> topBlocks) {
        public boolean isEmpty() {
            return blocks == 0 && blockEntities == 0 && entities == 0;
        }

        Usage plus(long b, long be, long e) {
            return new Usage(blocks + b, blockEntities + be, entities + e, topBlocks);
        }
    }

    /** Block name counts per namespace while counting one world. */
    private static final ThreadLocal<Map<String, Map<String, Long>>> BLOCKS = ThreadLocal.withInitial(HashMap::new);

    private WorldUsage() {}

    private static void block(String name, long count) {
        int c = name.indexOf(':');
        BLOCKS.get().computeIfAbsent(name.substring(0, c), k -> new HashMap<>())
                .merge(name.substring(c + 1), count, Long::sum);
    }

    /** Namespace -> what of it is in {@code world}, for the namespaces asked (empty ones left out). */
    public static Map<String, Usage> count(Path world, Set<String> namespaces) throws IOException {
        Map<String, Usage> out = new TreeMap<>();
        if (namespaces.isEmpty() || !Files.isDirectory(world)) return out;
        BLOCKS.remove();
        List<Path> regions = new ArrayList<>();
        try (Stream<Path> s = Files.walk(world, 6)) {
            s.filter(p -> p.getFileName().toString().endsWith(".mca")).forEach(regions::add);
        }
        for (Path region : regions) {
            boolean entities = region.getParent().getFileName().toString().equals("entities");
            boolean blocks = region.getParent().getFileName().toString().equals("region");
            if (!entities && !blocks) continue; // poi/ and others: not what a player loses
            for (Map<String, Object> chunk : chunks(region)) {
                if (blocks) countBlocks(chunk, namespaces, out);
                else countEntities(chunk, namespaces, out);
            }
        }
        out.values().removeIf(Usage::isEmpty);
        // the most common blocks of each mod: natural ore reads very differently from a built factory
        Map<String, Usage> named = new TreeMap<>();
        out.forEach((ns, u) -> {
            Map<String, Long> names = BLOCKS.get().getOrDefault(ns, Map.of());
            List<String> top = names.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                    .limit(3).map(Map.Entry::getKey).toList();
            named.put(ns, new Usage(u.blocks(), u.blockEntities(), u.entities(), top));
        });
        BLOCKS.remove();
        return named;
    }

    private static void add(Map<String, Usage> out, String ns, long b, long be, long e) {
        out.merge(ns, new Usage(b, be, e, List.of()), (x, y) -> x.plus(y.blocks(), y.blockEntities(), y.entities()));
    }

    private static String namespace(Object id) {
        if (!(id instanceof String s)) return null;
        int c = s.indexOf(':');
        return c < 0 ? "minecraft" : s.substring(0, c);
    }

    @SuppressWarnings("unchecked")
    private static void countBlocks(Map<String, Object> chunk, Set<String> namespaces, Map<String, Usage> out) {
        // 1.18+: "sections" and "block_entities" at the top (older "Level" wrapper too)
        Map<String, Object> root = chunk.get("Level") instanceof Map<?, ?> level ? (Map<String, Object>) level : chunk;
        if (root.get("sections") instanceof List<?> sections) {
            for (Object o : sections) {
                if (!(o instanceof Map<?, ?> section)) continue;
                if (!(section.get("block_states") instanceof Map<?, ?> states)) continue;
                if (!(states.get("palette") instanceof List<?> palette) || palette.isEmpty()) continue;
                String[] ns = new String[palette.size()];
                String[] names = new String[palette.size()];
                boolean any = false;
                for (int i = 0; i < palette.size(); i++) {
                    Object name = palette.get(i) instanceof Map<?, ?> m ? m.get("Name") : null;
                    ns[i] = namespace(name);
                    names[i] = name instanceof String n && n.indexOf(':') > 0 ? n : null;
                    if (ns[i] != null && namespaces.contains(ns[i])) any = true;
                }
                if (!any) continue; // most sections: nothing from these mods
                if (palette.size() == 1) {
                    add(out, ns[0], 4096, 0, 0);
                    if (names[0] != null) block(names[0], 4096);
                    continue;
                }
                if (!(states.get("data") instanceof long[] data)) continue;
                int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
                int perLong = 64 / bits;
                long mask = (1L << bits) - 1;
                Map<String, Long> counts = new HashMap<>();
                for (int i = 0; i < 4096; i++) {
                    int li = i / perLong;
                    if (li >= data.length) break;
                    int idx = (int) ((data[li] >>> ((i % perLong) * bits)) & mask);
                    if (idx < ns.length && ns[idx] != null && namespaces.contains(ns[idx])) {
                        counts.merge(ns[idx], 1L, Long::sum);
                        if (names[idx] != null) block(names[idx], 1);
                    }
                }
                counts.forEach((n, c) -> add(out, n, c, 0, 0));
            }
        }
        if (root.get("block_entities") instanceof List<?> bes) {
            for (Object o : bes) {
                String ns = o instanceof Map<?, ?> m ? namespace(m.get("id")) : null;
                if (ns != null && namespaces.contains(ns)) add(out, ns, 0, 1, 0);
            }
        }
    }

    private static void countEntities(Map<String, Object> chunk, Set<String> namespaces, Map<String, Usage> out) {
        if (!(chunk.get("Entities") instanceof List<?> list)) return;
        for (Object o : list) {
            String ns = o instanceof Map<?, ?> m ? namespace(m.get("id")) : null;
            if (ns != null && namespaces.contains(ns)) add(out, ns, 0, 0, 1);
        }
    }

    /** Every chunk stored in a region file, as NBT maps. Chunks in a format we can't read are skipped. */
    static List<Map<String, Object>> chunks(Path region) throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        try (RandomAccessFile f = new RandomAccessFile(region.toFile(), "r")) {
            if (f.length() < 8192) return out;
            byte[] header = new byte[4096];
            f.readFully(header);
            for (int i = 0; i < 1024; i++) {
                int offset = ((header[i * 4] & 0xFF) << 16) | ((header[i * 4 + 1] & 0xFF) << 8) | (header[i * 4 + 2] & 0xFF);
                if (offset == 0) continue;
                long pos = (long) offset * 4096;
                if (pos + 5 > f.length()) continue;
                f.seek(pos);
                int length = f.readInt();
                int type = f.readUnsignedByte();
                if (length <= 1 || pos + 4 + length > f.length() || (type & 0x80) != 0) continue; // .mcc outside: skip
                byte[] data = new byte[length - 1];
                f.readFully(data);
                InputStream raw = new ByteArrayInputStream(data);
                InputStream in = switch (type) {
                    case 1 -> new GZIPInputStream(raw);
                    case 2 -> new InflaterInputStream(raw);
                    case 3 -> raw;
                    default -> null; // LZ4 (1.20.5+ option): not read
                };
                if (in == null) continue;
                try (DataInputStream nbt = new DataInputStream(in)) {
                    Object root = Nbt.readRoot(nbt);
                    if (root instanceof Map<?, ?>) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> m = (Map<String, Object>) root;
                        out.add(m);
                    }
                } catch (IOException | RuntimeException ignored) {
                    // a damaged chunk: skip it
                }
            }
        }
        return out;
    }

    /** Just enough NBT: compounds, lists, strings and long arrays; numbers and other arrays are skipped. */
    static final class Nbt {
        private Nbt() {}

        /** Skips exactly {@code n} bytes (a compressed stream may skip fewer when asked once). */
        private static void skip(DataInputStream in, int n) throws IOException {
            if (n < 0) throw new IOException("bad NBT length");
            while (n > 0) {
                int s = in.skipBytes(n);
                if (s <= 0) {
                    in.readByte(); // throws at the end of the stream
                    s = 1;
                }
                n -= s;
            }
        }

        static Object readRoot(DataInputStream in) throws IOException {
            int type = in.readUnsignedByte();
            if (type != 10) return null;
            skip(in, in.readUnsignedShort()); // root name
            return read(in, 10);
        }

        private static Object read(DataInputStream in, int type) throws IOException {
            switch (type) {
                case 1 -> skip(in, 1);
                case 2 -> skip(in, 2);
                case 3, 5 -> skip(in, 4);
                case 4, 6 -> skip(in, 8);
                case 7 -> skip(in, in.readInt());
                case 8 -> {
                    byte[] b = new byte[in.readUnsignedShort()];
                    in.readFully(b);
                    return new String(b, StandardCharsets.UTF_8);
                }
                case 9 -> {
                    int t = in.readUnsignedByte();
                    int n = in.readInt();
                    List<Object> list = new ArrayList<>(Math.max(0, Math.min(n, 4096)));
                    for (int i = 0; i < n; i++) list.add(read(in, t));
                    return list;
                }
                case 10 -> {
                    Map<String, Object> map = new HashMap<>();
                    while (true) {
                        int t = in.readUnsignedByte();
                        if (t == 0) return map;
                        byte[] name = new byte[in.readUnsignedShort()];
                        in.readFully(name);
                        Object v = read(in, t);
                        if (v != null) map.put(new String(name, StandardCharsets.UTF_8), v);
                    }
                }
                case 11 -> skip(in, in.readInt() * 4);
                case 12 -> {
                    long[] a = new long[in.readInt()];
                    for (int i = 0; i < a.length; i++) a[i] = in.readLong();
                    return a;
                }
                default -> throw new IOException("bad NBT tag " + type);
            }
            return null;
        }
    }
}
