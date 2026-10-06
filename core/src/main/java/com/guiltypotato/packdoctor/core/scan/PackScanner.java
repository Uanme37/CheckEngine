package com.guiltypotato.packdoctor.core.scan;

import com.guiltypotato.packdoctor.core.model.Dependency;
import com.guiltypotato.packdoctor.core.model.ModInfo;
import com.guiltypotato.packdoctor.core.model.ModJar;
import com.guiltypotato.packdoctor.core.model.Side;
import com.guiltypotato.packdoctor.core.scan.Finding.Severity;
import com.guiltypotato.packdoctor.core.toml.Toml;
import com.guiltypotato.packdoctor.core.version.ModVersion;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Checks a mods folder: broken or wrong-loader jars, duplicates, missing or wrong-version dependencies,
 * incompatible mods, and client-only mods on a server.
 */
public final class PackScanner {
    /** Sinytra Connector lets NeoForge load Fabric mods. */
    private static final String CONNECTOR = "connector";

    private PackScanner() {}

    public static Report scan(Path modsFolder, ScanOptions options) throws IOException {
        List<Path> jarFiles = new ArrayList<>();
        List<String> strayZips = new ArrayList<>();
        try (Stream<Path> files = Files.list(modsFolder)) {
            for (Path p : files.sorted().toList()) {
                if (!Files.isRegularFile(p)) continue;
                String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                if (name.endsWith(".jar")) jarFiles.add(p);
                else if (name.endsWith(".zip")) strayZips.add(p.getFileName().toString());
            }
        }
        boolean legacy = options.legacy();
        List<ModJar> jars = jarFiles.stream().map(p -> JarReader.read(p, legacy)).toList();
        return check(modsFolder, jars, strayZips, options);
    }

    /** Runs the checks on jars that were already read. The in-game side can call this with its own list. */
    public static Report check(Path modsFolder, List<ModJar> jars, List<String> strayZips, ScanOptions options) {
        List<Finding> findings = new ArrayList<>();
        String loader = options.loaderName();
        if (options.loader() == ScanOptions.Loader.FABRIC) {
            findings.add(new Finding(Severity.INFO, "unsupported-loader", "Fabric packs aren't supported yet",
                    "Pack Doctor checks NeoForge and Forge packs. This one uses Fabric, so it wasn't checked.",
                    null, List.of()));
            return new Report(modsFolder, options, jars, findings);
        }
        if (olderThan(options, "1.20")) {
            findings.add(new Finding(Severity.INFO, "older-minecraft", "Lighter check for Minecraft "
                    + options.minecraftVersion(), "Pack Doctor is built for 1.20.1 and 1.21.1. On older packs it "
                    + "checks missing mods, duplicates and broken files, but skips " + loader + " version rules.",
                    null, List.of()));
        }
        if (jars.isEmpty()) {
            findings.add(new Finding(Severity.WARNING, Finding.NO_MODS, "No mods found",
                    "There are no .jar files in " + modsFolder + ", so there was nothing to check.",
                    "Make sure you pointed Pack Doctor at the right pack, and that its mods finished downloading.",
                    List.of()));
        }

        long neoJars = jars.stream().filter(j -> j.kind() == ModJar.Kind.NEOFORGE).count();
        long forgeJars = jars.stream().filter(j -> j.kind() == ModJar.Kind.FORGE).count();
        boolean forgeTomlIsFine = options.minecraftVersion() != null || options.loader() != ScanOptions.Loader.UNKNOWN
                ? options.legacy() : forgeJars > neoJars;
        boolean hasConnector = jars.stream().anyMatch(j -> hasMod(j, CONNECTOR) || hasMod(j, "connectormod"));

        // Which jars actually get loaded, and the mods they bring.
        List<ModJar> loaded = new ArrayList<>();
        for (ModJar jar : jars) {
            switch (jar.kind()) {
                case BROKEN -> findings.add(new Finding(Severity.ERROR, Finding.BROKEN_JAR,
                        "Broken file: " + jar.fileName(),
                        "This jar can't be opened (" + jar.error() + "). It's probably a half-finished download.",
                        "Delete it and download the mod again.", List.of(jar.fileName())));
                case UNKNOWN -> findings.add(new Finding(Severity.WARNING, Finding.NOT_A_MOD,
                        "Not a mod: " + jar.fileName(),
                        "This jar has no mod info in it, so " + loader + " will ignore it or refuse to start.",
                        "Remove it unless you know a mod needs it there.", List.of(jar.fileName())));
                case FABRIC -> {
                    if (hasConnector) {
                        loaded.add(jar);
                    } else {
                        findings.add(new Finding(Severity.ERROR, Finding.WRONG_LOADER,
                                "Fabric mod in a " + loader + " pack: " + displayName(jar),
                                "This is a Fabric mod. " + loader + " can't load it on its own.",
                                "Swap it for the " + loader + " version, or remove it.", List.of(jar.fileName())));
                    }
                }
                case FORGE -> {
                    if (forgeTomlIsFine) {
                        loaded.add(jar);
                    } else {
                        findings.add(new Finding(Severity.ERROR, Finding.WRONG_LOADER,
                                "Forge mod in a NeoForge pack: " + displayName(jar),
                                "This is a Forge mod (or an old NeoForge one). NeoForge for Minecraft 1.20.5 and "
                                        + "newer won't load it.",
                                "Swap it for the NeoForge version for your Minecraft version, or remove it.",
                                List.of(jar.fileName())));
                    }
                }
                case NEOFORGE -> {
                    if (!options.legacy()) {
                        loaded.add(jar);
                    } else {
                        findings.add(new Finding(Severity.ERROR, Finding.WRONG_LOADER,
                                "Mod for a newer Minecraft: " + displayName(jar),
                                "This is a NeoForge mod for Minecraft 1.20.5 or newer. This pack is "
                                        + (options.minecraftVersion() != null ? "Minecraft " + options.minecraftVersion()
                                        : "older") + " " + loader + ", which can't load it.",
                                "Swap it for the " + loader + " " + (options.minecraftVersion() != null
                                        ? options.minecraftVersion() + " " : "") + "version, or remove it.",
                                List.of(jar.fileName())));
                    }
                }
                case LIBRARY -> loaded.add(jar); // declares no mods itself, but may bundle some
            }
            if (jar.error() != null && jar.kind() != ModJar.Kind.BROKEN) {
                findings.add(new Finding(Severity.WARNING, Finding.UNREADABLE_METADATA,
                        "Can't read mod info: " + jar.fileName(),
                        "Pack Doctor couldn't read this mod's info file (" + jar.error() + "), so it wasn't checked. "
                                + loader + " may refuse to load it too.",
                        "Check for an updated version of the mod.", List.of(jar.fileName())));
            }
        }
        for (String zip : strayZips) {
            findings.add(new Finding(Severity.WARNING, Finding.STRAY_FILE, "Zip file in mods folder: " + zip,
                    "Mods are .jar files. A .zip here is usually a resource pack, shader or modpack put in the "
                            + "wrong folder, and it won't load.",
                    "Move it to resourcepacks/ or shaderpacks/, or remove it.", List.of(zip)));
        }

        // modId -> every top-level jar that declares it
        Map<String, List<Provider>> topLevel = new LinkedHashMap<>();
        Map<String, ModInfo> nested = new LinkedHashMap<>();
        Map<String, Provider> nestedIn = new LinkedHashMap<>(); // bundled mod id -> the newest copy and its outer jar
        for (ModJar jar : loaded) {
            for (ModInfo mod : jar.mods()) {
                List<Provider> list = topLevel.computeIfAbsent(mod.modId(), k -> new ArrayList<>());
                if (list.stream().noneMatch(p -> p.jar == jar)) list.add(new Provider(mod, jar));
            }
            for (ModInfo mod : jar.nestedMods()) {
                ModInfo old = nested.get(mod.modId());
                if (old == null || ModVersion.parse(mod.version()).compareTo(ModVersion.parse(old.version())) > 0) {
                    nested.put(mod.modId(), mod);
                    nestedIn.put(mod.modId(), new Provider(mod, jar));
                }
            }
        }

        checkDuplicates(topLevel, loader, findings);
        // NeoForge loads bundled (jar-in-jar) mods too and checks their dependencies, unless a top-level jar wins.
        List<Provider> dependents = new ArrayList<>();
        topLevel.values().forEach(dependents::addAll);
        nestedIn.forEach((id, p) -> {
            if (!topLevel.containsKey(id)) dependents.add(p);
        });
        checkDependencies(dependents, topLevel, nested, options, dependencyOverrides(modsFolder, options), findings);
        java.util.Set<String> installed = new java.util.HashSet<>(topLevel.keySet());
        installed.addAll(nested.keySet());
        findings.addAll(HiddenDependencies.check(loaded, installed));
        if (options.side() == Side.SERVER) checkClientOnly(topLevel, options, findings);

        // Two copies of one mod would report its dependency problems twice.
        List<Finding> unique = new ArrayList<>(new java.util.LinkedHashSet<>(findings));
        unique.sort(Comparator.comparing(Finding::severity).thenComparing(Finding::code).thenComparing(Finding::title));
        return new Report(modsFolder, options, jars, List.copyOf(unique));
    }

    private record Provider(ModInfo mod, ModJar jar) {}

    private static void checkDuplicates(Map<String, List<Provider>> topLevel, String loader, List<Finding> findings) {
        for (Map.Entry<String, List<Provider>> e : topLevel.entrySet()) {
            List<Provider> providers = e.getValue();
            if (providers.size() < 2) continue;
            List<Provider> sorted = new ArrayList<>(providers);
            sorted.sort(Comparator.comparing((Provider p) -> ModVersion.parse(p.mod.version())).reversed());
            StringBuilder detail = new StringBuilder(providers.size() + " copies of " + sorted.get(0).mod.name()
                    + " are installed:");
            for (Provider p : sorted) detail.append("\n- ").append(p.jar.fileName()).append(" (version ")
                    .append(p.mod.version()).append(')');
            detail.append("\n" + loader + " only loads one copy (the newest) and ignores the rest, or refuses to start. "
                    + "If you meant to downgrade, it didn't work.");
            findings.add(new Finding(Severity.WARNING, Finding.DUPLICATE_MOD,
                    "Duplicate mod: " + sorted.get(0).mod.name(), detail.toString(),
                    "Keep " + sorted.get(0).jar.fileName() + " (the newest) and remove the others.",
                    sorted.stream().map(p -> p.jar.fileName()).toList()));
        }
    }

    /** Someone who needs a mod that's missing, grouped so one missing mod is one finding. */
    private record Need(Provider who, Dependency dep) {}

    private static void checkDependencies(List<Provider> dependents, Map<String, List<Provider>> topLevel,
                                          Map<String, ModInfo> nested, ScanOptions options,
                                          Map<String, Set<String>> overrides, List<Finding> findings) {
        Map<String, List<Need>> missing = new LinkedHashMap<>();
        for (Provider who : dependents) {
            Set<String> dropped = overrides.getOrDefault(who.mod.modId(), Set.of());
            for (Dependency dep : who.mod.dependencies()) {
                if (dep.modId().equals(who.mod.modId()) || !dep.side().appliesTo(options.side())) continue;
                if (dropped.contains(dep.modId())) continue; // the pack switched this rule off in config/fml.toml
                Installed have = installed(dep.modId(), topLevel, nested, options);
                if (have == Installed.UNKNOWN) continue;
                if (have == null) {
                    if (dep.type() == Dependency.Type.REQUIRED) {
                        missing.computeIfAbsent(dep.modId(), k -> new ArrayList<>()).add(new Need(who, dep));
                    }
                    continue;
                }
                boolean inRange = have.version.equals("unknown") || dep.versionRange().contains(have.version);
                switch (dep.type()) {
                    case REQUIRED, OPTIONAL -> {
                        // Lots of 1.21.1 mods say "[1.21,1.21.1)" by mistake. NeoForge loads them anyway.
                        boolean sloppyMinecraftRange = dep.modId().equals("minecraft")
                                && sameMinecraftLine(dep, have.version);
                        if (!inRange && !sloppyMinecraftRange && !neoForgeSupportMatrix(dep, options)) {
                            findings.add(wrongVersion(who, dep, have));
                        }
                    }
                    case INCOMPATIBLE -> {
                        if (inRange) findings.add(incompatible(who, dep, have, Severity.ERROR));
                    }
                    case DISCOURAGED -> {
                        if (inRange) findings.add(incompatible(who, dep, have, Severity.WARNING));
                    }
                }
            }
        }
        for (Map.Entry<String, List<Need>> e : missing.entrySet()) {
            String depId = e.getKey();
            List<Need> needs = e.getValue();
            StringBuilder detail = new StringBuilder();
            if (needs.size() == 1) {
                Need n = needs.get(0);
                detail.append(n.who.mod.name()).append(" needs ").append(depId).append(" (")
                        .append(n.dep.versionRange().describe()).append("), but it isn't installed.");
            } else {
                detail.append(needs.size()).append(" mods need ").append(depId).append(", but it isn't installed:");
                for (Need n : needs) detail.append("\n- ").append(n.who.mod.name()).append(" (wants ")
                        .append(n.dep.versionRange().describe()).append(')');
            }
            appendReasons(detail, needs.stream().map(Need::dep).toList());
            findings.add(new Finding(Severity.ERROR, Finding.MISSING_DEPENDENCY, "Missing mod: " + depId,
                    detail.toString(),
                    needs.size() == 1
                            ? "Install " + depId + ", or remove " + needs.get(0).who.mod.name() + "."
                            : "Install " + depId + ", or remove the mods that need it.",
                    needs.stream().map(n -> n.who.jar.fileName()).distinct().toList()));
        }
    }

    /** What's installed under a mod id: a version, null for "not installed", or UNKNOWN for "can't tell". */
    private record Installed(String version, String name, String fileName) {
        static final Installed UNKNOWN = new Installed("?", "?", null);
    }

    private static Installed installed(String id, Map<String, List<Provider>> topLevel, Map<String, ModInfo> nested,
                                       ScanOptions options) {
        switch (id) {
            case "minecraft" -> {
                return options.minecraftVersion() == null ? Installed.UNKNOWN
                        : new Installed(options.minecraftVersion(), "Minecraft", null);
            }
            case "neoforge" -> {
                if (options.loader() == ScanOptions.Loader.FORGE) return null; // really not there on Forge
                return options.neoforgeVersion() == null ? Installed.UNKNOWN
                        : new Installed(options.neoforgeVersion(), "NeoForge", null);
            }
            case "forge" -> {
                // Before 1.20, Forge packs routinely ship mods with out-of-date Forge ranges that still load.
                return options.loader() != ScanOptions.Loader.FORGE || options.neoforgeVersion() == null
                        || olderThan(options, "1.20") ? Installed.UNKNOWN
                        : new Installed(options.neoforgeVersion(), "Forge", null);
            }
            case "javafml", "fml" -> {
                return Installed.UNKNOWN;
            }
            default -> { }
        }
        List<Provider> providers = topLevel.get(id);
        if (providers != null && !providers.isEmpty()) {
            Provider best = providers.stream()
                    .max(Comparator.comparing((Provider p) -> ModVersion.parse(p.mod.version()))).orElseThrow();
            return new Installed(best.mod.version(), best.mod.name(), best.jar.fileName());
        }
        ModInfo inner = nested.get(id);
        if (inner != null) return new Installed(inner.version(), inner.name(), null);
        return null;
    }

    /**
     * NeoForge on 1.21.1 also accepts mods made for 1.21: any NeoForge range that includes 21.0.166 passes
     * (FancyModLoader's VersionSupportMatrix). So "[21.0.0-beta,21.1.227)" still loads on 21.1.251.
     */
    private static boolean neoForgeSupportMatrix(Dependency dep, ScanOptions options) {
        return dep.modId().equals("neoforge") && options.loader() != ScanOptions.Loader.FORGE
                && "1.21.1".equals(options.minecraftVersion()) && dep.versionRange().contains("21.0.166");
    }

    /**
     * NeoForge's config/fml.toml can switch off a mod's dependency rules, e.g.
     * {@code dependencyOverrides.citresewn = ["-connector"]}. Returns mod id -> the dependency ids it drops
     * ("+dep" only changes load order, so it's ignored).
     */
    static Map<String, Set<String>> dependencyOverrides(Path modsFolder, ScanOptions options) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        if (modsFolder == null || modsFolder.getParent() == null || options.loader() == ScanOptions.Loader.FORGE) {
            return result;
        }
        Path fmlToml = modsFolder.getParent().resolve("config").resolve("fml.toml");
        if (!Files.isRegularFile(fmlToml)) return result;
        try {
            if (!(Toml.parse(Files.readString(fmlToml)).get("dependencyOverrides") instanceof Map<?, ?> table)) {
                return result;
            }
            for (Map.Entry<?, ?> e : table.entrySet()) {
                if (!(e.getValue() instanceof List<?> list)) continue;
                for (Object o : list) {
                    if (o instanceof String s && s.startsWith("-") && s.length() > 1) {
                        result.computeIfAbsent(String.valueOf(e.getKey()), k -> new HashSet<>()).add(s.substring(1));
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            // An unreadable fml.toml just means no overrides; NeoForge complains about it itself.
        }
        return result;
    }

    private static boolean olderThan(ScanOptions options, String mc) {
        return options.minecraftVersion() != null
                && ModVersion.parse(options.minecraftVersion()).compareTo(ModVersion.parse(mc)) < 0;
    }

    /** "1.21.1" -> "1.21" */
    private static String lineOf(String mc) {
        String[] parts = mc.split("\\.");
        return parts.length >= 2 ? parts[0] + "." + parts[1] : mc;
    }

    /**
     * True if the range accepts an earlier release of the pack's Minecraft line, e.g. a "[1.21]" mod on 1.21.1.
     * Never a newer one: a "[1.21.11,)" mod really won't load on 1.21.1.
     */
    private static boolean sameMinecraftLine(Dependency dep, String mc) {
        String line = lineOf(mc);
        if (dep.versionRange().contains(line)) return true;
        String[] parts = mc.split("\\.");
        int patch = parts.length >= 3 && parts[2].matches("\\d+") ? Integer.parseInt(parts[2]) : 0;
        for (int i = 0; i <= patch; i++) {
            if (dep.versionRange().contains(line + "." + i)) return true;
        }
        return false;
    }

    private static Finding wrongVersion(Provider who, Dependency dep, Installed have) {
        boolean platform = have.fileName == null && (dep.modId().equals("minecraft") || dep.modId().equals("neoforge")
                || dep.modId().equals("forge"));
        StringBuilder detail = new StringBuilder(who.mod.name() + " needs " + have.name + " "
                + dep.versionRange().describe() + ", but this pack has " + have.version + ".");
        if (dep.type() == Dependency.Type.OPTIONAL) {
            detail.append(" It's an optional extra, but if it's installed the version has to match.");
        }
        appendReasons(detail, List.of(dep));
        List<String> files = new ArrayList<>();
        files.add(who.jar.fileName());
        if (have.fileName != null) files.add(have.fileName);
        String fix = platform
                ? "Use a version of " + who.mod.name() + " made for " + have.name + " " + have.version + "."
                : "Update " + have.name + " (or use a version of " + who.mod.name() + " that matches it).";
        return new Finding(Severity.ERROR, Finding.WRONG_VERSION,
                "Wrong version: " + who.mod.name() + " needs " + have.name + " " + dep.versionRange().describe(),
                detail.toString(), fix, List.copyOf(files));
    }

    private static Finding incompatible(Provider who, Dependency dep, Installed have, Severity severity) {
        boolean hard = severity == Severity.ERROR;
        StringBuilder detail = new StringBuilder(who.mod.name()
                + (hard ? " doesn't work with " : " warns against using ") + have.name
                + (dep.versionRange().matchesAnything() ? "" : " " + dep.versionRange().describe()) + ".");
        appendReasons(detail, List.of(dep));
        List<String> files = new ArrayList<>();
        files.add(who.jar.fileName());
        if (have.fileName != null) files.add(have.fileName);
        return new Finding(severity, hard ? Finding.INCOMPATIBLE_MOD : Finding.DISCOURAGED_MOD,
                (hard ? "Incompatible mods: " : "Not recommended together: ") + who.mod.name() + " + " + have.name,
                detail.toString(), "Remove one of them" + (hard ? "." : ", or keep both if it works for you."),
                List.copyOf(files));
    }

    private static void checkClientOnly(Map<String, List<Provider>> topLevel, ScanOptions options,
                                        List<Finding> findings) {
        for (List<Provider> providers : topLevel.values()) {
            for (Provider p : providers) {
                if (p.jar.mods().get(0) != p.mod) continue; // one finding per jar, from its main mod
                String why = ClientOnlyMods.reason(p.mod, p.jar.fileName(), options.clientOnlyFiles());
                if (why == null) continue;
                findings.add(new Finding(Severity.WARNING, Finding.CLIENT_ONLY_ON_SERVER,
                        "Client-only mod on the server: " + p.mod.name(),
                        p.mod.name() + " looks client-only (" + why + "). On a server it does nothing at best "
                                + "and crashes it at worst.",
                        "Remove it from the server's mods folder. Players keep it.", List.of(p.jar.fileName())));
            }
        }
    }

    private static void appendReasons(StringBuilder detail, List<Dependency> deps) {
        deps.stream().map(Dependency::reason).filter(r -> r != null && !r.isBlank()).distinct()
                .forEach(r -> detail.append("\nThe mod says: \"").append(r.trim()).append('"'));
    }

    private static boolean hasMod(ModJar jar, String id) {
        return jar.mods().stream().anyMatch(m -> m.modId().equals(id))
                || jar.nestedMods().stream().anyMatch(m -> m.modId().equals(id));
    }

    private static String displayName(ModJar jar) {
        return jar.mods().isEmpty() ? jar.fileName() : jar.mods().get(0).name();
    }
}
