package checkengine.core.scan;

import checkengine.core.model.ModInfo;
import checkengine.core.model.ModJar;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * One zip with everything a helper needs: Check Engine's reports, the mod list, the latest log and the newest crash
 * report. Personal bits are blanked out first: the Windows user folder, player names, server addresses and IP addresses.
 * Saved as checkengine/CheckEngine-help-<date>.zip, ready to drag into Discord or attach to an issue.
 */
public final class HelpBundle {
    /** The tail of a log is what matters; very long logs are cut from the front. */
    static final int MAX_LOG_CHARS = 4_000_000;
    /**
     * An IP address, but not a version number: inside a file name like "mod-1.20.1.2.jar" or "v2.0.1.4", after a mod id
     * in a mod list like "[jei 19.21.0.247]", in a crash report's mod table "|jei |19.21.0.247 |", in version ranges
     * "versions: 15.20.0.106 in [15.0.0.0,)", update checks "Current: 1.0.0.57 Target: 1.0.0.60" and folder names
     * ".../neoforge/26.1.2.121/".
     */
    private static final Pattern IPV4 = Pattern.compile("(?m)(?<![\\w.+-])(?<!(?:[Vv]ersions?|Current|Target):? )"
            + "(?<!\\|\\s{0,3})(?<![\\[(,])(?<!\\[[A-Za-z][\\w.-]{0,63} )"
            // Check Engine's own history ("mod.jei=15.20.0.134") and loader / Java versions ("NeoForge: 26.1.2.121")
            + "(?<!^(?:mod\\.[\\w.-]{1,64}|loader)=)(?<!\\b(?:NeoForge|Forge|Minecraft|Java):? )"
            // the loader's mod tree: "\t- insanelib 1.23.4.6", "\t\t|-- architectury 9.2.14"
            + "(?<!^\\t{1,4}(?:- |\\|-- |\\\\-- )[a-z][a-z0-9_.-]{0,63} )"
            + "(?!127\\.0\\.0\\.1\\b)(?!0\\.0\\.0\\.0\\b)"
            + "(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)(?:\\.(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}"
            // not part of a longer version ("1.2.3.4.5", "-forge"); a full stop at the end of a sentence still counts
            + "(?!\\.\\w)(?![\\w/-])"
            // the mod list in logs and crash reports: "\t\tFTB Chunks 2601.1.2.4 (ftbchunks)", "1.2.3.4+neoforge"
            // (only on tab-indented list lines, so "Pinging 1.2.3.4 (server)" in a log still goes)
            + "(?!\\+)(?!(?<=^\\t{1,4}[^\\t\\n]{1,96})\\s\\([a-z][a-z0-9_]{1,63}\\)$)");
    private static final Pattern PLAYER = Pattern.compile("(Setting user: |username=|--username,? ?)([A-Za-z0-9_]{2,16})");
    /** A launch command in a JVM crash file (hs_err) can hold the login: "--accessToken abc", "--uuid abc" and the like. */
    private static final Pattern LOGIN_ARG = Pattern.compile(
            "(--(?:accessToken|uuid|xuid|clientId)[\"']?[ ,=]+[\"']?)[^\\s,\"']+", Pattern.CASE_INSENSITIVE);
    /** A login token on its own, wherever it's written: "token:abc:def" or the form starting "eyJ". */
    private static final Pattern TOKEN = Pattern.compile("token:[\\w.-]{20,}(?::[0-9a-fA-F]{32})?|\\beyJ[\\w.-]{30,}");
    /** Who and which PC, in the environment list of a JVM crash file. */
    private static final Pattern ENV = Pattern.compile("(?im)^(\\s*(?:USERNAME|USER|LOGNAME|COMPUTERNAME|HOSTNAME|"
            + "USERDOMAIN(?:_ROAMINGPROFILE)?|LOGONSERVER)\\s*=\\s*).*$");
    /** Any user folder, also from another PC or another system: C:\Users\x, /Users/x, /home/x. Not "assets/home/x". */
    private static final Pattern USER_FOLDER = Pattern.compile("(?<![\\w.-])([\\\\/]+(?:Users|home)[\\\\/]+)[^\\\\/\\s,;:\"'<>]+",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SERVER_UUID = Pattern.compile("(UUID of player )\\S+( is )\\S+");
    /** An IPv6 address, full or with "::" (and "%eth0" after it). Not times like 12:34:56 or "Foo::add" in code. */
    private static final Pattern IPV6 = Pattern.compile("(?i)(?<![\\w:.%])(?!::1(?![\\w:]))(?:(?:[0-9a-f]{1,4}:){7}[0-9a-f]{1,4}"
            + "|(?:[0-9a-f]{1,4}:){1,7}:(?:[0-9a-f]{1,4}(?::[0-9a-f]{1,4}){0,6})?|::(?:[0-9a-f]{1,4}:){0,5}[0-9a-f]{1,4})"
            + "(?:%[\\w.]+)?(?![\\w:])");
    /** A server's host name where the game or Check Engine writes the address: "Connecting to play.x.net, 25565". */
    private static final Pattern HOST = Pattern.compile("(?i)((?:Connecting to |server address[:=]? ?|serverAddress[:=] ?"
            + "|server[_-]?ip[:=] ?|--quickPlayMultiplayer[ ,=]+[\"']?|--server[ ,=]+[\"']?|couldn't join ))"
            + "(?!localhost\\b)([a-z0-9][a-z0-9-]*(?:\\.[a-z0-9-]+)+|[a-z0-9][a-z0-9-]*(?=, ?\\d))");
    /** A host name in front of its address in network logs: "play.x.net/203.0.113.7:25565". */
    private static final Pattern HOST_BEFORE_IP = Pattern.compile("(?i)(?<![\\w.-])[a-z0-9][a-z0-9-]*(?:\\.[a-z0-9-]+)+(?=/<ip>)");
    /** Where logs write a player's name, so it can be blanked on every other line too. */
    private static final Pattern NAME_SOURCE = Pattern.compile("(?:Setting user: |username=|--username,? ?|UUID of player )"
            + "([A-Za-z0-9_]{3,16})\\b|(?<![\\w])([A-Za-z0-9_]{3,16})\\[/[^\\]\\s]*\\] logged in");

    private HelpBundle() {}

    /**
     * @param gameDir the pack folder (the one with mods/, logs/, crash-reports/)
     * @param report  a scan of the pack, or null
     * @return the zip
     */
    public static Path create(Path gameDir, Report report) throws IOException {
        return create(gameDir, report, null);
    }

    /**
     * @param player the player's name when the game knows it, blanked out in every file; names written in the log
     *               ("Setting user", server joins) are found and blanked as well
     */
    public static Path create(Path gameDir, Report report, String player) throws IOException {
        Path out = gameDir.resolve("checkengine").resolve("CheckEngine-help-"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm")) + ".zip");
        Files.createDirectories(out.getParent());
        Redactor redact = new Redactor(System.getProperty("user.home"), player);
        // Learn every name first, so the files written before the log (README, reports) lose it too.
        Path ceFolder = gameDir.resolve("checkengine");
        Path newest = newestCrash(gameDir);
        for (Path f : new Path[] {gameDir.resolve("logs").resolve("latest.log"), ceFolder.resolve("join-report.txt"),
                ceFolder.resolve("last-crash.txt"), newest}) {
            try {
                if (f != null && Files.isRegularFile(f)) redact.learn(Files.readString(f, StandardCharsets.UTF_8));
            } catch (IOException | RuntimeException ignored) {
                // a file that can't be read isn't copied either
            }
        }
        try (OutputStream file = Files.newOutputStream(out); ZipOutputStream zip = new ZipOutputStream(file)) {
            put(zip, "README.txt", redact.apply(readme(gameDir, report)));
            if (report != null) {
                put(zip, "mods.txt", redact.apply(modList(report)));
                put(zip, "check-report.txt", redact.apply(report.toText()));
            }
            Path ce = gameDir.resolve("checkengine");
            for (String name : List.of("boot-report.txt", "early-report.txt", "last-crash.txt", "scan-report.txt",
                    "startup-report.txt", "join-report.txt", PackSnapshot.FILE, PackJournal.FILE,
                    // Misfire: which round did what, so a report shows where a search went wrong.
                    "strip-mine.properties", "strip-mine.log", "strip-mine-result.txt")) {
                copy(zip, ce.resolve(name), "checkengine/" + name, redact);
            }
            copy(zip, gameDir.resolve("logs").resolve("latest.log"), "logs/latest.log", redact);
            // Which mods flood the log over the last launches: often the same mods behind odd bugs.
            try {
                put(zip, "log-check.txt", redact.apply(LogSpam.check(gameDir).toText()
                        + LogProblems.check(gameDir).toText()));
            } catch (RuntimeException ignored) {
                // the help file is still useful without it
            }
            Path crash = newest;
            if (crash != null) {
                copy(zip, crash, "crash-reports/" + crash.getFileName(), redact);
                suggestFix(zip, crash, redact);
            }
            copy(zip, gameDir.resolve("config").resolve("fml.toml"), "config/fml.toml", redact);
        }
        return out;
    }

    /** A ready-made crash library suggestion: if someone finds the fix, this is what the list entry needs. */
    private static void suggestFix(ZipOutputStream zip, Path crash, Redactor redact) {
        try {
            String sig = checkengine.core.crash.KnownCrashes.signature(
                    Files.readString(crash, StandardCharsets.UTF_8));
            if (sig.isEmpty()) return;
            put(zip, "suggest-this-fix.txt", redact.apply("""
                    Found the fix for this crash? Add it to the Check Engine crash library so it helps the next
                    player: open an issue at https://github.com/Uanme37/CheckEngine/issues with this text and the fix
                    that worked.

                    """ + sig + "\n"));
        } catch (IOException | RuntimeException ignored) {
            // the help file is still useful without it
        }
    }

    private static String readme(Path gameDir, Report report) {
        StringBuilder sb = new StringBuilder("Check Engine help file\n");
        sb.append("Made: ").append(LocalDateTime.now().withNano(0).toString().replace('T', ' ')).append('\n');
        sb.append("Modpack folder: ").append(gameDir.toAbsolutePath()).append('\n');
        sb.append("Java: ").append(System.getProperty("java.version")).append(", OS: ")
                .append(System.getProperty("os.name")).append('\n');
        if (report != null) {
            ScanOptions o = report.options();
            if (o.minecraftVersion() != null) sb.append("Minecraft: ").append(o.minecraftVersion()).append('\n');
            if (o.neoforgeVersion() != null) sb.append(o.loaderName()).append(": ").append(o.neoforgeVersion()).append('\n');
            sb.append("Modpack health: ").append(report.health()).append('\n');
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
                config/fml.toml. Check Engine removed names, addresses and logins from this copy: the user name, PC
                name, player names, login tokens, server addresses and IP addresses. No mods, worlds or passwords are
                included. Nothing was sent anywhere: this zip stays on your PC until you share it.
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

    /** The newest crash report, or hs_err_pid file when the game closed without one. */
    private static Path newestCrash(Path gameDir) {
        return checkengine.core.crash.CrashDoctor.crashFiles(gameDir).stream()
                .max(Comparator.comparingLong(p -> p.toFile().lastModified())).orElse(null);
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

    /**
     * Blanks out the user folder (every way it gets written), player names on every line, login tokens, server
     * host names and IP addresses (v4 and v6). Player names come from the constructor and from the text itself.
     */
    public static final class Redactor {
        private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");
        private final String home;
        private final String user;
        private final Set<String> names = new LinkedHashSet<>();
        private Pattern namePattern;

        public Redactor(String home) {
            this(home, null);
        }

        /** @param player a player name to blank everywhere, or null */
        public Redactor(String home, String player) {
            this.home = home;
            String name = home == null ? null : Path.of(home).getFileName() == null ? null
                    : Path.of(home).getFileName().toString();
            this.user = name == null || name.length() < 3 ? null : name;
            addName(player);
        }

        /** Remembers a name to blank on every line (3 to 16 letters, digits or _, like Minecraft names). */
        public Redactor addName(String name) {
            if (name != null && VALID_NAME.matcher(name).matches() && names.add(name)) namePattern = null;
            return this;
        }

        /** Finds the names a log writes ("Setting user: X", "UUID of player X", "X[/ip] logged in") to blank later. */
        public Redactor learn(String text) {
            Matcher m = NAME_SOURCE.matcher(text);
            while (m.find()) addName(m.group(1) != null ? m.group(1) : m.group(2));
            return this;
        }

        public String apply(String text) {
            learn(text);
            String t = text;
            if (home != null) {
                t = t.replace(home, "<home>").replace(home.replace('\\', '/'), "<home>");
            }
            if (user != null) {
                // e.g. "/C:/Users/Alex/..." in crash reports, or the folder name on its own
                t = Pattern.compile("([\\\\/]Users[\\\\/])" + Pattern.quote(user) + "(?=[\\\\/])", Pattern.CASE_INSENSITIVE)
                        .matcher(t).replaceAll("$1<user>");
            }
            t = LOGIN_ARG.matcher(t).replaceAll("$1<hidden>");
            t = TOKEN.matcher(t).replaceAll("<hidden>");
            t = ENV.matcher(t).replaceAll("$1<hidden>");
            t = USER_FOLDER.matcher(t).replaceAll("$1<user>");
            t = SERVER_UUID.matcher(t).replaceAll("$1<player>$2<hidden>");
            t = PLAYER.matcher(t).replaceAll("$1<player>");
            t = IPV4.matcher(t).replaceAll("<ip>");
            t = IPV6.matcher(t).replaceAll("<ip>");
            t = HOST_BEFORE_IP.matcher(t).replaceAll("<server>");
            t = HOST.matcher(t).replaceAll("$1<server>");
            if (!names.isEmpty()) {
                if (namePattern == null) {
                    namePattern = Pattern.compile("(?<![A-Za-z0-9_])(?:" + String.join("|", names.stream()
                            .map(Pattern::quote).toList()) + ")(?![A-Za-z0-9_])");
                }
                t = namePattern.matcher(t).replaceAll("<player>");
            }
            return t;
        }
    }
}
