package com.guiltypotato.checkengine.core.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.DeflaterOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Counts a mod's blocks, machines and mobs straight from a region file. */
class WorldUsageTest {
    @TempDir
    Path world;

    private static void name(DataOutputStream o, int type, String name) throws Exception {
        o.writeByte(type);
        byte[] b = name.getBytes(StandardCharsets.UTF_8);
        o.writeShort(b.length);
        o.write(b);
    }

    private static void str(DataOutputStream o, String s) throws Exception {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        o.writeShort(b.length);
        o.write(b);
    }

    /** One chunk: a section of stone with 10 create:shaft blocks, 2 Create block entities, 1 vanilla one. */
    private static byte[] chunk() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(bytes);
        name(o, 10, "");
        name(o, 3, "DataVersion");
        o.writeInt(3955);
        name(o, 9, "sections");
        o.writeByte(10);
        o.writeInt(1);
        name(o, 10, "block_states");
        name(o, 9, "palette");
        o.writeByte(10);
        o.writeInt(2);
        name(o, 8, "Name");
        str(o, "minecraft:stone");
        o.writeByte(0);
        name(o, 8, "Name");
        str(o, "create:shaft");
        name(o, 10, "Properties"); // skipped part
        name(o, 8, "axis");
        str(o, "y");
        o.writeByte(0);
        o.writeByte(0);
        name(o, 12, "data"); // 4 bits each, 16 per long: the first 10 blocks are index 1
        o.writeInt(256);
        for (int i = 0; i < 256; i++) o.writeLong(i == 0 ? 0x1111111111L : 0L);
        o.writeByte(0); // end block_states
        o.writeByte(0); // end section
        name(o, 9, "block_entities");
        o.writeByte(10);
        o.writeInt(3);
        for (String id : List.of("create:basin", "create:depot", "minecraft:chest")) {
            name(o, 8, "id");
            str(o, id);
            o.writeByte(0);
        }
        o.writeByte(0); // end root
        return bytes.toByteArray();
    }

    @Test
    void countsBlocksAndMachinesOfAMod() throws Exception {
        ByteArrayOutputStream zipped = new ByteArrayOutputStream();
        try (DeflaterOutputStream z = new DeflaterOutputStream(zipped)) {
            z.write(chunk());
        }
        byte[] data = zipped.toByteArray();
        ByteArrayOutputStream region = new ByteArrayOutputStream();
        DataOutputStream r = new DataOutputStream(region);
        r.writeInt((2 << 8) | 1); // chunk 0: sector 2, 1 sector long
        r.write(new byte[8192 - 4]);
        r.writeInt(data.length + 1);
        r.writeByte(2); // zlib
        r.write(data);
        r.write(new byte[4096 - ((data.length + 5) % 4096)]);
        Path file = world.resolve("region").resolve("r.0.0.mca");
        Files.createDirectories(file.getParent());
        Files.write(file, region.toByteArray());

        Map<String, WorldUsage.Usage> usage = WorldUsage.count(world, Set.of("create"));
        WorldUsage.Usage create = usage.get("create");
        assertEquals(10, create.blocks());
        assertEquals(2, create.blockEntities());
        assertEquals(List.of("shaft"), create.topBlocks());
        assertTrue(SafeRemove.describe(create).startsWith("10 blocks (2 chests or machines), mostly shaft"),
                SafeRemove.describe(create));
    }
}
