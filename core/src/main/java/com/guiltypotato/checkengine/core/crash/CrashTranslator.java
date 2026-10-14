package com.guiltypotato.checkengine.core.crash;

import com.guiltypotato.checkengine.core.scan.Finding;
import com.guiltypotato.checkengine.core.scan.HiddenDependencies;
import com.guiltypotato.checkengine.core.scan.Finding.Severity;
import com.guiltypotato.checkengine.core.scan.Report;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Turns a NeoForge crash report into plain-English findings: what broke, which mod, and what to do. */
public final class CrashTranslator {
    /** Modules in stack traces that are never the culprit. */
    private static final Set<String> NOT_CULPRITS = Set.of("minecraft", "neoforge", "notenoughcrashes", "fml",
            "fml_loader", "javafml", "mixinextras");

    /** {@code at TRANSFORMER/xaerolib@1.7.3/xaero.lib...} */
    private static final Pattern FRAME_MOD = Pattern.compile("^\\s+at (?:[A-Z-]+/)?([a-z0-9_.-]+)@[^/]+/");
    /** {@code Name (id)} inside the "Suspected Mods:" line. */
    private static final Pattern SUSPECT = Pattern.compile("([^,]+?) \\(([a-z0-9_.-]+)\\)");

    private static final Pattern REQUIRES_MISSING = Pattern.compile(
            "Mod (\\S+) requires (\\S+) (.+?) Currently, \\2 is not installed");
    private static final Pattern REQUIRES_NEWER = Pattern.compile(
            "Mod (\\S+) requires (\\S+) (.+?) Currently, \\2 is (\\S+)");
    private static final Pattern ONLY_SUPPORTS = Pattern.compile(
            "Mod (\\S+) only supports (\\S+) (.+?) Currently, \\2 is (\\S+)");
    private static final Pattern INCOMPATIBLE = Pattern.compile(
            "Mod (\\S+) is incompatible with (\\S+) .*?Currently, \\2 is (\\S+)(?: The reason is: (.*))?");
    private static final Pattern FORGE_FILE = Pattern.compile(
            "File (.+?) is for Minecraft Forge or an older version of NeoForge");
    private static final Pattern FABRIC_FILE = Pattern.compile("File (.+?) is a Fabric mod");
    /** NeoForge: "Create (create) has failed to load correctly java.lang.IllegalStateException: ..." */
    private static final Pattern FAILED_TO_LOAD = Pattern.compile(
            "^(.+?) \\(([a-z0-9_.-]+)\\) has failed to load correctly(?: (?:[a-z0-9_]+\\.)*([A-Za-z0-9_$]+(?:Exception|Error)\\b.*))?$");
    /** Mixin errors name the mod whose patch failed: "...MixinLevelRenderer from mod epicfight->@Inject..." */
    private static final Pattern FROM_MOD = Pattern.compile("from mod ([a-z][a-z0-9_]*)");
    /** "Bobber Detector (bobberdetector) has failed to load correctly java.lang.NoClassDefFoundError: com/simibubi/create/..." */
    private static final Pattern CLASS_MISSING = Pattern.compile(
            "^(.+?) \\((\\S+)\\) has failed to load correctly.*?NoClassDefFoundError: (\\S+)");
    /**
     * Forge 1.20.1 and older don't write a crash report for missing mods; latest.log gets:
     * {@code Mod ID: 'architectury', Requested by: 'appleskin', Expected range: '[9.1.12,)', Actual version: '[MISSING]'}
     */
    private static final Pattern FORGE_MISSING = Pattern.compile(
            "Mod ID: '([^']+)', Requested by: '([^']+)', Expected range: '([^']*)', Actual version: '([^']*)'");
    /** Marks a log where Forge stopped because of dependencies. */
    public static final String FORGE_DEPS_MARKER = "Missing or unsupported mandatory dependencies";
    private static final Pattern NOT_AVAILABLE = Pattern.compile("Mod '([^']+)' is not available");
    /**
     * Something registered twice. NeoForge 1.21: "Duplicate client extensions registration for Block{create:belt}";
     * vanilla registries: "Adding duplicate key 'ResourceKey[minecraft:block / create:belt]' to registry"; Forge:
     * "...has been registered twice for the same name create:belt"; DeferredRegister: "Duplicate registration belt".
     */
    private static final Pattern DUPLICATE = Pattern.compile(
            "Duplicate (?:client extensions )?registration(?: for)? (?:\\w+\\{)?([a-z0-9_.-]+:[a-z0-9_/.-]+)"
                    + "|Adding duplicate (?:key|value) '?ResourceKey\\[[a-z0-9_.:/-]+ / ([a-z0-9_.-]+:[a-z0-9_/.-]+)\\]"
                    + "|registered twice for the same name ([a-z0-9_.-]+:[a-z0-9_/.-]+)"
                    + "|Duplicate registration ([a-z0-9_/.-]+)\\b");
    /** "Create (create) encountered an error while dispatching..." / "...has failed to load correctly" */
    private static final Pattern REPORTER = Pattern.compile(
            "^(.+?) \\(([a-z0-9_.-]+)\\) (?:has failed to load correctly|encountered an error)");
    /** Mixin naming the patch that got there first: "already redirected by supplementaries.mixins.json:...". */
    private static final Pattern MIXIN_FIRST = Pattern.compile(
            "(?:already redirected by|previously written by|merged by) ([A-Za-z0-9_.$-]+)");
    /** The game class a failed patch targets: "LVT in net/minecraft/client/renderer/LevelRenderer::m_109599_". */
    /** The jar a frame comes from: "~[player-animation-lib-forge-1.0.2-rc1+1.20.jar%23838!/:?]" -> the file name. */
    private static final Pattern FRAME_JAR = Pattern.compile("~\\[([^\\]%!]+?\\.jar)");
    private static final Pattern MIXIN_TARGET = Pattern.compile("(net/minecraft/[A-Za-z0-9_/$]+)::");

    private CrashTranslator() {}

    /**
     * What a crash report says, translated.
     *
     * @param description the report's "Description:" line, e.g. "Exception in server tick loop"
     * @param exception   the first line of the exception
     * @param findings    what went wrong and how to fix it, worst first
     */
    public record Explanation(String description, String exception, List<Finding> findings) {
        public String toText() {
            StringBuilder sb = new StringBuilder("Check Engine crash translation\n");
            if (description != null) sb.append("Minecraft said: ").append(description).append('\n');
            if (exception != null) sb.append("Error: ").append(exception).append('\n');
            sb.append('\n');
            Report.appendFindings(sb, findings);
            return sb.toString();
        }
    }

    /** "Failed to create mod instance. ModID: cecrash, class ce.crash.CeCrash" (NeoForge and Forge). */
    private static final Pattern LOG_CONSTRUCT = Pattern.compile(
            "Failed to create mod instance\\. ModID: ([a-z0-9_.-]+)");
    /** "Caught exception during event FMLCommonSetupEvent dispatch for modid create" */
    private static final Pattern LOG_EVENT = Pattern.compile(
            "Caught exception during event (?:\\S+\\.)?(\\S+?) dispatch for modid ([a-z0-9_.-]+)");
    /** Log lines start with "[14Oct2026 10:29:28.182]" or "[10:29:28]"; anything else belongs to the line above. */
    private static final Pattern LOG_LINE = Pattern.compile("^\\[(?:\\d{2}[A-Za-z]{3}\\d{4} |\\d{2}:\\d{2}:\\d{2}\\])");

    /**
     * Adds what the game log knows: when a mod failed to load earlier in the session, the crash report often blames
     * the mod that tripped over it next ("Mod 'architectury' is not available"). The mod that failed first goes on
     * top. Returns {@code crash} unchanged when the log names no failed mod, or the report already does.
     */
    public static Explanation withLog(Explanation crash, String log) {
        if (log == null) return crash;
        String[] lines = log.replace("\r", "").split("\n");
        Map<String, String> failed = new LinkedHashMap<>(); // mod id -> its error
        for (int i = 0; i < lines.length; i++) {
            Matcher c = LOG_CONSTRUCT.matcher(lines[i]);
            Matcher e = LOG_EVENT.matcher(lines[i]);
            String mod = c.find() ? c.group(1) : e.find() ? e.group(2) : null;
            if (mod == null || failed.containsKey(mod)) continue;
            failed.put(mod, logError(lines, i));
        }
        // Only when the report shows a side effect: a mixin conflict or a mod's own crash is already the real story.
        Finding main = crash.findings().isEmpty() ? null : crash.findings().get(0);
        boolean knockOn = main == null || main.code().equals("knock-on-crash") || main.code().equals("crash")
                || main.title().startsWith("Mod didn't load: ");
        // Many mods failing with the same error share one cause (often a broken mixin), not each its own.
        Map<String, Long> sameError = new java.util.HashMap<>();
        failed.values().stream().filter(java.util.Objects::nonNull).forEach(e -> sameError.merge(e, 1L, Long::sum));
        failed.values().removeIf(e -> e != null && sameError.get(e) > 1);
        if (!knockOn || failed.isEmpty() || failed.size() > 3) return crash;
        String said = crash.findings().stream().map(f -> f.title() + " " + f.detail() + " " + f.files())
                .reduce("", String::concat).toLowerCase(java.util.Locale.ROOT);
        List<Finding> first = new ArrayList<>();
        for (Map.Entry<String, String> f : failed.entrySet()) {
            String id = f.getKey();
            if (said.contains("(" + id + ")") || said.contains(id + "-") || said.contains("mod: " + id)) continue;
            String name = logModName(log, id);
            String msg = name + " (" + id + ") has failed to load correctly" + (f.getValue() == null ? ""
                    : " " + f.getValue());
            String jar = logJar(log, id);
            Finding base = loadingIssue(msg, jar == null ? List.of() : List.of(jar));
            first.add(new Finding(base.severity(), base.code(), base.title(), base.detail()
                    + "\nThe crash report blames something else, but that's a knock-on: the game log shows " + name
                    + " failed first.", base.fix(), base.files()));
        }
        if (first.isEmpty()) return crash;
        List<Finding> all = new ArrayList<>(first);
        all.addAll(crash.findings());
        return new Explanation(crash.description(), crash.exception(), all);
    }

    /** The error under a log line: the last "Caused by:" of its stack trace, or the first line. */
    private static String logError(String[] lines, int at) {
        String error = null;
        for (int j = at + 1; j < lines.length && j < at + 400 && !LOG_LINE.matcher(lines[j]).find(); j++) {
            String l = lines[j].strip();
            if (j == at + 1 && !l.startsWith("at ")) error = l;
            else if (l.startsWith("Caused by: ")) error = l.substring("Caused by: ".length());
        }
        return error;
    }

    /** "\t\tCE Test Crash Mod 1.0.0 (cecrash)" from the mod list at the top of the log, or the id. */
    private static String logModName(String log, String id) {
        Matcher m = Pattern.compile("(?m)^\\t\\t(.+?) \\S+ \\(" + Pattern.quote(id) + "\\)\\s*$").matcher(log);
        return m.find() ? m.group(1).strip() : id;
    }

    /** "TRANSFORMER/cecrash@1.0.0/... ~[cecrash-1.0.0.jar%23147!/:?]": the mod's jar, or null. */
    private static String logJar(String log, String id) {
        Matcher m = Pattern.compile("/" + Pattern.quote(id) + "@[^/]+/.*?~\\[([^\\]%!]+\\.jar)").matcher(log);
        return m.find() ? m.group(1) : null;
    }

    /** Explains a Forge latest.log that stopped on missing or wrong-version mods, or returns null if it didn't. */
    public static Explanation translateLog(String log) {
        if (!log.contains(FORGE_DEPS_MARKER)) return null;
        List<Finding> issues = new ArrayList<>();
        Matcher m = FORGE_MISSING.matcher(log);
        java.util.Set<String> seen = new java.util.HashSet<>();
        while (m.find()) {
            if (!seen.add(m.group(1) + "/" + m.group(2))) continue; // Forge repeats the list
            String mod = m.group(1), by = m.group(2), range = m.group(3), actual = m.group(4);
            String wants = range.isEmpty() || range.equals("*") ? "any version"
                    : com.guiltypotato.checkengine.core.version.VersionRange.parseLenient(range).describe();
            if (actual.equals("[MISSING]")) {
                issues.add(new Finding(Severity.ERROR, Finding.MISSING_DEPENDENCY, "Missing mod: " + mod,
                        by + " needs " + mod + " (" + wants + "), but it isn't installed.",
                        "Install " + mod + " (" + wants + "), or remove " + by + ".", List.of()));
            } else {
                issues.add(new Finding(Severity.ERROR, Finding.WRONG_VERSION, "Wrong version of " + mod + " for " + by,
                        by + " needs " + mod + " " + wants + ", but you have " + actual + ".",
                        "Get a version of " + mod + " in that range, or a version of " + by + " that matches "
                                + actual + ".", List.of()));
            }
        }
        if (issues.isEmpty()) return null;
        return new Explanation("Forge stopped loading: missing or wrong-version mods (from logs/latest.log)", null,
                mergeMissing(issues));
    }

    public static Explanation translate(String report) {
        // A silent crash (no crash report): Java's hs_err_pid file instead.
        if (NativeCrash.isNativeCrash(report)) return NativeCrash.explain(report);
        String[] lines = tidy(report).split("\n");
        String description = null;
        String exception = null;
        int exceptionLine = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].startsWith("Description: ")) {
                description = lines[i].substring("Description: ".length()).strip();
                for (int j = i + 1; j < lines.length; j++) {
                    if (!lines[j].isBlank()) {
                        exception = lines[j].strip();
                        exceptionLine = j;
                        break;
                    }
                }
                break;
            }
        }

        Finding race = timingCrash(lines);
        if (race != null) return new Explanation(description, exception, new ArrayList<>(List.of(race)));
        List<Finding> issues = loadingIssues(lines);
        if (issues.isEmpty()) issues = errorList(lines);
        List<Finding> findings = mergeMissing(issues);
        if (findings.isEmpty() && exception != null) {
            findings.add(runtimeCrash(lines, exceptionLine, description, exception));
        }
        if (findings.isEmpty()) {
            findings.add(new Finding(Severity.WARNING, "unknown-crash", "Couldn't read this crash report",
                    "It doesn't look like a Minecraft crash report (no \"Description:\" line).",
                    "Pass a file from the crash-reports folder.", List.of()));
        }
        return new Explanation(description, exception, findings);
    }

    /** One finding per missing mod, listing every mod that needs it, instead of one per (mod, dependency) pair. */
    private static List<Finding> mergeMissing(List<Finding> issues) {
        Map<String, List<Finding>> byTitle = new LinkedHashMap<>();
        for (Finding f : issues) byTitle.computeIfAbsent(f.title(), k -> new ArrayList<>()).add(f);
        List<Finding> out = new ArrayList<>();
        for (List<Finding> group : byTitle.values()) {
            Finding first = group.get(0);
            if (group.size() == 1 || !first.code().equals(Finding.MISSING_DEPENDENCY)) {
                out.addAll(group);
                continue;
            }
            String mod = first.title().substring("Missing mod: ".length());
            List<String> files = group.stream().flatMap(f -> f.files().stream()).distinct().toList();
            out.add(new Finding(Severity.ERROR, Finding.MISSING_DEPENDENCY,
                    "Missing mod: " + mod + " (" + group.size() + " mods need it)",
                    String.join("\n", group.stream().map(Finding::detail).distinct().toList()),
                    "Install " + mod + ", or remove the mods that need it.", files));
        }
        return out;
    }

    /** "-- Mod loading issue for: x --" blocks: NeoForge already knows exactly what's wrong, we just reword it. */
    private static List<Finding> loadingIssues(String[] lines) {
        List<Finding> out = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            // NeoForge: "-- Mod loading issue for: x --". Forge 1.20.1: "-- MOD x --".
            if (!lines[i].startsWith("-- Mod loading issue") && !lines[i].startsWith("-- MOD ")) continue;
            String modFile = null;
            StringBuilder message = new StringBuilder();
            boolean inMessage = false;
            for (int j = i + 1; j < lines.length && !lines[j].startsWith("-- "); j++) {
                String l = lines[j].strip();
                if (l.startsWith("Mod file:") || l.startsWith("Mod File:")) {
                    modFile = fileName(l.substring("Mod file:".length()).strip());
                } else if (l.startsWith("Failure message:")) {
                    message.append(l.substring("Failure message:".length()).strip());
                    inMessage = true;
                } else if (l.startsWith("Mod version:") || l.startsWith("Mod Version:") || l.startsWith("Mod issues URL:")
                        || l.startsWith("Mod Issue URL:") || l.startsWith("Exception message:")) {
                    inMessage = false;
                } else if (inMessage && !l.isEmpty()) {
                    message.append(' ').append(l);
                }
            }
            String msg = message.toString().replaceAll("\\s+", " ").strip();
            // Check Engine's own notes on NeoForge's screen end up in the report too; NeoForge's own issue says the same.
            if (msg.startsWith("Check Engine")) continue;
            List<String> files = modFile == null ? List.of() : List.of(modFile);
            out.add(loadingIssue(msg, files));
        }
        return out;
    }

    /**
     * NeoForge 26.1 lists the errors under the exception instead of in "-- Mod loading issue --" blocks:
     * "ModLoadingException: Loading errors encountered:" then "\t- message" with "\t  more" lines, until
     * "Loading warnings encountered:" or a blank line. Only the errors count.
     */
    private static List<Finding> errorList(String[] lines) {
        List<Finding> out = new ArrayList<>();
        int start = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].endsWith("Loading errors encountered:")) {
                start = i + 1;
                break;
            }
        }
        if (start < 0) return out;
        StringBuilder message = null;
        for (int i = start; i <= lines.length; i++) {
            String l = i < lines.length ? lines[i] : "";
            boolean item = l.startsWith("\t- ");
            boolean more = l.startsWith("\t  ") && message != null;
            if (more) {
                message.append(' ').append(l.strip());
                continue;
            }
            if (message != null) {
                String msg = message.toString().replaceAll("\\s+", " ").strip();
                if (!msg.startsWith("Check Engine")) out.add(loadingIssue(msg, List.of()));
                message = null;
            }
            if (!item) break;
            message = new StringBuilder(l.substring(3));
        }
        return out;
    }

    private static Finding loadingIssue(String msg, List<String> files) {
        Matcher m;
        if ((m = REQUIRES_MISSING.matcher(msg)).find()) {
            return new Finding(Severity.ERROR, Finding.MISSING_DEPENDENCY, "Missing mod: " + m.group(2),
                    m.group(1) + " needs " + m.group(2) + " (" + versionText(m.group(3)) + "), but it isn't installed.",
                    "Install " + m.group(2) + " (" + versionText(m.group(3)) + "), or remove " + m.group(1) + ".", files);
        }
        if ((m = REQUIRES_NEWER.matcher(msg)).find()
                && (m.group(2).equals("minecraft") || m.group(2).equals("neoforge"))) {
            String platform = m.group(2).equals("minecraft") ? "Minecraft" : "NeoForge";
            return new Finding(Severity.ERROR, Finding.WRONG_VERSION, "Wrong " + platform + " version for " + m.group(1),
                    m.group(1) + " is made for " + platform + " " + versionText(m.group(3)) + ", but this pack has "
                            + m.group(4) + ".",
                    "Get the version of " + m.group(1) + " made for " + platform + " " + m.group(4)
                            + " (check its Files tab), or remove it.", files);
        }
        if ((m = REQUIRES_NEWER.matcher(msg)).find()) {
            return new Finding(Severity.ERROR, Finding.WRONG_VERSION, "Outdated mod: " + m.group(2),
                    m.group(1) + " needs " + m.group(2) + " " + versionText(m.group(3)) + ", but you have "
                            + m.group(4) + ".",
                    "Update " + m.group(2) + ".", files);
        }
        if ((m = ONLY_SUPPORTS.matcher(msg)).find()) {
            return new Finding(Severity.ERROR, Finding.WRONG_VERSION,
                    "Wrong version of " + m.group(2) + " for " + m.group(1),
                    m.group(1) + " only works with " + m.group(2) + " " + m.group(3) + ", but you have "
                            + m.group(4) + ".",
                    "Get a version of " + m.group(2) + " in that range, or update " + m.group(1)
                            + " to a version that supports " + m.group(2) + " " + m.group(4) + ".", files);
        }
        if ((m = INCOMPATIBLE.matcher(msg)).find()) {
            String why = m.group(4) != null ? "\nThe mod author says: " + m.group(4) : "";
            return new Finding(Severity.ERROR, Finding.INCOMPATIBLE_MOD,
                    m.group(1) + " doesn't work with " + m.group(2),
                    m.group(1) + " refuses to load while " + m.group(2) + " " + m.group(3) + " is installed." + why,
                    "Remove one of them.", files);
        }
        if ((m = CLASS_MISSING.matcher(msg)).find()) {
            String needs = HiddenDependencies.modForClass(m.group(3));
            if (needs != null) {
                return new Finding(Severity.ERROR, Finding.MISSING_DEPENDENCY,
                        "Hidden missing mod: " + m.group(1) + " uses " + needs,
                        m.group(1) + " uses " + needs + "'s code, but " + needs + " isn't installed. The mod doesn't "
                                + "list " + needs + " as something it needs, so NeoForge couldn't warn you.",
                        "Install " + needs + ", or remove " + m.group(1) + ".", files);
            }
            return new Finding(Severity.ERROR, "loading-issue", m.group(1) + " is missing code it needs",
                    m.group(1) + " tried to use " + m.group(3) + ", which isn't in the pack. It probably needs another "
                            + "mod that isn't installed, or a different version of one.",
                    "Check " + m.group(1) + "'s CurseForge page for the mods it needs, or remove it.", files);
        }
        if ((m = FORGE_FILE.matcher(msg)).find()) {
            String f = fileName(m.group(1));
            return new Finding(Severity.ERROR, Finding.WRONG_LOADER, "Forge mod in a NeoForge pack: " + f,
                    f + " is made for Forge (or an old NeoForge), so this version of NeoForge can't load it.",
                    "Swap it for the NeoForge version for your Minecraft version, or remove it.", List.of(f));
        }
        if ((m = FABRIC_FILE.matcher(msg)).find()) {
            String f = fileName(m.group(1));
            return new Finding(Severity.ERROR, Finding.WRONG_LOADER, "Fabric mod in a NeoForge pack: " + f,
                    f + " is a Fabric mod. NeoForge can't load it on its own.",
                    "Swap it for the NeoForge version, or remove it.", List.of(f));
        }
        Matcher reporter = REPORTER.matcher(msg);
        Finding duplicate = reporter.find()
                ? duplicateRegistration(msg, reporter.group(1), reporter.group(2), files)
                : duplicateRegistration(msg, null, null, files);
        if (duplicate != null) return duplicate;
        Finding more = moreLoadingIssues(msg, files);
        if (more != null) return more;
        if ((m = FAILED_TO_LOAD.matcher(msg)).find()) {
            String error = m.group(3) == null ? "" : " Its error: " + m.group(3);
            return new Finding(Severity.ERROR, "mod-crashed", m.group(1) + " crashed while loading",
                    m.group(1) + " (" + m.group(2) + ") hit an error while the game was starting up, so loading stopped."
                            + error,
                    updateOrRemove(m.group(1), "its author"), files);
        }
        return new Finding(Severity.ERROR, "loading-issue", "Mod loading problem", msg,
                files.isEmpty() ? STRIP_MINE_HINT : "Update or remove " + files.get(0) + ".", files);
    }

    /** "Create (create) encountered an error while dispatching the ... event" / "during the common_setup event phase". */
    private static final Pattern EVENT_ERROR = Pattern.compile(
            "^(.+?) \\(([a-z0-9_.-]+)\\) encountered an error (?:while dispatching the \\S+?([A-Za-z]+Event) event"
                    + "|during the (\\S+) event phase)(?: (?:[a-z0-9_]+\\.)*([A-Za-z0-9_$]+(?:Exception|Error)\\b.*))?");
    /** "KubeUI (kubeui) has class loading errors java.lang.UnsupportedClassVersionError: ... (class file version 65.0)" */
    private static final Pattern NEWER_JAVA = Pattern.compile(
            "^(.+?)(?: \\(([a-z0-9_.-]+)\\))? has class loading errors.*UnsupportedClassVersionError.*?class file version (\\d+)");
    /** "Attempted to load class net/minecraft/client/... for invalid dist DEDICATED_SERVER", or a missing client class. */
    private static final Pattern SERVER_DIST = Pattern.compile(
            "invalid dist DEDICATED_SERVER|NoClassDefFoundError: net/minecraft/client/");
    private static final Pattern MOD_NAME = Pattern.compile("^(.+?)(?: \\(([a-z0-9_.-]+)\\))? (?:has|encountered)");
    /** "File mods\Textile-0.12.0+1.21.1-neoforge.jar is not a valid mod file" */
    private static final Pattern NOT_A_MOD = Pattern.compile("File (.+?) is not a valid mod file");
    /** "An uncaught parallel processing error has occurred. java.lang.NoClassDefFoundError: net/x/y/Z" */
    private static final Pattern PARALLEL_CLASS = Pattern.compile(
            "parallel processing error.*?(?:NoClassDefFoundError|ClassNotFoundException): (\\S+)");
    /** Sinytra Connector running a Fabric mod: "... due to errors, provided by 'paradise_lost'" */
    private static final Pattern CONNECTOR = Pattern.compile("\\[Connector\\].*?provided by '([a-z0-9_.-]+)'");

    /** Loading errors seen in real crash reports (the crash library) that the rules above don't cover. */
    private static Finding moreLoadingIssues(String msg, List<String> files) {
        Matcher m;
        if ((m = NEWER_JAVA.matcher(msg)).find()) {
            int java = Integer.parseInt(m.group(3)) - 44; // class file 65 = Java 21
            return new Finding(Severity.ERROR, "wrong-java", m.group(1) + " needs Java " + java,
                    m.group(1) + " was built for Java " + java + ", but the game runs an older Java.",
                    "In the launcher, pick Java " + java + " for this pack. Or get a version of " + m.group(1)
                            + " made for your Minecraft version.", files);
        }
        if (SERVER_DIST.matcher(msg).find()) {
            Matcher n = MOD_NAME.matcher(msg);
            String mod = n.find() ? n.group(1) : null;
            return new Finding(Severity.ERROR, Finding.CLIENT_ONLY_ON_SERVER,
                    mod != null ? "Client-only mod on the server: " + mod : "Client-only mod on the server",
                    (mod != null ? mod : "A mod") + " uses game code that only exists on the player's side (graphics, "
                            + "screens), so it can't run on a server.",
                    "Remove " + (mod != null ? mod : "it") + " from the server. Players keep it in their own pack. "
                            + "Check Pack.bat's serverpack command builds a server copy without these mods.", files);
        }
        if ((m = EVENT_ERROR.matcher(msg)).find()) {
            String when = m.group(3) != null ? m.group(3) : m.group(4).replace('_', ' ');
            String error = m.group(5) == null ? "" : " Its error: " + m.group(5);
            return new Finding(Severity.ERROR, "mod-crashed", m.group(1) + " crashed while loading",
                    m.group(1) + " (" + m.group(2) + ") hit an error while setting up (" + when + "), so loading "
                            + "stopped." + error, updateOrRemove(m.group(1), "its author"), files);
        }
        if ((m = NOT_A_MOD.matcher(msg)).find()) {
            String f = fileName(m.group(1));
            return new Finding(Severity.ERROR, Finding.BROKEN_JAR, f + " isn't a valid mod file",
                    "The loader can't read " + f + ": the download is broken, or it's made for another loader "
                            + "(Fabric, Forge) or another Minecraft version.",
                    "Download " + f + " again, picking the file for your loader and Minecraft version, or remove it.",
                    List.of(f));
        }
        if ((m = CONNECTOR.matcher(msg)).find()) {
            return new Finding(Severity.ERROR, "mod-crashed", "Fabric mod " + m.group(1) + " crashed (through Connector)",
                    m.group(1) + " is a Fabric mod running through Sinytra Connector, and it failed while starting.",
                    "Remove " + m.group(1) + ", or look for a NeoForge version of it. Fabric mods through Connector "
                            + "don't always work.", files);
        }
        if ((m = PARALLEL_CLASS.matcher(msg)).find()) {
            String cls = m.group(1).replace('.', '/');
            String needs = HiddenDependencies.modForClass(cls);
            String pkg = cls.contains("/") ? cls.substring(0, cls.lastIndexOf('/')).replace('/', '.') : cls;
            return new Finding(Severity.ERROR, needs != null ? Finding.MISSING_DEPENDENCY : "loading-issue",
                    needs != null ? "A mod needs " + needs + ", which isn't installed" : "A mod is missing code it needs",
                    "While loading, a mod tried to use " + cls + " (from " + pkg + "), which isn't in the pack.",
                    needs != null ? "Install " + needs + "." : "Install the mod that " + pkg + " belongs to, or update "
                            + "the mods that need it. " + STRIP_MINE_HINT, files);
        }
        return null;
    }

    /**
     * Crash reports people paste get mangled: every line quoted ("| ", "> ", non-breaking spaces from chat apps), or the
     * whole report squashed onto one line by a web form. Put the lines back so the rules can read them.
     */
    static String tidy(String report) {
        String text = report.replace("\r", "").replace(' ', ' ');
        String[] lines = text.split("\n");
        if (lines.length < 5 && text.length() > 200 && text.contains("Description:")) {
            // Squashed: the line breaks became double spaces. Stack frames are "at ..." after a space.
            text = text.replaceAll(" {2,}", "\n").replaceAll(" (at [\\w$.]+[.(])", "\n\t$1")
                    .replaceAll(" (Caused by: )", "\n$1").replaceAll(" (-- .+? --)", "\n$1");
            lines = text.split("\n");
        }
        StringBuilder out = new StringBuilder(text.length());
        for (String l : lines) out.append(l.replaceFirst("^ *(?:[|>] ?)? *", "")).append('\n');
        // Half-squashed: "Time: ... Description: ..." on one line.
        return out.toString().replaceAll("(\\S) +(Description: )", "$1\n$2");
    }

    /** The fix when Crash Doctor can't name the mod: Strip Mine finds it by switching mods off. */
    public static final String STRIP_MINE_HINT = "Check Engine couldn't tell which mod did this, but Strip Mine can find "
            + "it: press Strip Mine (or run Check Pack.bat). About 9 launches on a 400-mod pack, fewer if you changed "
            + "mods recently.";

    /** A crash while playing or starting up: work out which mod, and recognise the common cases. */
    private static Finding runtimeCrash(String[] lines, int exceptionLine, String description, String exception) {
        // The whole exception section, including "Caused by:" lines, up to the detailed walkthrough.
        StringBuilder trace = new StringBuilder();
        Set<String> stackMods = new LinkedHashSet<>();
        for (int i = exceptionLine; i < lines.length && !lines[i].startsWith("A detailed walkthrough"); i++) {
            trace.append(lines[i]).append('\n');
            Matcher m = FRAME_MOD.matcher(lines[i]);
            if (m.find() && !NOT_CULPRITS.contains(m.group(1))) stackMods.add(m.group(1));
        }
        String all = trace.toString();
        // Deepest "Caused by: ... from mod x" wins: that's the mod whose code patch failed.
        String mixinMod = null;
        for (String l : all.split("\n")) {
            if (!l.startsWith("Caused by:")) continue;
            Matcher fm = FROM_MOD.matcher(l);
            if (fm.find()) mixinMod = fm.group(1);
        }
        Map<String, String> suspects = suspects(lines);
        String culpritId = !stackMods.isEmpty() ? stackMods.iterator().next()
                : suspects.isEmpty() ? null : suspects.keySet().iterator().next();
        String culprit = culpritId == null ? null
                : suspects.containsKey(culpritId) ? suspects.get(culpritId) + " (" + culpritId + ")" : culpritId;
        String where = culprit == null ? "" : "\nThe crash happened inside " + culprit + ".";
        String suspectLine = suspects.isEmpty() ? "" : "\nMods involved: " + String.join(", ", suspects.values());

        if (all.contains("OutOfMemoryError")) {
            return new Finding(Severity.ERROR, "out-of-memory", "Minecraft ran out of memory",
                    "The game used all the RAM it was given and crashed.",
                    "Give it more RAM in your launcher (CurseForge: Settings > Minecraft > Java Settings). "
                            + "Big packs usually want 8 to 12 GB. Don't give it more than about half your PC's RAM.",
                    List.of());
        }
        if (all.contains("UnsupportedClassVersionError")) {
            return new Finding(Severity.ERROR, "wrong-java", "Wrong Java version",
                    "A mod was built for a newer Java than the one running the game." + where,
                    "Minecraft 1.21.1 needs Java 21. Let your launcher pick Java automatically, or point it at Java 21.",
                    List.of());
        }
        Matcher na = NOT_AVAILABLE.matcher(all);
        if (na.find()) {
            String mod = na.group(1);
            return new Finding(Severity.ERROR, Finding.MISSING_DEPENDENCY, "Mod didn't load: " + mod,
                    "Another mod asked for " + mod + " while the game was starting, but " + mod
                            + " isn't loaded. It's either missing, the wrong version, or it failed to load."
                            + suspectLine,
                    "Check that " + mod + " is installed and matches your Minecraft and NeoForge version. "
                            + "Running Check Engine's scan on the pack will spot a missing or wrong-version " + mod + ".",
                    List.of());
        }
        if (all.contains("Cannot get config value before config is loaded")
                || (all.contains("KeyMapping") && all.contains("is null"))) {
            return new Finding(Severity.ERROR, "knock-on-crash", "Knock-on crash: something else failed first",
                    "This crash is a side effect. Loading already went wrong earlier, so " + (culprit != null
                            ? culprit : "this mod") + " tried to use a setting or keybind that never got set up. "
                            + "It's usually not that mod's fault.",
                    "Run Check Engine's scan on the pack to find missing, duplicate or wrong-version mods and fix "
                            + "those first. If it finds nothing: " + STRIP_MINE_HINT,
                    List.of());
        }
        if (all.contains("Multiple servers running at once")) {
            return new Finding(Severity.ERROR, "knock-on-crash", "Leftover from an earlier crash",
                    "A world was already open in this session (probably one that crashed), and " + (culprit != null
                            ? culprit : "a mod") + " doesn't support starting a second one.",
                    "Close the game completely and start it again. If the first world crashed, translate that "
                            + "crash report instead.", List.of());
        }
        if ((all.contains("NoClassDefFoundError") || all.contains("ClassNotFoundException"))
                && (all.contains("net/minecraft/client") || all.contains("net.minecraft.client"))) {
            return new Finding(Severity.ERROR, Finding.CLIENT_ONLY_ON_SERVER, "Client-only mod on the server",
                    "A mod tried to use screen or rendering code, which doesn't exist on a dedicated server." + where,
                    "Remove " + (culprit != null ? culprit : "that mod") + " from the server. Players keep it.",
                    List.of());
        }
        if (all.contains("MixinApplyError") || all.contains("InvalidInjectionException")
                || all.contains("MixinTransformerError") || all.contains("InjectionError")) {
            if (mixinMod != null) {
                culprit = suspects.containsKey(mixinMod) ? suspects.get(mixinMod) + " (" + mixinMod + ")" : mixinMod;
                where = "\nThe patch that failed belongs to " + culprit + ".";
            }
            List<String> others = new ArrayList<>();
            for (String id : mixinRivals(lines, all, mixinMod != null ? mixinMod : culpritId)) {
                others.add(suspects.containsKey(id) ? suspects.get(id) + " (" + id + ")" : id);
            }
            if (culprit != null && !others.isEmpty()) {
                String rival = String.join(" or ", others);
                return new Finding(Severity.ERROR, "mixin-conflict",
                        "Two mods changed the same game code: " + culprit + " and " + rival,
                        culprit + "'s code patch (mixin) couldn't be applied, because " + rival + " also changes "
                                + "that part of the game." + suspectLine,
                        "Update both: one of them may already have a fix for this clash. If that doesn't help, "
                                + "remove " + culprit + " or " + rival + ".", List.of());
            }
            return new Finding(Severity.ERROR, "mixin-conflict", "Two mods changed the same game code",
                    "A mod's code patch (mixin) couldn't be applied, usually because another mod or a different "
                            + "game version changed that code first." + where + suspectLine,
                    "Update " + (culprit != null ? culprit : "the mods involved")
                            + ". If that doesn't help, remove it or the mod it clashes with.", List.of());
        }
        // The mod at the top of the stack isn't necessarily the one registering, so only the name itself counts here.
        Finding duplicate = duplicateRegistration(all, null, null, List.of());
        if (duplicate != null) return duplicate;
        if (description != null && description.startsWith("Ticking")) {
            String what = detailValue(lines, "Entity Type:", "Name:");
            String at = detailValue(lines, "Entity's Exact location:", "Block location:");
            return new Finding(Severity.ERROR, "ticking-crash", description,
                    "Something in the world crashes every time it updates"
                            + (what != null ? ": " + what : "") + (at != null ? " at " + at : "") + "." + where,
                    "Update " + (culprit != null ? culprit : "the mod that adds it")
                            + ". To get the world back: Rescue world in Crash Doctor backs it up and removes just "
                            + "that one, or remove it yourself with a world editor (MCA Selector, NBTExplorer).",
                    List.of());
        }
        return new Finding(Severity.ERROR, "crash", culprit != null ? "Crash in " + culprit : "Game crashed",
                "Minecraft crashed with: " + exception + where + suspectLine,
                culprit != null
                        ? updateOrRemove(culprit, "its issue tracker")
                        : STRIP_MINE_HINT + " Or send this crash report to the pack or mod authors.",
                List.of());
    }

    /**
     * Mods are set up in parallel, and some add to a shared list without locking it. When two do it at the same moment
     * the list breaks: an ArrayIndexOutOfBoundsException inside ArrayList.add (or a ConcurrentModificationException)
     * on a loading worker thread. That depends on timing, so the next launch usually works. Null if it isn't one.
     */
    static Finding timingCrash(String[] lines) {
        boolean broken = false, parallel = false;
        String jar = null;
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i];
            if (l.contains("ConcurrentModificationException")
                    || (l.contains("ArrayIndexOutOfBoundsException") && nextFrameIsList(lines, i))) {
                broken = true;
            }
            if (broken && jar == null && l.strip().startsWith("at ") && !l.contains("at java.")) {
                Matcher j = FRAME_JAR.matcher(l);
                if (j.find()) jar = j.group(1);
            }
            if (l.contains("ForkJoinWorkerThread") || l.contains("modloading-worker")) parallel = true;
        }
        if (!broken || !parallel) return null;
        String mod = null;
        for (String l : lines) {
            Matcher m = REPORTER.matcher(l.strip().replaceFirst("^Failure message: ", ""));
            if (m.find()) {
                mod = m.group(1);
                break;
            }
        }
        String who = mod != null ? mod : "a mod";
        String lib = jar != null && (mod == null || !jar.toLowerCase().contains(mod.toLowerCase())) ? jar : null;
        return new Finding(Severity.ERROR, Finding.TIMING_CRASH, "Random timing crash while loading"
                + (mod != null ? " (" + mod + ")" : ""),
                "Mods are set up at the same time to load faster. " + who + " and another mod added to the same list "
                        + "at the same moment" + (lib != null ? " (in " + lib + ")" : "") + ", and it broke. This "
                        + "depends on timing, so it happens at random and the next launch usually works.",
                "Launch again. If it keeps crashing like this, update " + who + (lib != null ? " and " + lib : "")
                        + ".", List.of());
    }

    /** Whether the first stack frame after line i (skipping a "Stacktrace:" header) is inside java.util.ArrayList. */
    private static boolean nextFrameIsList(String[] lines, int i) {
        for (int j = i + 1; j < lines.length && j <= i + 2; j++) {
            String l = lines[j].strip();
            if (l.equals("Stacktrace:")) continue;
            return l.startsWith("at java.util.ArrayList.");
        }
        return false;
    }

    /**
     * "Registered twice" crashes. Each mod registers each block, item or setting once, so a second registration means
     * either the mod runs twice (two copies, or something made its setup run again) or another mod adds a thing with
     * the same name. Null if the text isn't one.
     */
    private static Finding duplicateRegistration(String text, String reporterName, String reporterId, List<String> files) {
        Matcher d = DUPLICATE.matcher(text);
        if (!d.find()) return null;
        String id = d.group(1) != null ? d.group(1) : d.group(2) != null ? d.group(2) : d.group(3);
        if (id == null) id = (reporterId != null ? reporterId : "?") + ":" + d.group(4);
        String owner = id.substring(0, id.indexOf(':'));
        String who = reporterName != null ? reporterName : owner;
        String title = "Registered twice: " + id;
        if (reporterId == null || reporterId.equals(owner) || owner.equals("?")) {
            return new Finding(Severity.ERROR, "duplicate-registration", title,
                    who + " tried to add " + id + " a second time. A mod adds each block, item or setting once, so "
                            + "either " + who + " is installed twice, or another mod made its setup run twice (a mod "
                            + "that changes how mods load, or one added or updated since the pack last worked).",
                    "Look in the mods folder for two copies of " + who + " and remove one. If there's only one, "
                            + "remove the mods added or updated since the pack last worked, one at a time, until it "
                            + "starts.", files);
        }
        return new Finding(Severity.ERROR, "duplicate-registration", title,
                reporterName + " tried to add " + id + ", but that name belongs to " + owner + ", which already "
                        + "added it. Usually " + reporterName + " is made for a different version of " + owner
                        + ", or both mods add the same thing.",
                "Update " + reporterName + " to the version made for your " + owner + ", or remove " + reporterName
                        + ".", files);
    }

    /**
     * The other mods in a mixin clash: the ones a log line says got there first ("already redirected by x.mixins.json",
     * "previously written by com.x.mixin.Y"), and the ones that also patched the target class in the stack trace.
     */
    private static Set<String> mixinRivals(String[] lines, String all, String culpritId) {
        Set<String> out = new LinkedHashSet<>();
        Matcher f = MIXIN_FIRST.matcher(all);
        while (f.find()) {
            String mod = modFromMixinRef(f.group(1));
            if (mod != null) out.add(mod);
        }
        Matcher t = MIXIN_TARGET.matcher(all);
        if (t.find()) {
            String target = t.group(1).replace('/', '.');
            for (String l : lines) {
                if (!l.contains(target + ".") && !l.contains(target + "(")) continue;
                Matcher fm = FROM_MOD.matcher(l);
                while (fm.find()) out.add(fm.group(1));
            }
        }
        out.remove(culpritId);
        out.removeAll(NOT_CULPRITS);
        return out;
    }

    /** "supplementaries.mixins.json" -> supplementaries, "me.jellysquid.mods.sodium.mixin.Foo" -> sodium. */
    public static String modFromMixinRef(String ref) {
        int json = ref.indexOf(".json");
        if (json > 0) {
            String name = ref.substring(0, json);
            if (name.startsWith("mixins.")) name = name.substring("mixins.".length());
            name = name.split("[.]")[0].replaceAll("[-_](common|client|neoforge|forge|fabric|mixins?)$", "");
            return name.isEmpty() ? null : name.toLowerCase();
        }
        String[] parts = ref.split("[.]");
        for (int i = 1; i < parts.length; i++) {
            if (parts[i].equals("mixin") || parts[i].equals("mixins")) return parts[i - 1].toLowerCase();
        }
        return null;
    }

    /**
     * Fix for a crash inside one mod. The player may already have its newest version, so updating is only the first
     * step: "Update X." alone sends them looking for an update that doesn't exist.
     */
    private static String updateOrRemove(String mod, String reportTo) {
        return "If there's a newer version of " + mod + ", update it. If you already have the newest one, remove "
                + mod + " or send this crash report to " + reportTo + ".";
    }

    /** "1.1.3 or above" stays, "any" becomes "any version". */
    private static String versionText(String range) {
        return range.equals("any") || range.matches("0(\\.0)* or above") ? "any version" : range;
    }

    /** id -> "Name" from the "Suspected Mods:" line, without Minecraft/NeoForge/crash-helper mods. */
    private static Map<String, String> suspects(String[] lines) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String l : lines) {
            String s = l.strip();
            if (!s.startsWith("Suspected Mods:") && !s.startsWith("Suspected Mod:")) continue;
            Matcher m = SUSPECT.matcher(s.substring(s.indexOf(':') + 1));
            while (m.find()) {
                if (!NOT_CULPRITS.contains(m.group(2))) out.put(m.group(2), m.group(1).strip());
            }
            break;
        }
        return out;
    }

    private static String detailValue(String[] lines, String... keys) {
        for (String l : lines) {
            String s = l.strip();
            for (String k : keys) if (s.startsWith(k)) return s.substring(k.length()).strip();
        }
        return null;
    }

    /** "/C:/x/mods/foo.jar" or "mods\foo.jar" -> "foo.jar" */
    private static String fileName(String path) {
        if (path.startsWith("<")) return null;
        int cut = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return path.substring(cut + 1);
    }
}
