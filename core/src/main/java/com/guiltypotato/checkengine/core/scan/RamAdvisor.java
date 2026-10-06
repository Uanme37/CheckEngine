package com.guiltypotato.checkengine.core.scan;

import com.guiltypotato.checkengine.core.json.Json;
import com.guiltypotato.checkengine.core.scan.Finding.Severity;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * How much memory (RAM) a pack needs, compared with what it gets and what the PC has.
 * Uses the real number from the last launch when the Check Engine mod measured one, otherwise a size-based estimate.
 */
public final class RamAdvisor {
    public static final String CODE = "memory";
    /** Written by the mod after loading: what the pack really used. */
    public static final String MEASURED_FILE = "ram.properties";

    private static final Pattern XMX = Pattern.compile("-Xmx(\\d+)([kKmMgG]?)");

    /** Mods that need a lot of extra memory, and roughly how much (MB). */
    static final Map<String, Integer> HEAVY = Map.of(
            "distanthorizons", 2048, // keeps far-away terrain in memory
            "iris", 1024, "oculus", 1024, // shaders
            "voxy", 2048);

    private RamAdvisor() {}

    /**
     * Everything we know about memory for one pack.
     *
     * @param modCount    jars in the mods folder
     * @param heavy       heavy mods found (ids)
     * @param allocatedMb what the game gets (-Xmx), or 0 if unknown
     * @param allocatedFrom where that number came from, for the report
     * @param measuredMb  memory really used after loading on the last launch, or 0
     * @param systemMb    the PC's total RAM, or 0 if unknown
     */
    public record Facts(int modCount, List<String> heavy, long allocatedMb, String allocatedFrom, long measuredMb,
                        long systemMb) {}

    /** Recommended allocation in MB, from the real measurement when there is one. */
    public static long recommendedMb(Facts f) {
        if (f.measuredMb() > 0) {
            // Room for the world, chunks and players on top of what loading used, rounded up to a whole GB.
            return roundUpGb(Math.max(f.measuredMb() * 2, f.measuredMb() + 3072));
        }
        long base = f.modCount() <= 50 ? 4096 : f.modCount() <= 150 ? 6144 : f.modCount() <= 250 ? 8192
                : f.modCount() <= 400 ? 10240 : 12288;
        for (String id : f.heavy()) base += HEAVY.getOrDefault(id, 0);
        return base;
    }

    public static List<Finding> advise(Facts f) {
        return advise(f, false);
    }

    /** @param server true for a dedicated server folder, where memory is set in its start script, not CurseForge */
    public static List<Finding> advise(Facts f, boolean server) {
        String where = server
                ? "On the server: set -Xmx in user_jvm_args.txt (or your host's memory setting)."
                : "In CurseForge: right-click the pack > Profile Options > Allocated Memory.";
        List<Finding> out = new ArrayList<>();
        long rec = recommendedMb(f);
        String basis = f.measuredMb() > 0
                ? "On its last launch this pack used " + gb(f.measuredMb()) + " right after loading."
                : "Estimated from its " + f.modCount() + " mods" + (f.heavy().isEmpty() ? ""
                        : " (including memory-hungry " + String.join(", ", f.heavy()) + ")")
                        + ". Launch it once with the Check Engine mod installed for a real measurement.";

        if (f.allocatedMb() <= 0) {
            out.add(new Finding(Severity.INFO, CODE, "Memory: give it about " + gb(rec), basis
                    + "\nCheck Engine couldn't tell how much memory the launcher gives it.",
                    where, List.of()));
            return out;
        }
        String has = "It gets " + gb(f.allocatedMb()) + " (" + f.allocatedFrom() + ")"
                + (f.systemMb() > 0 ? " and this PC has " + gb(f.systemMb()) + "." : ".");
        if (f.allocatedMb() < rec * 85 / 100) {
            out.add(new Finding(Severity.WARNING, CODE, "Memory: too little (" + gb(f.allocatedMb()) + ", needs about "
                    + gb(rec) + ")", basis + "\n" + has + " Too little memory means stutters, freezes and "
                    + "\"OutOfMemoryError\" crashes.",
                    "Raise it to " + gb(rec) + ". " + (server ? where : "In CurseForge: right-click the pack > Profile "
                            + "Options > Allocated Memory (or Settings > Minecraft for all packs)."), List.of()));
        } else if (f.systemMb() > 0 && f.allocatedMb() > f.systemMb() * 3 / 4) {
            out.add(new Finding(Severity.WARNING, CODE, "Memory: leaves too little for Windows",
                    has + " Windows, Discord and your browser need the rest, or everything starts swapping and lags.",
                    "Lower it to about " + gb(Math.max(rec, f.systemMb() / 2)) + ".", List.of()));
        } else if (f.allocatedMb() > rec + 6144) {
            out.add(new Finding(Severity.INFO, CODE, "Memory: more than it needs (" + gb(f.allocatedMb()) + ")",
                    basis + "\n" + has + " That's fine, but much more than needed can make Java's cleanup pauses "
                            + "longer (small hitches).",
                    "About " + gb(rec) + " is plenty.", List.of()));
        } else {
            out.add(new Finding(Severity.INFO, CODE, "Memory: looks right (" + gb(f.allocatedMb()) + ")",
                    basis + "\n" + has + " About " + gb(rec) + " is recommended.", null, List.of()));
        }
        return out;
    }

    /** Collects the facts for a pack folder from files on disk. */
    public static Facts facts(Path root, Path modsFolder) {
        int mods = 0;
        List<String> heavy = new ArrayList<>();
        try (Stream<Path> s = Files.list(modsFolder)) {
            for (Path p : s.toList()) {
                String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!n.endsWith(".jar")) continue;
                mods++;
                for (String id : HEAVY.keySet()) {
                    // "iris-neoforge-..." yes, "irissearch-..." no; "DistantHorizons-..." yes.
                    if (n.startsWith(id + "-") || n.startsWith(id + "_")) {
                        if (!heavy.contains(id)) heavy.add(id);
                    }
                }
            }
        } catch (IOException ignored) {
            // no mods folder: estimate with 0
        }
        heavy.sort(Comparator.naturalOrder());

        long measured = 0;
        long allocated = 0;
        String from = null;
        Path measuredFile = root.resolve("checkengine").resolve(MEASURED_FILE);
        if (Files.isRegularFile(measuredFile)) {
            Properties p = new Properties();
            try (var in = Files.newBufferedReader(measuredFile, StandardCharsets.UTF_8)) {
                p.load(in);
                measured = Long.parseLong(p.getProperty("used_after_load_mb", "0"));
                allocated = Long.parseLong(p.getProperty("max_mb", "0"));
                if (allocated > 0) from = "measured on the last launch";
            } catch (IOException | NumberFormatException ignored) {
                // ignore a broken file
            }
        }
        long override = curseForgeOverride(root);
        if (override > 0) {
            allocated = override;
            from = "this pack's CurseForge setting";
        } else if (allocated <= 0) {
            long fromCrash = fromNewestCrashReport(root);
            if (fromCrash > 0) {
                allocated = fromCrash;
                from = "from its last crash report";
            }
        }
        return new Facts(mods, List.copyOf(heavy), allocated, from, measured, systemMb());
    }

    /** CurseForge's per-pack memory setting, when the player changed it for this pack. */
    private static long curseForgeOverride(Path root) {
        Path cf = root.resolve("minecraftinstance.json");
        if (!Files.isRegularFile(cf)) return 0;
        try {
            Map<String, Object> o = Json.parseObject(Files.readString(cf, StandardCharsets.UTF_8));
            if (Boolean.TRUE.equals(o.get("isMemoryOverride")) && o.get("allocatedMemory") instanceof Double d) {
                return d.longValue();
            }
            if (o.get("javaArgsOverride") instanceof String args) return xmx(args);
        } catch (IOException | RuntimeException ignored) {
            // unreadable: unknown
        }
        return 0;
    }

    private static long fromNewestCrashReport(Path root) {
        Path dir = root.resolve("crash-reports");
        if (!Files.isDirectory(dir)) return 0;
        try (Stream<Path> s = Files.list(dir)) {
            Path newest = s.filter(p -> p.getFileName().toString().endsWith(".txt"))
                    .max(Comparator.comparing(p -> p.toFile().lastModified())).orElse(null);
            return newest == null ? 0 : xmx(Files.readString(newest, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    /** "-Xmx12544m" -> 12544, "-Xmx8G" -> 8192. */
    static long xmx(String text) {
        Matcher m = XMX.matcher(text);
        if (!m.find()) return 0;
        long n = Long.parseLong(m.group(1));
        return switch (m.group(2).toLowerCase(Locale.ROOT)) {
            case "g" -> n * 1024;
            case "k" -> n / 1024;
            case "" -> n / (1024 * 1024);
            default -> n;
        };
    }

    private static long systemMb() {
        try {
            if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
                return os.getTotalMemorySize() / (1024 * 1024);
            }
        } catch (RuntimeException | LinkageError ignored) {
            // not available on this Java
        }
        return 0;
    }

    private static long roundUpGb(long mb) {
        return (mb + 1023) / 1024 * 1024;
    }

    /** 12544 -> "12.3 GB", 8192 -> "8 GB". */
    static String gb(long mb) {
        double g = mb / 1024.0;
        return (g == Math.floor(g) ? String.valueOf((long) g) : String.format(Locale.ROOT, "%.1f", g)) + " GB";
    }
}
