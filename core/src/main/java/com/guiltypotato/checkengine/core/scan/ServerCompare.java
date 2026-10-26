package com.guiltypotato.checkengine.core.scan;

import com.guiltypotato.checkengine.core.model.ModInfo;
import com.guiltypotato.checkengine.core.model.ModJar;
import com.guiltypotato.checkengine.core.model.Side;
import com.guiltypotato.checkengine.core.scan.Finding.Severity;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Compares a player's pack with a server (any host, or one on your own PC) and explains what will stop players
 * from joining: mods only one side has, different versions of the same mod, different Minecraft/NeoForge.
 */
public final class ServerCompare {
    public static final String MISSING_ON_PLAYER = "missing-on-player";
    public static final String MISSING_ON_SERVER = "missing-on-server";
    public static final String VERSION_MISMATCH = "version-mismatch";
    public static final String PLATFORM_MISMATCH = "platform-mismatch";

    private ServerCompare() {}

    /** One side: its mods by id, and its Minecraft/NeoForge versions if we could tell. */
    record SideInfo(Map<String, Installed> mods, String minecraft, String neoforge) {}

    /** @param bundled only present inside another jar (a library), never as its own jar */
    record Installed(ModInfo mod, String file, boolean bundled) {}

    /**
     * @param pack   the player's pack or instance folder
     * @param server a server folder, or a server pack .zip
     */
    public static List<Finding> compare(Path pack, Path server) throws IOException {
        PackFolder p = PackFolder.locate(pack, Side.CLIENT);
        SideInfo player = read(p.modsFolder(), p.options().minecraftVersion(), p.options().neoforgeVersion());
        SideInfo host = Files.isRegularFile(server) && server.toString().toLowerCase(Locale.ROOT).endsWith(".zip")
                ? readZip(server) : readServerFolder(server);
        return compare(player, host, p.options().clientOnlyFiles());
    }

    static List<Finding> compare(SideInfo player, SideInfo host, java.util.Set<String> clientOnlyFiles) {
        List<Finding> out = new ArrayList<>();
        if (player.minecraft() != null && host.minecraft() != null && !player.minecraft().equals(host.minecraft())) {
            out.add(new Finding(Severity.ERROR, PLATFORM_MISMATCH, "Different Minecraft versions",
                    "The pack is Minecraft " + player.minecraft() + " but the server is " + host.minecraft()
                            + ". Players can't join.",
                    "Set the server to Minecraft " + player.minecraft() + " (or use the matching pack version).",
                    List.of()));
        }
        if (player.neoforge() != null && host.neoforge() != null && !player.neoforge().equals(host.neoforge())) {
            out.add(new Finding(Severity.WARNING, PLATFORM_MISMATCH, "Different NeoForge versions",
                    "The pack uses NeoForge " + player.neoforge() + " and the server uses " + host.neoforge()
                            + ". This usually still works, but some mods need an exact match.",
                    "Use the same NeoForge version on both if players get kicked.", List.of()));
        }

        host.mods().forEach((id, s) -> {
            Installed c = player.mods().get(id);
            if (c == null) {
                if (s.mod().optionalOnOtherSide()) return; // server-side mod (e.g. a backup or perms mod)
                out.add(new Finding(Severity.ERROR, MISSING_ON_PLAYER, "Players don't have: " + s.mod().name(),
                        "The server runs " + s.mod().name() + " " + s.mod().version() + ", but the pack doesn't "
                                + "have it. Players will be kicked with a \"mod mismatch\" or \"missing mods\" "
                                + "error.",
                        "Add " + s.mod().name() + " to the pack, or remove it from the server.", List.of(s.file())));
            } else if (!sameVersion(c, s) && !s.mod().optionalOnOtherSide() && !c.mod().optionalOnOtherSide()
                    && !(c.bundled() && s.bundled())
                    && ClientOnlyMods.reason(c.mod(), c.file(), clientOnlyFiles) == null) {
                out.add(new Finding(Severity.WARNING, VERSION_MISMATCH, "Different versions: " + s.mod().name(),
                        "The pack has " + c.mod().version() + " but the server has " + s.mod().version()
                                + ". Players may get kicked with a \"mod mismatch\" error.",
                        "Use the same version on both (" + c.file() + " / " + s.file() + ").",
                        List.of(c.file(), s.file())));
            }
        });

        player.mods().forEach((id, c) -> {
            if (host.mods().containsKey(id) || c.mod().optionalOnOtherSide()) return;
            if (ClientOnlyMods.reason(c.mod(), c.file(), clientOnlyFiles) != null) return; // belongs on players only
            out.add(new Finding(Severity.ERROR, MISSING_ON_SERVER, "Server doesn't have: " + c.mod().name(),
                    "The pack has " + c.mod().name() + " " + c.mod().version() + ", but the server doesn't. "
                            + "Players will usually be kicked, or its blocks and items won't work.",
                    "Add " + c.file() + " to the server's mods folder (Check Engine's serverpack command copies "
                            + "everything that belongs there).", List.of(c.file())));
        });
        out.sort(Comparator.comparing(Finding::severity).thenComparing(Finding::title));
        return out;
    }

    private static void keepNewest(Map<String, Installed> mods, Installed candidate) {
        Installed old = mods.get(candidate.mod().modId());
        if (old == null || com.guiltypotato.checkengine.core.version.ModVersion.parse(candidate.mod().version())
                .compareTo(com.guiltypotato.checkengine.core.version.ModVersion.parse(old.mod().version())) > 0) {
            mods.put(candidate.mod().modId(), candidate);
        }
    }

    private static boolean sameVersion(Installed a, Installed b) {
        return a.mod().version().equals(b.mod().version()) || a.file().equals(b.file());
    }

    /** Like NeoForge: a mod's own jar wins over bundled copies, and the newest copy wins among equals. */
    static SideInfo read(Path modsFolder, String mc, String neo) throws IOException {
        List<ModJar> jars;
        try (Stream<Path> s = Files.list(modsFolder)) {
            jars = s.filter(f -> f.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .sorted().map(JarReader::read).toList();
        }
        return fromJars(jars, mc, neo);
    }

    static SideInfo fromJars(List<ModJar> jars, String mc, String neo) {
        Map<String, Installed> mods = new LinkedHashMap<>();
        for (ModJar j : jars) for (ModInfo m : j.mods()) keepNewest(mods, new Installed(m, j.fileName(), false));
        for (ModJar j : jars) {
            for (ModInfo m : j.nestedMods()) {
                Installed old = mods.get(m.modId());
                if (old == null || old.bundled()) keepNewest(mods, new Installed(m, j.fileName(), true));
            }
        }
        mods.remove("minecraft");
        mods.remove("neoforge");
        return new SideInfo(mods, mc, neo);
    }

    /** A server folder: mods/ plus NeoForge's libraries/net/neoforged/neoforge/<version>/ for the versions. */
    static SideInfo readServerFolder(Path server) throws IOException {
        Path root = server.toAbsolutePath().normalize();
        if (root.getFileName() != null && root.getFileName().toString().equalsIgnoreCase("mods")) root = root.getParent();
        Path mods = root.resolve("mods");
        if (!Files.isDirectory(mods)) throw new IOException("No mods folder in " + root);
        String neo = null;
        Path libs = root.resolve("libraries/net/neoforged/neoforge");
        if (Files.isDirectory(libs)) {
            try (Stream<Path> s = Files.list(libs)) {
                neo = s.map(p -> p.getFileName().toString()).max(Comparator.naturalOrder()).orElse(null);
            }
        }
        return read(mods, minecraftFor(neo), neo);
    }

    /** A server pack zip: reads its mods/*.jar straight from the zip, without unpacking anything. */
    static SideInfo readZip(Path zip) throws IOException {
        String neo = null;
        List<ModJar> jars = new ArrayList<>();
        try (ZipFile z = new ZipFile(zip.toFile())) {
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String name = e.getName().replace('\\', '/');
                int lib = name.indexOf("libraries/net/neoforged/neoforge/");
                if (lib >= 0) {
                    String rest = name.substring(lib + "libraries/net/neoforged/neoforge/".length());
                    if (rest.contains("/")) neo = rest.substring(0, rest.indexOf('/'));
                }
                // mods/x.jar at any depth (zips often have one top folder), but not jars nested deeper in mods/.
                int m = name.lastIndexOf("mods/");
                if (e.isDirectory() || m < 0 || !name.endsWith(".jar") || name.indexOf('/', m + 5) >= 0) continue;
                try (InputStream in = z.getInputStream(e)) {
                    jars.add(JarReader.read(Path.of(name.substring(m + 5)), in.readAllBytes()));
                }
            }
        }
        return fromJars(jars, minecraftFor(neo), neo);
    }
    /** NeoForge 21.1.x is Minecraft 1.21.1, 21.0.x is 1.21, 20.4.x is 1.20.4. */
    static String minecraftFor(String neoforge) {
        if (neoforge == null) return null;
        String[] p = neoforge.split("\\.");
        if (p.length < 2 || !p[0].matches("\\d+") || !p[1].matches("\\d+")) return null;
        return "1." + p[0] + (p[1].equals("0") ? "" : "." + p[1]);
    }
}
