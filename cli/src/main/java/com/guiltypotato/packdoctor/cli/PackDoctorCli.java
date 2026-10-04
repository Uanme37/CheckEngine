package com.guiltypotato.packdoctor.cli;

import com.guiltypotato.packdoctor.core.PackDoctorCore;
import com.guiltypotato.packdoctor.core.model.Side;
import com.guiltypotato.packdoctor.core.scan.PackFolder;
import com.guiltypotato.packdoctor.core.scan.PackScanner;
import com.guiltypotato.packdoctor.core.scan.Report;
import com.guiltypotato.packdoctor.core.scan.ScanOptions;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** CLI entry point. Phase 3+: crash translator, server pack builder. */
public final class PackDoctorCli {
    private static final String USAGE = """
            Usage: java -jar packdoctor-cli.jar scan <pack or mods folder> [options]

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
                    o.clientOnlyFiles());
            Report report = PackScanner.scan(pack.modsFolder(), options);
            String text = report.toText();
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
}
