package com.guiltypotato.packdoctor.core.crash;

import com.guiltypotato.packdoctor.core.scan.Finding;
import com.guiltypotato.packdoctor.core.scan.Finding.Severity;
import com.guiltypotato.packdoctor.core.scan.Report;
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
    /** Mixin errors name the mod whose patch failed: "...MixinLevelRenderer from mod epicfight->@Inject..." */
    private static final Pattern FROM_MOD = Pattern.compile("from mod ([a-z][a-z0-9_]*)");
    private static final Pattern NOT_AVAILABLE = Pattern.compile("Mod '([^']+)' is not available");

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
            StringBuilder sb = new StringBuilder("Pack Doctor crash translation\n");
            if (description != null) sb.append("Minecraft said: ").append(description).append('\n');
            if (exception != null) sb.append("Error: ").append(exception).append('\n');
            sb.append('\n');
            Report.appendFindings(sb, findings);
            return sb.toString();
        }
    }

    public static Explanation translate(String report) {
        String[] lines = report.replace("\r", "").split("\n");
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

        List<Finding> findings = new ArrayList<>(loadingIssues(lines));
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

    /** "-- Mod loading issue for: x --" blocks: NeoForge already knows exactly what's wrong, we just reword it. */
    private static List<Finding> loadingIssues(String[] lines) {
        List<Finding> out = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].startsWith("-- Mod loading issue")) continue;
            String modFile = null;
            StringBuilder message = new StringBuilder();
            boolean inMessage = false;
            for (int j = i + 1; j < lines.length && !lines[j].startsWith("-- "); j++) {
                String l = lines[j].strip();
                if (l.startsWith("Mod file:")) {
                    modFile = fileName(l.substring("Mod file:".length()).strip());
                } else if (l.startsWith("Failure message:")) {
                    message.append(l.substring("Failure message:".length()).strip());
                    inMessage = true;
                } else if (l.startsWith("Mod version:") || l.startsWith("Mod issues URL:")
                        || l.startsWith("Exception message:")) {
                    inMessage = false;
                } else if (inMessage && !l.isEmpty()) {
                    message.append(' ').append(l);
                }
            }
            List<String> files = modFile == null ? List.of() : List.of(modFile);
            out.add(loadingIssue(message.toString().replaceAll("\\s+", " ").strip(), files));
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
        return new Finding(Severity.ERROR, "loading-issue", "Mod loading problem", msg,
                files.isEmpty() ? null : "Update or remove " + files.get(0) + ".", files);
    }

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
                            + "Running Pack Doctor's scan on the pack will spot a missing or wrong-version " + mod + ".",
                    List.of());
        }
        if (all.contains("Cannot get config value before config is loaded")
                || (all.contains("KeyMapping") && all.contains("is null"))) {
            return new Finding(Severity.ERROR, "knock-on-crash", "Knock-on crash: something else failed first",
                    "This crash is a side effect. Loading already went wrong earlier, so " + (culprit != null
                            ? culprit : "this mod") + " tried to use a setting or keybind that never got set up. "
                            + "It's usually not that mod's fault.",
                    "Run Pack Doctor's scan on the pack to find missing, duplicate or wrong-version mods and fix "
                            + "those first. If it finds nothing, look for the first ERROR in logs/latest.log.",
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
            return new Finding(Severity.ERROR, "mixin-conflict", "Two mods changed the same game code",
                    "A mod's code patch (mixin) couldn't be applied, usually because another mod or a different "
                            + "game version changed that code first." + where + suspectLine,
                    "Update " + (culprit != null ? culprit : "the mods involved")
                            + ". If that doesn't help, remove it or the mod it clashes with.", List.of());
        }
        if (description != null && description.startsWith("Ticking")) {
            String what = detailValue(lines, "Entity Type:", "Name:");
            String at = detailValue(lines, "Entity's Exact location:", "Block location:");
            return new Finding(Severity.ERROR, "ticking-crash", description,
                    "Something in the world crashes every time it updates"
                            + (what != null ? ": " + what : "") + (at != null ? " at " + at : "") + "." + where,
                    "Update " + (culprit != null ? culprit : "the mod that adds it")
                            + ". To get the world back, remove that entity or block with a world editor (e.g. MCA "
                            + "Selector or NBTExplorer) after making a backup.", List.of());
        }
        return new Finding(Severity.ERROR, "crash", culprit != null ? "Crash in " + culprit : "Game crashed",
                "Minecraft crashed with: " + exception + where + suspectLine,
                culprit != null
                        ? "Update " + culprit + ". If it still crashes, remove it and send this crash report to its "
                                + "issue tracker."
                        : "Send this crash report to the pack or mod authors.",
                List.of());
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
