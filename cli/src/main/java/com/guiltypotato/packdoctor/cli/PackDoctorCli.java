package com.guiltypotato.packdoctor.cli;

import com.guiltypotato.packdoctor.core.PackDoctorCore;
import com.guiltypotato.packdoctor.core.crash.CrashTranslator;
import com.guiltypotato.packdoctor.core.scan.Finding;
import com.guiltypotato.packdoctor.core.model.Side;
import com.guiltypotato.packdoctor.core.scan.PackFolder;
import com.guiltypotato.packdoctor.core.scan.PackScanner;
import com.guiltypotato.packdoctor.core.scan.RamAdvisor;
import com.guiltypotato.packdoctor.core.scan.Report;
import com.guiltypotato.packdoctor.core.scan.ScanOptions;
import com.guiltypotato.packdoctor.core.scan.ServerCompare;
import com.guiltypotato.packdoctor.core.scan.ServerPack;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** CLI entry point: pack scan, crash translator and server pack builder. */
public final class PackDoctorCli {
    private static final String USAGE = """
            Usage: java -jar packdoctor-cli.jar scan <pack or mods folder> [options]
                   java -jar packdoctor-cli.jar crash <crash report, or a pack folder for its newest crash>
                   java -jar packdoctor-cli.jar serverpack <pack folder> [--out <new folder>] [--zip] [--dry-run]
                   java -jar packdoctor-cli.jar compare <pack folder> <server folder or server pack .zip>

              --server          check it as a dedicated server (also flags client-only mods)
              --client          check it as a player's game (default)
              --mc <version>    Minecraft version, e.g. 1.21.1 (read from CurseForge instances automatically)
              --neoforge <ver>  NeoForge version, e.g. 21.1.251 (same)
              --out <file>      also save the report to a text file

            Exit code: 0 = no problems, 1 = problems found, 2 = couldn't run.
            """;

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        if (args.length == 0 || args[0].equals("--help") || args[0].equals("-h")) {
            System.out.print(USAGE);
            return args.length == 0 ? 2 : 0;
        }
        if (args[0].equals("crash")) return crash(args);
        if (args[0].equals("serverpack")) return serverPack(args);
        if (args[0].equals("compare")) return compare(args);
        int i = 0;
        if (args[0].equals("scan")) i++;
        Path folder = null;
        Side side = Side.CLIENT;
        String mc = null;
        String neo = null;
        Path out = null;
        try {
            for (; i < args.length; i++) {
                switch (args[i]) {
                    case "--server" -> side = Side.SERVER;
                    case "--client" -> side = Side.CLIENT;
                    case "--mc" -> mc = args[++i];
                    case "--neoforge" -> neo = args[++i];
                    case "--out" -> out = Path.of(args[++i]);
                    default -> {
                        if (args[i].startsWith("--") || folder != null) {
                            System.err.println("Unknown option: " + args[i] + "\n");
                            System.err.print(USAGE);
                            return 2;
                        }
                        folder = Path.of(args[i]);
                    }
                }
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            System.err.println(args[args.length - 1] + " needs a value.\n");
            System.err.print(USAGE);
            return 2;
        }
        if (folder == null) {
            System.err.print(USAGE);
            return 2;
        }

        try {
            PackFolder pack = PackFolder.locate(folder, side);
            ScanOptions o = pack.options();
            ScanOptions options = new ScanOptions(side,
                    mc != null ? mc : o.minecraftVersion(),
                    neo != null ? neo : o.neoforgeVersion(),
                    o.clientOnlyFiles(), o.loader());
            Report report = PackScanner.scan(pack.modsFolder(), options);
            Path root = pack.modsFolder().getFileName().toString().equalsIgnoreCase("mods")
                    ? pack.modsFolder().getParent() : pack.modsFolder();
            StringBuilder memory = new StringBuilder("\nMemory (RAM)\n");
            for (Finding f : RamAdvisor.advise(RamAdvisor.facts(root, pack.modsFolder()), side == Side.SERVER)) {
                memory.append(f.title()).append("\n  ").append(f.detail().replace("\n", "\n  ")).append('\n');
                if (f.fix() != null) memory.append("  Fix: ").append(f.fix()).append('\n');
            }
            String text = report.toText() + memory;
            System.out.print(text);
            if (options.minecraftVersion() == null || options.neoforgeVersion() == null) {
                System.out.println("Tip: pass --mc and --neoforge to also check Minecraft and NeoForge versions.");
            }
            if (out != null) {
                Files.writeString(out, text, StandardCharsets.UTF_8);
                System.out.println("Saved to " + out.toAbsolutePath());
            }
            return report.hasErrors() ? 1 : 0;
        } catch (IOException e) {
            System.err.println(PackDoctorCore.NAME + " couldn't scan " + folder + ": " + e.getMessage());
            return 2;
        }
    }

    /** Explains a crash report. Given a folder, picks the newest report in it (or in its crash-reports folder). */
    static int crash(String[] args) {
        if (args.length != 2) {
            System.err.print(USAGE);
            return 2;
        }
        try {
            Path target = Path.of(args[1]).toAbsolutePath();
            Path file = target;
            List<Path> others = List.of();
            if (Files.isDirectory(target)) {
                Path dir = Files.isDirectory(target.resolve("crash-reports")) ? target.resolve("crash-reports") : target;
                List<Path> reports;
                try (Stream<Path> s = Files.list(dir)) {
                    reports = s.filter(p -> p.getFileName().toString().startsWith("crash-")
                                    && p.getFileName().toString().endsWith(".txt"))
                            .sorted(Comparator.comparing((Path p) -> p.toFile().lastModified()).reversed())
                            .toList();
                }
                // Forge 1.20.1 and older stop on missing mods without a crash report; only latest.log says why.
                Path log = target.resolve("logs").resolve("latest.log");
                if (Files.isRegularFile(log) && (reports.isEmpty()
                        || log.toFile().lastModified() > reports.get(0).toFile().lastModified())) {
                    CrashTranslator.Explanation fromLog = CrashTranslator.translateLog(
                            new String(Files.readAllBytes(log), StandardCharsets.UTF_8));
                    if (fromLog != null) {
                        System.out.println("Log: " + log);
                        System.out.print(fromLog.toText());
                        return 0;
                    }
                }
                if (reports.isEmpty()) {
                    System.out.println("No crash reports in " + dir + ". Nice.");
                    return 0;
                }
                file = reports.get(0);
                others = reports.subList(1, reports.size());
            }
            String text = Files.readString(file, StandardCharsets.UTF_8);
            CrashTranslator.Explanation e = CrashTranslator.translate(text);
            System.out.println("Crash report: " + file);
            System.out.print(e.toText());
            // A knock-on crash right after another crash: the earlier one is usually the real problem.
            boolean knockOn = e.findings().stream().anyMatch(f -> f.code().equals("knock-on-crash"));
            if (knockOn && !others.isEmpty()) {
                Path before = others.get(0);
                long gap = file.toFile().lastModified() - before.toFile().lastModified();
                if (gap >= 0 && gap < 10 * 60 * 1000) {
                    System.out.println("The crash just before it (" + before.getFileName()
                            + ") is probably the real cause:\n");
                    System.out.print(CrashTranslator.translate(Files.readString(before, StandardCharsets.UTF_8)).toText());
                }
            }
            if (!others.isEmpty() && e.exception() != null) {
                int same = 0;
                for (Path p : others) {
                    // Same problems, not just the same error line (every mod loading failure has the same one).
                    List<String> titles = e.findings().stream().map(Finding::title).toList();
                    if (titles.equals(CrashTranslator.translate(Files.readString(p, StandardCharsets.UTF_8))
                            .findings().stream().map(Finding::title).toList())) same++;
                }
                System.out.println("This is the newest of " + (others.size() + 1) + " crash reports"
                        + (same > 0 ? "; " + same + " older ones are the same crash." : "."));
            }
            return 0;
        } catch (IOException e) {
            System.err.println(PackDoctorCore.NAME + " couldn't read " + args[1] + ": " + e.getMessage());
            return 2;
        }
    }

    /** Copies a pack minus its client-only mods into a new folder (and optionally a zip) for a server host. */
    static int serverPack(String[] args) {
        Path pack = null;
        Path out = null;
        boolean zip = false;
        boolean dryRun = false;
        try {
            for (int i = 1; i < args.length; i++) {
                switch (args[i]) {
                    case "--out" -> out = Path.of(args[++i]);
                    case "--zip" -> zip = true;
                    case "--dry-run" -> dryRun = true;
                    default -> {
                        if (args[i].startsWith("--") || pack != null) {
                            System.err.println("Unknown option: " + args[i] + "\n");
                            System.err.print(USAGE);
                            return 2;
                        }
                        pack = Path.of(args[i]);
                    }
                }
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            System.err.println("--out needs a folder.\n");
            return 2;
        }
        if (pack == null) {
            System.err.print(USAGE);
            return 2;
        }
        try {
            ServerPack.Plan plan = ServerPack.plan(pack);
            if (out == null) out = Path.of(plan.root().getFileName() + " server");
            out = out.toAbsolutePath();
            if (dryRun) {
                System.out.print(ServerPack.readme(plan));
                System.out.println("\nDry run: nothing was copied. Without --dry-run it would go to " + out);
                return 0;
            }
            System.out.println("Copying to " + out + " ...");
            System.out.print(ServerPack.write(plan, out));
            if (zip) {
                Path zipFile = out.resolveSibling(out.getFileName() + ".zip");
                ServerPack.zip(out, zipFile);
                System.out.println("\nZipped to " + zipFile);
            }
            // Check the result as a server, so problems carried over from the pack show up now, not on the host.
            Report check = PackScanner.scan(out.resolve("mods"), plan.options().withSide(Side.SERVER));
            if (check.findings().isEmpty()) {
                System.out.println("\nChecked the server pack: no problems found.");
            } else {
                System.out.println("\nChecked the server pack: " + Report.summary(check.findings())
                        + " (these were already in the pack):\n");
                StringBuilder sb = new StringBuilder();
                Report.appendFindings(sb, check.findings());
                System.out.print(sb.substring(sb.indexOf("\n\n") + 2));
            }
            System.out.println("\nDone: " + out);
            return 0;
        } catch (IOException e) {
            System.err.println(PackDoctorCore.NAME + " couldn't build the server pack: " + e.getMessage());
            return 2;
        }
    }

    /** Compares a player's pack with a server (folder or zip) and lists what will stop players joining. */
    static int compare(String[] args) {
        if (args.length != 3) {
            System.err.print(USAGE);
            return 2;
        }
        try {
            List<Finding> findings = ServerCompare.compare(Path.of(args[1]), Path.of(args[2]));
            StringBuilder sb = new StringBuilder("Pack Doctor: pack vs server\nPack:   " + Path.of(args[1]).toAbsolutePath()
                    + "\nServer: " + Path.of(args[2]).toAbsolutePath() + "\n\n");
            Report.appendFindings(sb, findings);
            System.out.print(sb);
            if (findings.isEmpty()) System.out.println("They match: players should be able to join.");
            return findings.stream().anyMatch(f -> f.severity() == Finding.Severity.ERROR) ? 1 : 0;
        } catch (IOException e) {
            System.err.println(PackDoctorCore.NAME + " couldn't compare: " + e.getMessage());
            return 2;
        }
    }
}