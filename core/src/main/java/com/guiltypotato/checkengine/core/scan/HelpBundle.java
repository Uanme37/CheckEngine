package com.guiltypotato.checkengine.core.scan;

import com.guiltypotato.checkengine.core.model.ModInfo;
import com.guiltypotato.checkengine.core.model.ModJar;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * One zip with everything a helper needs: Check Engine's reports, the mod list, the latest log and the newest crash
 * report. Personal bits are blanked out first: the Windows user folder, the player name and IP addresses.
 * Saved as checkengine/CheckEngine-help-<date>.zip, ready to drag into Discord or attach to an issue.
 */
public final class HelpBundle {
    /** The tail of a log is what matters; very long logs are cut from the front. */
    static final int MAX_LOG_CHARS = 4_000_000;
    /** An IP address, but not a version number inside a file name like "mod-1.20.1.2.jar" or "v2.0.1.4". */
    private static final Pattern IPV4 = Pattern.compile("(?<![\\w.+-])(?<![Vv]ersion )(?!127\\.0\\.0\\.1\\b)(?!0\\.0\\.0\\.0\\b)"
            + "(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)(?:\\.(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}(?![.\\w-])");
    private static final Pattern PLAYER = Pattern.compile("(Setting user: |username=|--username,? ?)([A-Za-z0-9_]{2,16})");

    private HelpBundle() {}

    /**
     * @param gameDir the pack folder (the one with mods/, logs/, crash-reports/)
     * @param report  a scan of the pack, or null
     * @return the zip
     */
    public static Path create(Path gameDir, Report report) throws IOException {
        Path out = gameDir.resolve("checkengine").resolve("CheckEngine-help-"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm")) + ".zip");
        Files.createDirectories(out.getParent());
        Redactor redact = new Redactor(System.getProperty("user.home"));
        try (OutputStream file = Files.newOutputStream(out); ZipOutputStream zip = new ZipOutputStream(file)) {
            put(zip, "README.txt", redact.apply(readme(gameDir, report)));
            if (report != null) {
                put(zip, "mods.txt", redact.apply(modList(report)));
                put(zip, "check-report.txt", redact.apply(report.toText()));
            }
            Path ce = gameDir.resolve("checkengine");
            for (String name : List.of("boot-report.txt", "early-report.txt", "last-crash.txt", "scan-report.txt",
                    "startup-report.txt", "join-report.txt", PackSnapshot.FILE)) {
                copy(zip, ce.resolve(name), "checkengine/" + name, redact);
            }
            copy(zip, gameDir.resolve("logs").resolve("latest.log"), "logs/latest.log", redact);
            Path crash = newestCrash(gameDir);
            if (crash != null) copy(zip, crash, "crash-reports/" + crash.getFileName(), redact);
            copy(zip, gameDir.resolve("config").resolve("fml.toml"), "config/fml.toml", redact);
        }
        return out;
    }

    private static String readme(Path gameDir, Report report) {
        StringBuilder sb = new StringBuilder("Check Engine help file\n");
        sb.append("Made: ").append(LocalDateTime.now().withNano(0).toString().replace('T', ' ')).append('\n');
        sb.append("Pack folder: ").append(gameDir.toAbsolutePath()).append('\n');
        sb.append("Java: ").append(System.getProperty("java.version")).append(", OS: ")
                .append(System.getProperty("os.name")).append('\n');
        if (report != null) {
            ScanOptions o = report.options();
            if (o.minecraftVersion() != null) sb.append("Minecraft: ").append(o.minecraftVersion()).append('\n');
            if (o.neoforgeVersion() != null) sb.append(o.loaderName()).append(": ").append(o.neoforgeVersion()).append('\n');
            sb.append("Pack health: ").append(report.health()).append('\n');
            if (!report.roots().isEmpty()) {
                sb.append("\nFix these first:\n");
                for (RootProblem p : report.roots()) sb.append("- ").append(p.title()).append('\n');
            }
            if (report.changes() != null && !report.changes().isEmpty()) {
                sb.append("\nChanged since it last worked (").append(report.changes().when()).append("):\n")
                        .append(report.changes().summary()).append('\n');
            }
        }
        sb.append("""

                What's in here: Check Engine's reports, the mod list, logs/latest.log, the newest crash report and
                config/fml.toml. Check Engine blanked out the Windows user name, the player name and IP addresses.
                No mods, worlds or passwords are included.
                """);
        return sb.toString();
    }

    private static String modList(Report report) {
        StringBuilder sb = new StringBuilder("Mods folder (" + report.jars().size() + " jars)\n\n");
        report.jars().stream().sorted(Comparator.comparing(ModJar::fileName, String.CASE_INSENSITIVE_ORDER))
                .forEach(j -> {
                    sb.append(j.fileName());
                    for (ModInfo m : j.mods()) sb.append("  [").append(m.modId()).append(' ').append(m.version()).append(']');
                    if (j.kind() == ModJar.Kind.BROKEN || j.kind() == ModJar.Kind.FABRIC) sb.append("  (").append(j.kind()).append(')');
                    sb.append('\n');
                });
        return sb.toString();
    }

    private static Path newestCrash(Path gameDir) {
        Path dir = gameDir.resolve("crash-reports");
        if (!Files.isDirectory(dir)) return null;
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".txt"))
                    .max(Comparator.comparingLong(p -> p.toFile().lastModified())).orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private static void copy(ZipOutputStream zip, Path file, String name, Redactor redact) throws IOException {
        if (!Files.isRegularFile(file)) return;
        String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        if (text.length() > MAX_LOG_CHARS) {
            text = "[... start cut off, the file was too big ...]\n" + text.substring(text.length() - MAX_LOG_CHARS);
        }
        put(zip, name, redact.apply(text));
    }

    private static void put(ZipOutputStream zip, String name, String text) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    /** Blanks out the user folder (every way it gets written), the player name and IP addresses. */
    static final class Redactor {
        private final String home;
        private final String user;

        Redactor(String home) {
            this.home = home;
            String name = home == null ? null : Path.of(home).getFileName() == null ? null
                    : Path.of(home).getFileName().toString();
            this.user = name == null || name.length() < 3 ? null : name;
        }

        String apply(String text) {
            String t = text;
            if (home != null) {
                t = t.replace(home, "<home>").replace(home.replace('\\', '/'), "<home>");
            }
            if (user != null) {
                // e.g. "/C:/Users/Gaming/..." in crash reports, or the folder name on its own
                t = Pattern.compile("([\\\\/]Users[\\\\/])" + Pattern.quote(user) + "(?=[\\\\/])", Pattern.CASE_INSENSITIVE)
                        .matcher(t).replaceAll("$1<user>");
            }
            t = PLAYER.matcher(t).replaceAll("$1<player>");
            return IPV4.matcher(t).replaceAll("<ip>");
        }
    }
}
