package com.guiltypotato.checkengine.cli;

import com.guiltypotato.checkengine.core.CheckEngineCore;
import com.guiltypotato.checkengine.core.crash.CrashDoctor;
import com.guiltypotato.checkengine.core.crash.CrashTranslator;
import com.guiltypotato.checkengine.core.scan.Finding;
import com.guiltypotato.checkengine.core.scan.HelpBundle;
import com.guiltypotato.checkengine.core.model.Side;
import com.guiltypotato.checkengine.core.scan.PackFolder;
import com.guiltypotato.checkengine.core.scan.PackScanner;
import com.guiltypotato.checkengine.core.scan.RamAdvisor;
import com.guiltypotato.checkengine.core.scan.StartupTimes;
import com.guiltypotato.checkengine.core.scan.Report;
import com.guiltypotato.checkengine.core.scan.ScanOptions;
import com.guiltypotato.checkengine.core.scan.ServerCompare;
import com.guiltypotato.checkengine.core.scan.ServerPack;
import com.guiltypotato.checkengine.core.strip.UndoNewMods;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** CLI entry point: pack scan, crash translator and server pack builder. */
public final class CheckEngineCli {
    private static final String USAGE = """
            Usage: java -jar checkengine-cli.jar scan <pack or mods folder> [options]
                   java -jar checkengine-cli.jar crash <crash report, or a pack folder for its newest crash>
                   java -jar checkengine-cli.jar serverpack <pack folder> [--out <new folder>] [--zip] [--dry-run]
                   java -jar checkengine-cli.jar compare <pack folder> <server folder or server pack .zip>
                   java -jar checkengine-cli.jar export <pack folder>   (one zip with reports and logs, for asking for help)
                   java -jar checkengine-cli.jar stripmine <pack folder> [stop]   (find the mod behind a crash, round by round)
                   java -jar checkengine-cli.jar undo-configs <pack folder>   (put back the configs of the last launch that worked)
                   java -jar checkengine-cli.jar newmods <pack folder> [list|off|putback]   (switch off the mods added since it last worked)
                   java -jar checkengine-cli.jar logs <pack folder>   (log flooding, config typos, missing textures)
                   java -jar checkengine-cli.jar journal <pack folder>   (what changed between launches that worked)
                   java -jar checkengine-cli.jar materials <pack folder>   (same ingot or ore from several mods)
                   java -jar checkengine-cli.jar exportcheck <export .zip or .mrpack>   (before uploading a pack)

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
        if (args[0].equals("export")) return export(args);
        if (args[0].equals("stripmine")) return StripMineCli.run(args);
        if (args[0].equals("undo-configs")) return undoConfigs(args);
        if (args[0].equals("newmods")) return newMods(args);
        if (args[0].equals("logs")) return logs(args);
        if (args[0].equals("journal")) return journal(args);
        if (args[0].equals("materials")) return materials(args);
        if (args[0].equals("exportcheck")) return exportCheck(args);
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
            StartupTimes.Data startup = StartupTimes.load(root.resolve("checkengine"));
            if (startup != null) {
                memory.append("\nStartup (last launch with the Check Engine mod, ").append(startup.when()).append(")\n")
                        .append(StartupTimes.wasServer(root.resolve("checkengine")) ? "Server ready in " : "Title screen after ")
                        .append(StartupTimes.time(startup.totalMs())).append(": mods ")
                        .append(StartupTimes.time(startup.modsMs())).append(", then ")
                        .append(StartupTimes.time(startup.finishingMs())).append('\n');
                startup.mods().stream().filter(m -> m.ms() >= 250).limit(5).forEach(m -> memory.append("  ")
                        .append(m.name()).append(": ").append(StartupTimes.time(m.ms())).append('\n'));
                memory.append("  Full breakdown: checkengine\\").append(StartupTimes.REPORT_FILE).append('\n');
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
            System.err.println(CheckEngineCore.NAME + " couldn't scan " + folder + ": " + e.getMessage());
            return 2;
        }
    }

    /** Makes the help zip (reports, mod list, latest.log, newest crash, personal bits blanked) for asking for help. */
    static int export(String[] args) {
        if (args.length != 2) {
            System.err.print(USAGE);
            return 2;
        }
        try {
            PackFolder pack = PackFolder.locate(Path.of(args[1]), Side.CLIENT);
            Report report = PackScanner.scan(pack.modsFolder(), pack.options());
            Path root = pack.modsFolder().getFileName().toString().equalsIgnoreCase("mods")
                    ? pack.modsFolder().getParent() : pack.modsFolder();
            Path zip = HelpBundle.create(root, report);
            System.out.println("Help file saved: " + zip.toAbsolutePath());
            System.out.println("Drag it into the Discord or GitHub issue where you're asking for help. Your Windows "
                    + "user name, player name and IP addresses were blanked out.");
            return 0;
        } catch (IOException e) {
            System.err.println(CheckEngineCore.NAME + " couldn't make a help file for " + args[1] + ": " + e.getMessage());
            return 2;
        }
    }

    /** Checks a pack export for the pack maker's own files and other mistakes before it's uploaded. */
    static int exportCheck(String[] args) {
        if (args.length != 2) {
            System.err.print(USAGE);
            return 2;
        }
        try {
            Path zip = Path.of(args[1]).toAbsolutePath();
            var findings = com.guiltypotato.checkengine.core.scan.ExportCheck.check(zip);
            System.out.print(com.guiltypotato.checkengine.core.scan.ExportCheck.toText(zip, findings));
            return findings.stream().anyMatch(x -> x.severity()
                    != com.guiltypotato.checkengine.core.scan.Finding.Severity.INFO) ? 1 : 0;
        } catch (IOException | RuntimeException e) {
            System.err.println("Couldn't read that export: " + e.getMessage());
            return 2;
        }
    }

    /** Puts back the configs of the last launch that worked; the current ones are kept in checkengine/config-undo-*. */
    /**
     * newmods <pack> [list|off|putback]: the mods added since the pack last worked. list (default) prints them and
     * exits 1 when there are some; off switches them off (with what needs them); putback switches them on again.
     */
    static int newMods(String[] args) {
        String what = args.length == 3 ? args[2] : "list";
        if (args.length < 2 || args.length > 3 || !List.of("list", "off", "putback").contains(what)) {
            System.err.print(USAGE);
            return 2;
        }
        try {
            Path mods = PackFolder.locate(Path.of(args[1]), Side.CLIENT).modsFolder();
            Path folder = mods.getParent().resolve("checkengine");
            if (what.equals("putback")) {
                List<String> back = UndoNewMods.putBack(mods, folder);
                System.out.println(back.isEmpty() ? "Nothing to put back." : "Switched back on: " + String.join(", ", back));
                return 0;
            }
            List<String> plan = UndoNewMods.plan(mods, folder);
            if (plan.isEmpty()) {
                System.out.println("No mods added since the pack last worked (or it never worked with Check Engine "
                        + "installed).");
                return 0;
            }
            System.out.println("Added since the pack last worked (with the mods that need them):");
            plan.forEach(f -> System.out.println("  " + f));
            if (what.equals("list")) return 1;
            List<String> locked = UndoNewMods.apply(mods, folder, plan);
            System.out.println(locked.isEmpty() ? "Switched off (renamed to .disabled). Put them back any time: newmods "
                    + "<pack> putback." : "Close the game first, these are in use: " + String.join(", ", locked));
            return locked.isEmpty() ? 0 : 2;
        } catch (IOException | RuntimeException e) {
            System.err.println("Couldn't switch the new mods: " + e.getMessage());
            return 2;
        }
    }

    /** logs <pack>: which mods write the most warnings and errors over the last launches. Exit 1 if some flood it. */
    static int logs(String[] args) {
        if (args.length != 2) {
            System.err.print(USAGE);
            return 2;
        }
        Path game = Path.of(args[1]).toAbsolutePath();
        if (Files.isDirectory(game.resolve("minecraft")) && !Files.isDirectory(game.resolve("logs"))) {
            game = game.resolve("minecraft"); // Prism / MultiMC instance folder
        }
        var result = com.guiltypotato.checkengine.core.scan.LogSpam.check(game);
        if (result.launches() == 0) {
            System.out.println("No game logs in " + game.resolve("logs") + " yet.");
            return 0;
        }
        System.out.print(result.toText());
        // Config values the loader threw away, and missing textures and models
        var problems = com.guiltypotato.checkengine.core.scan.LogProblems.check(game);
        System.out.print(problems.toText());
        return result.isEmpty() && problems.isEmpty() ? 0 : 1;
    }

    /** journal <pack>: the pack journal, what changed between launches that worked (newest first). */
    static int journal(String[] args) {
        if (args.length != 2) {
            System.err.print(USAGE);
            return 2;
        }
        try {
            Path game = PackFolder.locate(Path.of(args[1]), Side.CLIENT).modsFolder().getParent();
            String text = com.guiltypotato.checkengine.core.scan.PackJournal.read(game.resolve("checkengine"));
            System.out.print(text != null ? text : "No journal yet: it starts with the next launch that works with "
                    + "Check Engine installed.\n");
            return 0;
        } catch (IOException | RuntimeException e) {
            System.err.println("Couldn't read the journal: " + e.getMessage());
            return 2;
        }
    }

    /** materials <pack>: the same ingot, ore, dust... from several mods. Exit 1 if there are some. */
    static int materials(String[] args) {
        if (args.length != 2) {
            System.err.print(USAGE);
            return 2;
        }
        try {
            Path mods = PackFolder.locate(Path.of(args[1]), Side.CLIENT).modsFolder();
            List<com.guiltypotato.checkengine.core.model.ModJar> jars = new java.util.ArrayList<>();
            try (Stream<Path> s = Files.list(mods)) {
                for (Path p : s.filter(f -> f.getFileName().toString().toLowerCase().endsWith(".jar")).toList()) {
                    jars.add(com.guiltypotato.checkengine.core.scan.JarReader.read(p));
                }
            }
            var dupes = com.guiltypotato.checkengine.core.scan.Materials.find(jars);
            System.out.print(com.guiltypotato.checkengine.core.scan.Materials.toText(dupes,
                    com.guiltypotato.checkengine.core.scan.Materials.unifiers(jars)));
            return dupes.isEmpty() ? 0 : 1;
        } catch (IOException | RuntimeException e) {
            System.err.println("Couldn't read the mods: " + e.getMessage());
            return 2;
        }
    }

    static int undoConfigs(String[] args) {
        if (args.length != 2) {
            System.err.print(USAGE);
            return 2;
        }
        try {
            Path game = PackFolder.locate(Path.of(args[1]), Side.CLIENT).modsFolder().getParent();
            Path folder = game.resolve("checkengine");
            var changes = com.guiltypotato.checkengine.core.scan.ConfigCheckpoint.changes(game, folder);
            if (changes.isEmpty()) {
                System.out.println("The configs match the last launch that worked (or it never worked with Check Engine "
                        + "installed). Nothing to undo.");
                return 0;
            }
            changes.forEach(c -> System.out.println("  " + c.describe()));
            Path kept = com.guiltypotato.checkengine.core.scan.ConfigCheckpoint.restore(game, folder);
            System.out.println("Put back " + changes.size() + " config file(s). Your changed ones are in " + kept);
            return 0;
        } catch (IOException | RuntimeException e) {
            System.err.println("Couldn't put the configs back: " + e.getMessage());
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
                // Crash reports, plus hs_err_pid files: the game closed without a report (a silent crash).
                List<Path> all = new java.util.ArrayList<>(CrashDoctor.crashFiles(target));
                if (!dir.equals(target.resolve("crash-reports"))) {
                    try (Stream<Path> s = Files.list(dir)) {
                        s.filter(p -> p.getFileName().toString().startsWith("crash-")
                                && p.getFileName().toString().endsWith(".txt")).forEach(all::add);
                    }
                }
                reports = all.stream().distinct()
                        .sorted(Comparator.comparing((Path p) -> p.toFile().lastModified()).reversed()).toList();
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
            // In a pack folder the game log can name the mod that failed first (the report blames a knock-on).
            Path gameDir = file.getParent() != null && file.getParent().getFileName() != null
                    && file.getParent().getFileName().toString().equals("crash-reports") ? file.getParent().getParent()
                    : null;
            CrashTranslator.Explanation e = CrashTranslator.withLog(CrashTranslator.translate(text),
                    gameDir == null ? null : com.guiltypotato.checkengine.core.crash.GameLog.forCrash(gameDir, file));
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
            System.err.println(CheckEngineCore.NAME + " couldn't read " + args[1] + ": " + e.getMessage());
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
            System.err.println(CheckEngineCore.NAME + " couldn't build the server pack: " + e.getMessage());
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
            StringBuilder sb = new StringBuilder("Check Engine: pack vs server\nPack:   " + Path.of(args[1]).toAbsolutePath()
                    + "\nServer: " + Path.of(args[2]).toAbsolutePath() + "\n\n");
            Report.appendFindings(sb, findings);
            System.out.print(sb);
            if (findings.isEmpty()) System.out.println("They match: players should be able to join.");
            return findings.stream().anyMatch(f -> f.severity() == Finding.Severity.ERROR) ? 1 : 0;
        } catch (IOException e) {
            System.err.println(CheckEngineCore.NAME + " couldn't compare: " + e.getMessage());
            return 2;
        }
    }
}