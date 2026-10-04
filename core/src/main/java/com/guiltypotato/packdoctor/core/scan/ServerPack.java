package com.guiltypotato.packdoctor.core.scan;

import com.guiltypotato.packdoctor.core.model.Dependency;
import com.guiltypotato.packdoctor.core.model.ModInfo;
import com.guiltypotato.packdoctor.core.model.ModJar;
import com.guiltypotato.packdoctor.core.model.Side;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds a server copy of a pack: every mod except client-only ones, plus config, scripts and datapacks.
 * Never touches the original pack and never overwrites an existing folder.
 */
public final class ServerPack {
    /** Pack folders a server needs. Everything else (saves, resourcepacks, shaderpacks, options.txt...) stays behind. */
    static final List<String> FOLDERS = List.of("config", "defaultconfigs", "kubejs", "scripts", "datapacks",
            "global_packs", "openloader", "paxi");

    private ServerPack() {}

    /**
     * What will go in the server pack.
     *
     * @param root       the pack/instance folder
     * @param modsFolder its mods folder
     * @param options    Minecraft/NeoForge versions we found
     * @param keep       jars that go to the server
     * @param removed    client-only jar -> why
     * @param keptAnyway client-only-looking jar -> the server mod that needs it
     */
    public record Plan(Path root, Path modsFolder, ScanOptions options, List<ModJar> keep,
                       Map<String, String> removed, Map<String, String> keptAnyway) {}

    public static Plan plan(Path pack) throws IOException {
        PackFolder folder = PackFolder.locate(pack, Side.SERVER);
        Path mods = folder.modsFolder();
        Path root = mods.getFileName().toString().equalsIgnoreCase("mods") && mods.getParent() != null
                ? mods.getParent() : mods;
        ScanOptions options = folder.options();
        List<ModJar> jars;
        try (Stream<Path> files = Files.list(folder.modsFolder())) {
            jars = files.filter(p -> p.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".jar"))
                    .sorted().map(JarReader::read).toList();
        }

        Map<ModJar, String> clientOnly = new LinkedHashMap<>();
        for (ModJar jar : jars) {
            if (jar.mods().isEmpty()) continue;
            String why = ClientOnlyMods.reason(jar.mods().get(0), jar.fileName(), options.clientOnlyFiles());
            if (why != null) clientOnly.put(jar, why);
        }

        // Don't strip a "client-only" mod that a server mod still requires (e.g. a library tagged Client on CurseForge).
        Map<String, String> keptAnyway = new LinkedHashMap<>();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (ModJar jar : jars) {
                if (clientOnly.containsKey(jar)) continue;
                for (ModInfo mod : jar.mods()) {
                    for (Dependency d : mod.dependencies()) {
                        if (d.type() != Dependency.Type.REQUIRED || !d.side().appliesTo(Side.SERVER)) continue;
                        if (providedBy(jars, d.modId(), clientOnly, false)) continue;
                        for (ModJar provider : jars) {
                            if (clientOnly.containsKey(provider) && provides(provider, d.modId())) {
                                clientOnly.remove(provider);
                                keptAnyway.put(provider.fileName(), mod.name());
                                changed = true;
                            }
                        }
                    }
                }
            }
        }

        Map<String, String> removed = new LinkedHashMap<>();
        clientOnly.forEach((jar, why) -> removed.put(jar.fileName(), jar.mods().get(0).name() + ": " + why));
        List<ModJar> keep = jars.stream().filter(j -> !clientOnly.containsKey(j)).toList();
        return new Plan(root, folder.modsFolder(), options, keep, removed, keptAnyway);
    }

    /** True if a jar that is (or, with {@code onlyRemoved}, isn't) being removed provides the mod. */
    private static boolean providedBy(List<ModJar> jars, String modId, Map<ModJar, String> removed, boolean onlyRemoved) {
        for (ModJar j : jars) {
            if (removed.containsKey(j) == onlyRemoved && provides(j, modId)) return true;
        }
        return false;
    }

    private static boolean provides(ModJar jar, String modId) {
        return jar.mods().stream().anyMatch(m -> m.modId().equals(modId))
                || jar.nestedMods().stream().anyMatch(m -> m.modId().equals(modId));
    }

    /** Copies the server pack into {@code out}, which must not exist yet (or be empty). Returns the readme text. */
    public static String write(Plan plan, Path out) throws IOException {
        if (Files.exists(out)) {
            try (Stream<Path> s = Files.list(out)) {
                if (s.findAny().isPresent()) throw new IOException(out + " already exists and isn't empty");
            }
        }
        Path mods = Files.createDirectories(out.resolve("mods"));
        for (ModJar jar : plan.keep()) Files.copy(jar.file(), mods.resolve(jar.fileName()));
        for (String name : FOLDERS) {
            Path from = plan.root().resolve(name);
            if (Files.isDirectory(from)) copyTree(from, out.resolve(name));
        }
        String readme = readme(plan);
        Files.writeString(out.resolve("SERVER-PACK-README.txt"), readme, StandardCharsets.UTF_8);
        return readme;
    }

    /** Zips a finished server pack folder into {@code zip} (must not exist). */
    public static void zip(Path folder, Path zip) throws IOException {
        if (Files.exists(zip)) throw new IOException(zip + " already exists");
        try (OutputStream os = Files.newOutputStream(zip); ZipOutputStream z = new ZipOutputStream(os);
             Stream<Path> files = Files.walk(folder)) {
            for (Path p : files.filter(Files::isRegularFile).sorted().toList()) {
                z.putNextEntry(new ZipEntry(folder.relativize(p).toString().replace('\\', '/')));
                Files.copy(p, z);
                z.closeEntry();
            }
        }
    }

    public static String readme(Plan plan) {
        StringBuilder sb = new StringBuilder("Server pack made by Pack Doctor\n");
        sb.append("From: ").append(plan.root()).append('\n');
        if (plan.options().minecraftVersion() != null) {
            sb.append("Minecraft ").append(plan.options().minecraftVersion());
            if (plan.options().neoforgeVersion() != null) {
                sb.append(", NeoForge ").append(plan.options().neoforgeVersion());
            }
            sb.append('\n');
        }
        sb.append("\nMods: ").append(plan.keep().size()).append(" kept, ").append(plan.removed().size())
                .append(" client-only removed\n");
        List<String> copied = new ArrayList<>();
        for (String name : FOLDERS) if (Files.isDirectory(plan.root().resolve(name))) copied.add(name);
        sb.append("Also copied: ").append(copied.isEmpty() ? "nothing else" : String.join(", ", copied)).append('\n');

        if (!plan.removed().isEmpty()) {
            sb.append("\nRemoved (players keep these, servers don't need them):\n");
            plan.removed().forEach((file, why) -> sb.append("- ").append(file).append("  (").append(why).append(")\n"));
        }
        if (!plan.keptAnyway().isEmpty()) {
            sb.append("\nLook client-only but kept, because a server mod needs them:\n");
            plan.keptAnyway().forEach((file, by) -> sb.append("- ").append(file).append("  (needed by ")
                    .append(by).append(")\n"));
        }
        sb.append("""

                How to use it:
                1. On your host (e.g. BisectHosting), pick NeoForge %s for Minecraft %s.
                2. Stop the server, upload everything in this folder into the server's main folder.
                3. Start it once, accept the EULA (eula.txt), start again.
                4. If a removed mod turns out to be needed, the server log will say so: copy that jar back in.
                """.formatted(plan.options().neoforgeVersion() != null ? plan.options().neoforgeVersion() : "(same as the pack)",
                plan.options().minecraftVersion() != null ? plan.options().minecraftVersion() : "(same as the pack)"));
        return sb.toString();
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> files = Files.walk(from)) {
            for (Path p : files.toList()) {
                Path target = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(target);
                else Files.copy(p, target);
            }
        }
    }
}
