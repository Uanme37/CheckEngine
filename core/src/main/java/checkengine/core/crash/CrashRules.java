package checkengine.core.crash;

import checkengine.core.crash.CrashTranslator.Confidence;
import checkengine.core.crash.CrashTranslator.Explanation;
import checkengine.core.crash.CrashTranslator.Suspect;
import checkengine.core.scan.Finding;
import checkengine.core.scan.Finding.Severity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The 0.25 Mechanic rules: they read the stack and the report's own hints, then decide how sure the answer is
 * (Sure / Likely / Maybe / Not sure) and which mods are suspects, most likely first. They run after the older
 * rules and only change an answer when they know better.
 */
final class CrashRules {
    /** The game, the loaders, launchers and crash catchers: never named. */
    static final Set<String> CORE = Set.of("minecraft", "neoforge", "forge", "fml", "fml_loader", "javafml",
            "lowcodefml", "mclanguage", "mixinextras", "mixinsquared", "notenoughcrashes", "neruina", "bus", "eventbus",
            "miolaunchwrapper", "theseus", "launchwrapper", "java", "fabricloader", "fabric_loader", "datafixerupper");
    /**
     * Shared libraries other mods build on: in a crash they are usually where another mod's bad call ended up. Named
     * only when no other mod shows up, and then as Maybe.
     */
    static final Set<String> SHARED = Set.of("architectury", "cloth_config", "cloth-config", "geckolib", "citadel",
            "kotlinforforge", "kotlinfml", "thedarkcolour", "balm", "puzzleslib", "curios", "cupboard", "bookshelf",
            "moonlight", "zeta", "framework", "yungsapi", "terrablender", "resourcefulconfig", "resourcefullib",
            "monolib", "lodestone", "playeranimator", "player_animation_lib", "creativecore", "fzzy_config", "owo",
            "connectormod", "connector", "forgified_fabric_api", "fabric_api", "configured", "collective", "coroutil",
            "glitchcore", "lionfishapi", "azurelib", "smartbrainlib", "supermartijn642corelib", "cristellib",
            "platform", "prism", "ftblibrary", "rhino", "kotlinlangforge", "jamlib", "necronomicon", "ctm", "fusion",
            "iceberg", "ponder", "registrate", "flywheel", "insanelib", "veil", "sinytra", "mru", "trender",
            "placebo", "patchouli", "uranus", "konkrete", "fancymenu", "melody", "libipn", "kambrik");

    private CrashRules() {}

    static boolean isLibrary(String id) {
        return isCore(id) || SHARED.contains(id) || id.startsWith("fabric_") || id.startsWith("fabric-");
    }

    static boolean isCore(String id) {
        return id == null || id.isEmpty() || CORE.contains(id);
    }

    /** The jar a Forge 1.20.1 frame comes from: "~[from-another-world-1.2A.1-forge.jar%23211!/:?]". */
    private static final Pattern FRAME_JAR = Pattern.compile("~\\[([^\\]%!/]+?\\.jar)");
    /** Jars that are the game, the loader or Java libraries, never a mod. */
    private static final Pattern GAME_JAR = Pattern.compile("(?i)(client|server|minecraft|forge|neoforge|fmlcore|fmlloader"
            + "|javafmllanguage|lowcodelanguage|mclanguage|modlauncher|securejarhandler|bootstraplauncher|mixin"
            + "|mixinextras|datafixerupper|authlib|guava|netty|lwjgl|jopt|brigadier|fastutil|log4j|gson|commons|slf4j"
            + "|asm|night-config|eventbus|coremods|accesstransformers|unsafe|jarjar|srgutils|spi|loader|events"
            + "|core|fml|universal)(-[a-z]+)?-\\d[\\w.+-]*\\.jar|[^ ]*-srg\\.jar");
    /** A row of the report's mod list: "from-another-world-1.2A.1-forge.jar |From Another World |fromanotherworld |". */
    private static final Pattern MOD_ROW = Pattern.compile("^\\s*(\\S+\\.jar)\\s*\\|[^|]*\\|\\s*([a-z0-9_.-]+)\\s*\\|");

    /** jar file name (lower case) to mod id, from the report's mod list. */
    static Map<String, String> jarIds(String[] lines) {
        Map<String, String> out = new HashMap<>();
        for (String l : lines) {
            Matcher m = MOD_ROW.matcher(l);
            if (m.find()) out.put(m.group(1).toLowerCase(Locale.ROOT), m.group(2));
        }
        return out;
    }

    /** The mod one stack frame belongs to: "TRANSFORMER/modid@1.0/..." or a mod jar named in "~[...jar]", or null. */
    static String frameMod(String line, Map<String, String> jars) {
        if (!line.strip().startsWith("at ")) return null;
        Matcher m = CrashTranslator.FRAME_MOD.matcher("\t" + line.strip()); // tidy() took the spaces off
        if (m.find() && !m.group(1).contains(".")) return m.group(1);
        // The game's own code, whatever the jar is called: launchers rename the game jar ("MyModpack-HD.jar").
        if (GAME_CODE.matcher(line.strip()).find()) return null;
        Matcher j = FRAME_JAR.matcher(line);
        if (!j.find() || GAME_JAR.matcher(j.group(1)).matches()) return null;
        String jar = j.group(1).toLowerCase(Locale.ROOT);
        String id = jars.get(jar);
        return id != null ? id : jarName(jar);
    }

    /** A frame in the game's, the loader's or Java's own packages. */
    private static final Pattern GAME_CODE = Pattern.compile("^at (?:[\\w.-]+//)?(?:net\\.minecraft\\.|com\\.mojang\\."
            + "|net\\.minecraftforge\\.|net\\.neoforged\\.|cpw\\.mods\\.|java\\.|javax\\.|jdk\\.|sun\\.)");
    /**
     * Where the mod's name ends in a jar file name: a version, "v2.1", "mc1.20.1", or a loader word. A number is a
     * version only when a dot, a separator or the end follows it, so "mod_2d_overlay" keeps its "2d".
     */
    private static final Pattern JAR_NAME_END = Pattern.compile(
            "(?i)[-_+ .]+(?:v?\\d+(?=\\.\\d|[-_+ .]|$)|mc\\d|(?:forge|neoforge|fabric|quilt|universal|all|common)(?=[-_+ .]|$)).*$");

    /**
     * The mod name in a jar file name when the report has no mod list: "CreativeCore_FORGE_v2.12.1_mc1.20.1.jar" ->
     * "creativecore", "from-another-world-1.2A.1-forge.jar" -> "from-another-world". Null when nothing is left.
     */
    static String jarName(String jar) {
        String s = jar.toLowerCase(Locale.ROOT).replaceFirst("\\.jar$", "");
        // "mod1.2.jar" -> "mod" (a version glued to the name), but "ae2.jar" stays ae2.
        s = s.replaceFirst("(?<=[a-z])\\d+(?:\\.\\d+)+$", "");
        String name = JAR_NAME_END.matcher(s).replaceFirst("");
        // Letters outside a-z are a player's own label ("卓越前线-superbwarfare-0.8.9.jar" -> "superbwarfare").
        name = name.replaceAll("[^a-z0-9_.+ -]", "").replaceAll("^[-_+ .]+|[-_+ .]+$", "");
        return name.length() < 2 ? null : name;
    }

    /** The mods in the exception section's stack frames, in order, libraries left out. */
    static List<String> stackMods(String[] lines, int from) {
        return stackMods(lines, from, false);
    }

    /** The same, with shared libraries kept (the game and loaders are always left out). */
    static List<String> stackMods(String[] lines, int from, boolean withLibraries) {
        Map<String, String> jars = jarIds(lines);
        Set<String> out = new LinkedHashSet<>();
        for (int i = Math.max(0, from); i < lines.length && !lines[i].startsWith("A detailed walkthrough"); i++) {
            String id = frameMod(lines[i], jars);
            if (id != null && !isCore(id) && (withLibraries || !isLibrary(id))) out.add(id);
        }
        return new ArrayList<>(out);
    }

    /** A method a mixin added: "handler$zza000$modid$name", "redirect$bcd000$modid$name", "localvar$...", "wrapOperation$...". */
    private static final Pattern MIXIN_METHOD = Pattern.compile(
            "\\.(?:handler|redirect|localvar|modify\\w*|wrapOperation|wrapMethod|wrapWithCondition)\\$\\w+?\\$([a-z][a-z0-9_]{1,63})\\$\\w*\\(");
    /** A method a mod added with its own prefix: "sable$buildProperties", "AEA$capture". */
    private static final Pattern PREFIX_METHOD = Pattern.compile("\\.([A-Za-z][A-Za-z0-9_]{1,63})\\$[A-Za-z]\\w*\\(");
    private static final Set<String> NOT_PREFIX = Set.of("lambda", "access", "mixinextras", "handler", "redirect",
            "localvar", "wrapOperation", "wrapMethod", "wrapWithCondition", "bridge", "md5", "static", "init", "clinit");
    /** "pl:mixin:APP:modb.mixins.json:SomeMixin from mod modb" in a frame's tag. */
    private static final Pattern TAG_MIXIN = Pattern.compile("APP:([\\w.-]+?)\\.mixins?\\.json:[\\w.$]+(?: from mod ([a-z0-9_.-]+))?");

    /**
     * Mixin owner first (2026-11-27): the mod whose mixin (a patch into other code) is where it crashed. Read from
     * the top three frames: a method the mixin added carries the mod ID ("handler$...$modb$..." or "modb$name"), or
     * the top frame's tag says exactly one mod patched that class. Null when no such mod, or it's the game, a loader
     * or a library.
     */
    static String mixinOwner(String[] lines, int from) {
        Map<String, String> jars = jarIds(lines);
        Set<String> known = new LinkedHashSet<>(jars.values());
        known.addAll(stackMods(lines, from, true));
        int seen = 0;
        for (int i = Math.max(0, from); i < lines.length && seen < 3; i++) {
            String l = lines[i].strip();
            if (l.startsWith("A detailed walkthrough")) break;
            if (!l.startsWith("at ")) continue;
            seen++;
            String code = l.contains("(") ? l.substring(0, l.indexOf('(') + 1) : l;
            Matcher m = MIXIN_METHOD.matcher(code);
            if (m.find() && !isLibrary(m.group(1))) return modOf(m.group(1), known, lines);
            Matcher p = PREFIX_METHOD.matcher(code);
            if (p.find() && !NOT_PREFIX.contains(p.group(1))) {
                String id = p.group(1).toLowerCase(Locale.ROOT);
                if (known.contains(id) && !isLibrary(id)) return id;
            }
            String own = frameMod(l, jars);
            if (seen == 1) {
                Set<String> mixers = new LinkedHashSet<>();
                Matcher t = TAG_MIXIN.matcher(l);
                while (t.find()) {
                    String id = t.group(2) != null ? t.group(2) : t.group(1).replaceFirst("[-.](common|forge|neoforge|fabric|client)$", "");
                    if (t.group(2) == null && !known.contains(id)) id = "?" + id; // a patch we can't tie to a mod
                    if (!id.equals(own) && usable(id.replace("?", ""))) mixers.add(id);
                }
                if (mixers.size() == 1 && !mixers.iterator().next().startsWith("?")) return mixers.iterator().next();
            }
            // A mod's own code is on top: the crash is in that mod, not in a patch further down.
            if (own != null && !isCore(own)) return null;
        }
        return null;
    }

    /** A mixin method names its config, which isn't always the mod ID ("batchedentityrendering" is part of Oculus). */
    private static String modOf(String id, Set<String> known, String[] lines) {
        if (known.contains(id)) return id;
        String n = id.replaceAll("[^a-z0-9]", "");
        Pattern cfg = Pattern.compile("APP:([\\w.-]+?)\\.json:[\\w.$]+(?: from mod ([a-z0-9_.-]+))?");
        for (String l : lines) {
            if (!l.contains("APP:")) continue;
            Matcher t = cfg.matcher(l);
            while (t.find()) {
                if (!t.group(1).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "").contains(n)) continue;
                if (t.group(2) != null) return t.group(2);
                for (String part : t.group(1).toLowerCase(Locale.ROOT).split("[-._]")) if (known.contains(part)) return part;
            }
        }
        return id;
    }

    /**
     * Walk past libraries and render mods (2026-11-27): when the mod on top of the stack is a render or performance
     * mod, or the next mod down is its add-on (cnpc-epicfight-addon under epicfight, createcobblestone under create),
     * the mod below is usually the one that handed it bad data. Returns that mod, or null. Shared libraries are
     * already left out of the stack list.
     */
    static String callerPast(List<String> stack) {
        if (stack.size() < 2) return null;
        String top = stack.get(0);
        if (RENDER_PIPE.contains(top)) {
            for (String s : stack) if (!RENDER.contains(s)) return s;
            return null;
        }
        String next = stack.get(1);
        String h = top.replaceAll("[^a-z0-9]", ""), n = next.replaceAll("[^a-z0-9]", "");
        return h.length() >= 3 && !n.equals(h) && n.contains(h) ? next : null;
    }

    /** For a tag only: render and performance mods patch almost every class, so their tag alone says nothing. */
    private static boolean usable(String id) {
        return !isLibrary(id) && !RENDER.contains(id);
    }

    /** Render and performance mods: their mixins patch nearly everything, so a crash in one is rarely theirs alone. */
    static final Set<String> RENDER = Set.of("sodium", "embeddium", "rubidium", "iris", "oculus", "optifine",
            "sodium_extra", "sodiumextra", "embeddium_extra", "immediatelyfast", "modernfix", "ferritecore", "lithium",
            "radium", "canary", "c2me", "c2me_base", "entityculling", "moreculling", "acceleratedrendering",
            "sodiumdynamiclights", "dynamic_fps", "fpsreducer", "starlight", "krypton", "noisium", "scalablelux");

    /** Switches for measuring each 0.25 rule on its own (the study tools set them; the game always has them on). */
    static boolean MIXIN_RULE = !Boolean.getBoolean("checkengine.study.noMixinRule");
    static boolean LIBRARY_RULE = !Boolean.getBoolean("checkengine.study.noLibraryRule");

    /**
     * Mods that only draw what other mods hand them. Performance mods (lithium, radium, modernfix) are left out: when
     * their own code throws, it is usually their bug.
     */
    static final Set<String> RENDER_PIPE = Set.of("sodium", "embeddium", "rubidium", "iris", "oculus", "optifine",
            "sodium_extra", "sodiumextra", "embeddium_extra", "immediatelyfast", "acceleratedrendering");

    /** Lower {@code c} to Likely when it was Sure: the new rules never give a sure answer. */
    private static Confidence atMostLikely(Confidence c) {
        return c == Confidence.SURE ? Confidence.LIKELY : c;
    }

    /** Applies the rules to a translated crash report. */
    static Explanation rate(Explanation e, String[] lines, int exceptionLine) {
        if (e.findings().isEmpty()) return e;
        Finding main = e.findings().get(0);
        if (GENERIC.contains(main.code())) {
            Explanation exact = exact(e, lines, exceptionLine);
            if (exact != null) return exact;
        }
        Explanation server = clientOnly(e, lines, exceptionLine);
        if (server != null) return server;
        if (main.code().equals("ticking-crash")) return ticking(e, lines, exceptionLine);
        if (main.code().equals("crash")) {
            return special(linkage(plainCrash(e, lines, exceptionLine), lines, exceptionLine), lines, exceptionLine);
        }
        if (main.title().startsWith("Mod didn't load: ")) return installedTooEarly(e, lines, exceptionLine);
        return e;
    }

    /** Answers the exact rules may replace: the general ones. */
    private static final Set<String> GENERIC = Set.of("crash", "unknown-crash", "loading-issue", "ticking-crash",
            "mod-crashed", "knock-on-crash");

    private static final Pattern CONFIG_FAILED = Pattern.compile(
            "Failed (?:loading|to load) config file (\\S+?\\.toml) of type (\\w+) for modid ([a-z0-9_.-]+)");
    private static final Pattern INCOMPATIBLE = Pattern.compile("Mod ([a-z0-9_.-]+) is incompatible with ([a-z0-9_.-]+)");
    private static final Pattern LAYER = Pattern.compile(
            "(?:Missing layer definitions: \\[|No model for layer )([a-z0-9_.-]+):([a-z0-9_/.-]+)");
    private static final Pattern PARTICLE = Pattern.compile("Unknown particle(?: type)?: ([a-z0-9_.-]+):([a-z0-9_/.-]+)");
    private static final Pattern GLIBC = Pattern.compile("GLIBC_([0-9.]+)' not found");

    /** Small rules for one exact message each. Null when none fits. */
    static Explanation exact(Explanation e, String[] lines, int exceptionLine) {
        Finding f = e.findings().get(0);
        String all = String.join("\n", lines);
        Matcher m;
        if ("Loading screen debug info".equals(e.description())) {
            return replace(e, finding("debug-crash", "Not a crash: the loading screen's computer info",
                    "This file is the computer and mod info the loading screen writes on purpose. Its own text says it "
                            + "is not an error.", "Nothing to fix here. If the game did crash, look for a newer file in "
                            + "the crash-reports folder."), Confidence.SURE, List.of());
        }
        if ((m = CONFIG_FAILED.matcher(all)).find()) {
            String file = m.group(1), type = m.group(2), mod = m.group(3);
            boolean server = type.equalsIgnoreCase("SERVER");
            String where = server ? "saves/<your world>/serverconfig/" + file + " (each world has its own copy; on a "
                    + "server: world/serverconfig/" + file + ")" : "config/" + file;
            return replace(e, finding("config-error", "Broken settings file: " + file, mod + "'s settings file "
                    + file + " couldn't be read, usually because it was cut off or edited by hand. The game stops "
                    + "instead of guessing the settings.", "Rename " + where + " (for example add .broken to the "
                    + "end). " + mod + " makes a fresh one with default settings on the next start. Copy over any "
                    + "setting you changed by hand."), Confidence.SURE, List.of(new Suspect(mod, null,
                    "its settings file is the broken one")));
        }
        if ((m = INCOMPATIBLE.matcher(all)).find() && !f.code().equals(Finding.INCOMPATIBLE_MOD)) {
            String a = m.group(1), b = m.group(2);
            return replace(e, finding(Finding.INCOMPATIBLE_MOD, a + " doesn't work with " + b, a + " says it can't "
                    + "run together with " + b + ".", "Remove " + a + " or " + b + ". If you need both, check "
                    + a + "'s page for a version that works with " + b + "."), Confidence.SURE,
                    List.of(new Suspect(a, null, "it says it doesn't work with " + b),
                            new Suspect(b, null, a + " says it doesn't work with it")));
        }
        if ((m = PARTICLE.matcher(all)).find() && CrashTranslator.modListCount(all, m.group(1)) == 0
                && !stackMods(lines, exceptionLine, true).contains(m.group(1)) && !m.group(1).equals("minecraft")) {
            String mod = m.group(1);
            return replace(e, finding(Finding.MISSING_DEPENDENCY, "Missing mod: " + mod, "Something in the world "
                    + "asked for the particle " + mod + ":" + m.group(2) + ", but no mod called " + mod + " is "
                    + "installed. Usually an addon or content pack is missing, or was removed.", "Install " + mod
                    + " (the addon or pack that adds it), or remove what uses it."), Confidence.SURE,
                    List.of(new Suspect(mod, null, "its particle was asked for, and it isn't installed")));
        }
        if ((m = LAYER.matcher(all)).find() && !m.group(1).equals("minecraft")) {
            String mod = m.group(1);
            return replace(e, finding("crash", "Crash, most likely in " + mod + " (its 3D models didn't load)",
                    "The game couldn't find " + mod + "'s 3D model parts (" + mod + ":" + m.group(2) + "). The mod's "
                            + "client parts didn't load: often a Fabric mod running through Connector, or a mod made for "
                            + "another version.", fixFor(mod, Confidence.LIKELY)), Confidence.LIKELY,
                    List.of(new Suspect(mod, null, "its 3D model parts are the missing ones")));
        }
        if (all.contains("GLFW error") && all.contains("Wayland")) {
            return replace(e, finding("window-system", "The game's window can't open under Wayland",
                    "On Linux the game runs under Wayland here, and the window library (GLFW) can't do what Minecraft "
                            + "asks of it there.", "Start the game in an X11 session, or switch on your launcher's "
                            + "option to use the system's GLFW or XWayland."), Confidence.SURE, List.of());
        }
        if ((m = GLIBC.matcher(all)).find()) {
            return replace(e, finding("os-library", "This Linux is too old for a mod's native library",
                    "A mod's native library needs GLIBC " + m.group(1) + " or newer, and this system has an older one.",
                    "Update the Linux system (or run the server on a newer one). Or remove the mod that brings the "
                            + "native library."), Confidence.SURE, List.of());
        }
        return null;
    }

    /** "NoSuchFileException: C:\saves\New World\x.nbt" / "FileNotFoundException: x.json (The system cannot find...)" */
    private static final Pattern MISSING_FILE = Pattern.compile(
            "(?m)(?:FileNotFoundException|NoSuchFileException): (.+?)(?: \\([^)]*\\))?\\s*$");

    /** Watchdog, stack overflow and missing file: the same suspects, words that say what happened. */
    static Explanation special(Explanation e, String[] lines, int exceptionLine) {
        Finding f = e.findings().get(0);
        String all = String.join("\n", lines);
        Confidence c = e.confidence();
        String who = e.suspects().isEmpty() ? null : e.suspects().get(0).label();
        if (all.contains("A single server tick took") || "Watching Server".equals(e.description())) {
            return replace(e, finding("watchdog", who == null ? "The server froze" : "The server froze, " + (c ==
                    Confidence.SURE ? "in " : c == Confidence.LIKELY ? "most likely in " : "maybe in ") + who,
                    "One server tick took far too long, so the server's watchdog stopped it on purpose. Something got "
                            + "stuck: a mod in a loop, or a huge job (a big machine, explosion or chunk generation)."
                            + (who == null ? "" : " " + who + "'s code was running when it was stopped."),
                    (who == null ? "Remove the mods added or updated since it last worked, one at a time. "
                            : lead(who, c) + ": update it, or remove it if it keeps happening. ")
                            + "While you look, max-tick-time=-1 in server.properties stops the watchdog from closing "
                            + "the server (set it back afterwards)."), c, e.suspects());
        }
        if (all.contains("StackOverflowError")) {
            String loop = e.suspects().isEmpty() ? loopMod(lines, exceptionLine) : e.suspects().get(0).id();
            if (loop == null) return e;
            Confidence lc = c == Confidence.SURE && e.suspects().get(0).id().equals(loop) ? c : Confidence.LIKELY;
            List<Suspect> s = new ArrayList<>(List.of(new Suspect(loop, null, "its code repeats over and over in the "
                    + "crash")));
            for (Suspect x : e.suspects()) if (!x.id().equals(loop) && s.size() < 3) s.add(x);
            return replace(e, finding("crash", "Endless loop in " + loop + " (stack overflow)", "The game ran out of "
                    + "room for nested calls: code kept calling itself. " + loop + "'s code is the part that repeats, "
                    + "often because of another mod it hooks into.", fixFor(loop, lc)), lc, s);
        }
        Matcher m = MISSING_FILE.matcher(all);
        if (m.find() && who != null) {
            String path = m.group(1);
            String name = path.substring(Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1);
            return replace(e, new Finding(f.severity(), "missing-file", who + " couldn't find a file: " + name,
                    f.detail() + "\nThe missing file: " + path, (c == Confidence.SURE ? "" : lead(who, c)
                    + ". ") + "If the file belongs to " + who + ", reinstall or update it. If it's a file you removed or "
                    + "renamed (a config, a resource pack), put it back.", f.files()), c, e.suspects());
        }
        return e;
    }

    /** The non-library mod with the most frames in a stack overflow: the code that repeats. */
    static String loopMod(String[] lines, int from) {
        Map<String, String> jars = jarIds(lines);
        Map<String, Integer> count = new HashMap<>();
        for (int i = Math.max(0, from); i < lines.length && !lines[i].startsWith("A detailed walkthrough"); i++) {
            String id = frameMod(lines[i], jars);
            if (id != null && !isLibrary(id)) count.merge(id, 1, Integer::sum);
        }
        return count.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
    }

    /** "Attempted to load class net/minecraft/client/... for invalid dist DEDICATED_SERVER" */
    private static final Pattern INVALID_DIST = Pattern.compile("for invalid dist DEDICATED_SERVER");
    private static final Pattern CLIENT_CLASS = Pattern.compile(
            "(?:NoClassDefFoundError|ClassNotFoundException|Attempted to load class):? net[/.]minecraft[/.]client[/.]");
    /** "Steve's Gadgets (stevesgadgets) has failed to load correctly" / "...encountered an error..." */
    private static final Pattern REPORTER = Pattern.compile(
            "^(?:Failure message: )?(.+?) \\(([a-z0-9_.-]+)\\) (?:has failed to load correctly|encountered an error|has class loading errors)");

    /**
     * A dedicated server tried to load the game's screen or rendering code: a client-only mod on the server (or a
     * server mod with a client-code bug, which is why it is Likely, not Sure). Only when a mod can be named.
     */
    static Explanation clientOnly(Explanation e, String[] lines, int exceptionLine) {
        Finding f = e.findings().get(0);
        String all = String.join("\n", lines);
        boolean invalidDist = INVALID_DIST.matcher(all).find();
        // The loader's own "invalid dist" message names the mod outright: the older rule's sure answer stays.
        if (f.code().equals(Finding.CLIENT_ONLY_ON_SERVER) && invalidDist) return null;
        if (!invalidDist && !CLIENT_CLASS.matcher(all).find()) return null;
        String id = null, name = null;
        for (String l : lines) {
            Matcher r = REPORTER.matcher(l.strip());
            if (r.find() && !isLibrary(r.group(2))) {
                name = r.group(1).strip();
                id = r.group(2);
                break;
            }
        }
        if (id == null) {
            List<String> stack = stackMods(lines, exceptionLine);
            if (stack.isEmpty()) return null;
            id = stack.get(0);
        }
        Suspect s = new Suspect(id, name, "it tried to load the game's screen or rendering code, which a server "
                + "doesn't have");
        String who = s.label();
        Finding out = new Finding(f.severity(), Finding.CLIENT_ONLY_ON_SERVER, "Client-only mod on the server, likely: "
                + who, who + " tried to use screen or rendering code while running on a dedicated server, which "
                + "doesn't have it. Usually that means " + who + " is only meant for players' games; sometimes it's a "
                + "bug in a mod that should work on servers.", "Probably " + who + " belongs on players' games only: "
                + "remove it from the server (players keep it). If its page says it's needed on servers, update it.",
                f.files());
        return replace(e, out, Confidence.LIKELY, List.of(s));
    }

    /**
     * "Mod 'architectury' is not available" while architectury IS in the report's mod list or stack: it isn't
     * missing, another mod asked for it before it was ready. Install advice would be wrong here.
     */
    static Explanation installedTooEarly(Explanation e, String[] lines, int exceptionLine) {
        Finding f = e.findings().get(0);
        String mod = f.title().substring("Mod didn't load: ".length()).strip();
        boolean installed = CrashTranslator.modListCount(String.join("\n", lines), mod) > 0
                || stackMods(lines, exceptionLine, true).contains(mod);
        if (!installed) return e;
        Set<String> found = new LinkedHashSet<>(stackMods(lines, exceptionLine));
        found.addAll(suspectedMods(lines).keySet());
        found.remove(mod);
        List<String> callers = new ArrayList<>(found);
        String title = mod + INSTALLED_NOT_READY;
        String detail = mod + " is in the modpack, but another mod used it while the game was starting, before "
                + mod + " was ready (or loading had already gone wrong earlier). Installing it again won't help."
                + f.detail().replaceFirst("^[^\n]*", "");
        if (callers.isEmpty()) {
            return replace(e, new Finding(f.severity(), f.code(), title, detail + " The crash doesn't show which mod "
                    + "asked.", "Remove the mods added or updated since the modpack last worked, one at a time. "
                    + CrashTranslator.STRIP_MINE_HINT, f.files()), Confidence.NOT_SURE, List.of());
        }
        String caller = callers.get(0);
        return replace(e, new Finding(f.severity(), f.code(), title + " (likely " + caller + ")", detail,
                "Probably " + caller + ": update it, or remove it if that doesn't help.", f.files()),
                Confidence.LIKELY, List.of(new Suspect(caller, null, "it shows up in the crash next to " + mod)));
    }

    /** The title ending of {@link #installedTooEarly}: the game log may still know which mod failed first. */
    static final String INSTALLED_NOT_READY = " is installed, but wasn't ready when a mod asked for it";

    /** NoSuchMethodError, NoSuchFieldError, NoClassDefFoundError...: code built against another version. */
    private static final Pattern LINKAGE = Pattern.compile("(NoSuchMethodError|NoSuchFieldError|NoClassDefFoundError"
            + "|AbstractMethodError|IncompatibleClassChangeError|ClassNotFoundException)(?::\\s*'?(?:[\\w$.\\[\\]<>]+ )?"
            + "([\\w$/.]+))?");

    /**
     * A crash from code built for another version: mod A calls something in mod B (or the game, or the loader) that
     * isn't there. The answer becomes "versions don't match", naming the caller first and the other mod second.
     */
    static Explanation linkage(Explanation e, String[] lines, int exceptionLine) {
        if (e.suspects().isEmpty() || exceptionLine < 0) return e;
        StringBuilder head = new StringBuilder();
        for (int i = exceptionLine; i < lines.length && !lines[i].startsWith("A detailed walkthrough"); i++) {
            String l = lines[i].strip();
            if (i == exceptionLine || l.startsWith("Caused by:")) head.append(l).append('\n');
        }
        Matcher m = LINKAGE.matcher(head);
        if (!m.find()) return e;
        String cls = m.group(2) == null ? "" : m.group(2).replace('.', '/');
        Finding f = e.findings().get(0);
        Suspect caller = e.suspects().get(0);
        String who = caller.label();
        if (cls.startsWith("net/minecraft/") || cls.startsWith("net/neoforged/") || cls.startsWith("net/minecraftforge/")
                || cls.startsWith("com/mojang/")) {
            return replace(e, new Finding(f.severity(), Finding.WRONG_VERSION, who + " is made for a different "
                    + "Minecraft or loader version", f.detail() + "\n" + who + " uses game code that isn't there in "
                    + "this version (" + m.group(1) + ").", "Get the version of " + who + " made for your exact "
                    + "Minecraft and loader version, or remove it.", f.files()), e.confidence(), e.suspects());
        }
        List<Suspect> suspects = new ArrayList<>(e.suspects());
        String other = cls.isEmpty() ? null : checkengine.core.scan.HiddenDependencies.modForClass(cls);
        if (other != null && suspects.stream().noneMatch(s -> s.id().equals(other))) {
            suspects.add(Math.min(1, suspects.size()), new Suspect(other, null, "the code " + caller.id()
                    + " looked for belongs to it"));
        }
        String with = other != null ? other : "another mod";
        return replace(e, new Finding(f.severity(), Finding.WRONG_VERSION, who + " doesn't match the version of "
                + with, f.detail() + "\n" + who + " looked for code that isn't there (" + m.group(1) + "): it was "
                + "built for a different version of " + with + ".", "Update " + who + (other != null ? " and " + other
                : "") + " so they match (check " + caller.id() + "'s page for the version it needs), or remove " + who
                + ".", f.files()), e.confidence(), suspects.subList(0, Math.min(3, suspects.size())));
    }

    /** "Suspected Mod(s):" names on its own line or on the lines under it ("\tName (id), Version: 1.0"). */
    private static final Pattern SUSPECT = Pattern.compile("([^,]+?) \\(([a-z0-9_.-]+)\\)");
    private static final Pattern SUSPECT_ROW = Pattern.compile("^\\s*(.+?) \\(([a-z0-9_.-]+)\\), Version:");

    /** id -> name from the report's "Suspected Mod(s):" block, libraries left out, in the report's order. */
    static Map<String, String> suspectedMods(String[] lines) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < lines.length; i++) {
            String s = lines[i].strip();
            if (!s.startsWith("Suspected Mods:") && !s.startsWith("Suspected Mod:")) continue;
            Matcher m = SUSPECT.matcher(s.substring(s.indexOf(':') + 1));
            while (m.find()) if (!isLibrary(m.group(2))) out.putIfAbsent(m.group(2), m.group(1).strip());
            for (int j = i + 1; j < lines.length && j < i + 40; j++) {
                String l = lines[j].strip();
                if (l.isEmpty() || l.startsWith("Stacktrace:") || l.startsWith("-- ") || l.startsWith("Details:")) break;
                Matcher r = SUSPECT_ROW.matcher(l);
                if (r.find() && !isLibrary(r.group(2))) out.putIfAbsent(r.group(2), r.group(1).strip());
            }
            break;
        }
        return out;
    }

    /**
     * A crash while playing or loading with no special rule: read the stack (every frame style, libraries skipped)
     * and the report's own "Suspected Mods". Both name the same mod: Sure. One of them: Likely. They disagree: Maybe,
     * the stack's mod first. Up to 3 suspects.
     */
    static Explanation plainCrash(Explanation e, String[] lines, int exceptionLine) {
        Finding f = e.findings().get(0);
        List<String> stack = stackMods(lines, exceptionLine);
        Map<String, String> suspected = suspectedMods(lines);
        String first = stack.isEmpty() ? null : stack.get(0);
        String mixer = MIXIN_RULE ? mixinOwner(lines, exceptionLine) : null;
        if (first == null && suspected.isEmpty() && mixer == null) {
            // Only a shared library shows up: name it, but as Maybe, since it's usually where another mod's call ended.
            List<String> libs = stackMods(lines, exceptionLine, true);
            if (libs.isEmpty()) return e.with(Confidence.NOT_SURE, List.of());
            String lib = libs.get(0);
            return replace(e, new Finding(f.severity(), f.code(), "Crash, maybe in " + lib, f.detail()
                    .replaceAll("\nThe crash happened inside [^\n]*", ""), "The crash happened inside " + lib
                    + ", a library other mods build on, so the cause is often another mod that uses it. Update " + lib
                    + " first. " + CrashTranslator.STRIP_MINE_HINT, f.files()), Confidence.MAYBE,
                    List.of(new Suspect(lib, null, "a library other mods use; its code is on top of the crash")));
        }
        Confidence c;
        List<Suspect> out = new ArrayList<>();
        if (first != null && suspected.containsKey(first)) {
            c = Confidence.SURE;
            out.add(new Suspect(first, suspected.get(first), "its code is on top of the crash, and the report "
                    + "suspects it too"));
        } else if (first != null) {
            c = suspected.isEmpty() ? Confidence.LIKELY : Confidence.MAYBE;
            out.add(new Suspect(first, suspected.get(first), "its code is on top of the crash"));
        } else {
            c = Confidence.LIKELY;
        }
        for (Map.Entry<String, String> s : suspected.entrySet()) {
            if (out.size() < 3 && out.stream().noneMatch(x -> x.id().equals(s.getKey()))) {
                out.add(new Suspect(s.getKey(), s.getValue(), "the crash report lists it as a suspect"));
            }
        }
        for (String s : stack) {
            if (out.size() < 3 && out.stream().noneMatch(x -> x.id().equals(s))) {
                out.add(new Suspect(s, null, "its code is further down the crash"));
            }
        }
        if (mixer != null && (out.isEmpty() || !out.get(0).id().equals(mixer))) {
            out.removeIf(x -> x.id().equals(mixer));
            out.add(0, new Suspect(mixer, suspected.get(mixer), "its mixin (a patch into other code) is where it crashed"));
            while (out.size() > 3) out.remove(3);
            c = atMostLikely(c);
        }
        String caller = mixer == null && LIBRARY_RULE ? callerPast(stack) : null;
        if (caller != null && !out.get(0).id().equals(caller)) {
            String top = stack.get(0);
            out.removeIf(x -> x.id().equals(caller));
            out.add(0, new Suspect(caller, suspected.get(caller), "it called into " + top + ", where it crashed"));
            while (out.size() > 3) out.remove(3);
            c = atMostLikely(c);
        }
        String who = out.get(0).label();
        String title = c == Confidence.SURE ? "Crash in " + who
                : "Crash, " + (c == Confidence.LIKELY ? "most likely" : "maybe") + " in " + who;
        String detail = f.detail().replaceAll("\nThe crash happened inside [^\n]*", "")
                .replaceAll("\nMods involved: [^\n]*", "");
        return replace(e, new Finding(f.severity(), f.code(), title, detail, fixFor(out.get(0).id(), c), f.files()), c,
                out);
    }

    /**
     * A ticking crash names the thing that was updating (a mob, a machine) and Minecraft calls it "Ticking entity".
     * The thing's owner is often only the victim: the mod whose code threw is the first mod in the stack. Same mod:
     * Sure. Only the stack's mod: Likely. Two different mods: Maybe, the stack's mod first. No mod at all and the thing
     * is the game's own (an armor stand): Not sure, and never "update minecraft".
     */
    static Explanation ticking(Explanation e, String[] lines, int exceptionLine) {
        Finding f = e.findings().get(0);
        String what = detail(lines, "Entity Type:", "Name:");
        if (what != null) what = what.replaceAll("\\s*\\([\\w.$]+\\)$", "").replaceAll("\\s*//.*$", "").strip();
        String owner = what != null && what.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") ? what.substring(0, what.indexOf(':'))
                : null;
        if ("minecraft".equals(owner)) owner = null;
        List<String> stack = stackMods(lines, exceptionLine);
        String coder = stack.isEmpty() ? null : stack.get(0);
        String mixer = MIXIN_RULE ? mixinOwner(lines, exceptionLine) : null;
        boolean mixed = mixer != null && !mixer.equals(coder);
        if (mixed) coder = mixer;
        String caller = !mixed && LIBRARY_RULE ? callerPast(stack) : null;
        boolean walked = caller != null;
        if (walked) coder = caller;
        String thing = what != null && what.contains(":") ? nice(what) : null;
        String rescue = " To get the world back: Rescue world in Mechanic backs it up and removes just that one, or "
                + "remove it yourself with a world editor (MCA Selector, NBTExplorer).";

        if (coder == null && owner == null) {
            String title = thing != null ? thing + " keeps crashing the world" : f.title();
            return replace(e, new Finding(f.severity(), f.code(), title, f.detail().replaceAll("\nThe crash happened "
                    + "inside [^\n]*", "") + "\nNo mod shows up in the crash, so Check Engine can't tell which one "
                    + "broke it.", "Remove the mods added or updated since the world last loaded, one at a time." + rescue,
                    f.files()), Confidence.NOT_SURE, List.of());
        }
        if (coder == null || coder.equals(owner)) {
            String mod = coder != null ? coder : owner;
            Confidence c = coder != null && !mixed && !walked ? Confidence.SURE : Confidence.LIKELY;
            return replace(e, new Finding(f.severity(), f.code(), f.title(), f.detail(), fixFor(mod, c) + rescue,
                    f.files()), c, List.of(new Suspect(mod, null, coder != null
                    ? "it adds the thing that crashed, and its code is where it crashed"
                    : "it adds the thing that crashed; no mod's code shows up in the crash")));
        }
        List<Suspect> suspects = new ArrayList<>();
        suspects.add(new Suspect(coder, null, mixed ? "its mixin (a patch into other code) is where it crashed"
                : walked ? "it called into " + stack.get(0) + ", where it crashed" : "its code is where it crashed"));
        if (owner != null) suspects.add(new Suspect(owner, null, "it adds the thing that crashed"));
        Confidence c = owner == null ? Confidence.LIKELY : Confidence.MAYBE;
        // The headline names the mods in the suspect list's order. "Swivel bearing (simulated) keeps crashing the world
        // (maybe sable_rapier)" read as simulated first while the list put sable_rapier first (G303, 24 crashes).
        String plain = what != null && what.contains(":") ? nice(what).replaceAll(" \\([^)]*\\)$", "") : null;
        String title = (plain != null ? plain : "A " + kind(e)) + " keeps crashing the world ("
                + c.word().toLowerCase(Locale.ROOT) + " " + coder + (owner != null ? ", or " + owner + " that adds it" : "")
                + ")";
        String detail = f.detail().replaceAll("\nThe crash happened inside [^\n]*", "") + "\n" + coder
                + "'s code was running when it crashed" + (owner != null ? ", while " + owner + " adds the thing"
                : "") + ".";
        return replace(e, new Finding(f.severity(), f.code(), title, detail, fixFor(coder, c) + rescue, f.files()), c,
                suspects);
    }

    /** "Update X." for a sure answer; a lead for the others, so no screen shows a guess as the fix. */
    static String fixFor(String mod, Confidence c) {
        if (c == Confidence.SURE) {
            return "If there's a newer version of " + mod + ", update it. If you already have the newest one, remove "
                    + mod + " or send this crash report to its author.";
        }
        // "... Misfire can find it: press Misfire ..." -> "if it still crashes, Misfire can find the real one: press ..."
        String hint = CrashTranslator.STRIP_MINE_HINT;
        int at = hint.indexOf("can find it: ");
        String how = at < 0 ? hint : "Misfire can find the real one: " + hint.substring(at + "can find it: ".length());
        return c == Confidence.MAYBE
                ? "Might be " + mod + ", or another mod: this is a guess. Try updating " + mod + " first. If it still "
                        + "crashes, " + how
                : "Probably " + mod + ", but not certain. Try updating " + mod + " first. If it still crashes, " + how;
    }

    /** How a fix names the mod it suspects: "Probably X" for Likely, the softer "Might be X" for Maybe. */
    static String lead(String mod, Confidence c) {
        return (c == Confidence.MAYBE ? "Might be " : "Probably ") + mod;
    }

    private static String kind(Explanation e) {
        return e.description() != null && e.description().toLowerCase(Locale.ROOT).contains("block") ? "block or machine"
                : "mob or entity";
    }

    /** "iceandfire:dragon_egg" -> "Dragon egg (iceandfire)" */
    private static String nice(String what) {
        int c = what.indexOf(':');
        return Character.toUpperCase(what.charAt(c + 1)) + what.substring(c + 2).replace('_', ' ') + " ("
                + what.substring(0, c) + ")";
    }

    static Explanation replace(Explanation e, Finding main, Confidence c, List<Suspect> suspects) {
        List<Finding> findings = new ArrayList<>(e.findings());
        findings.set(0, main);
        return new Explanation(e.description(), e.exception(), findings, c, suspects);
    }

    static String detail(String[] lines, String... keys) {
        for (String l : lines) {
            String s = l.strip();
            for (String k : keys) if (s.startsWith(k)) return s.substring(k.length()).strip();
        }
        return null;
    }

    static Finding finding(String code, String title, String detail, String fix) {
        return new Finding(Severity.ERROR, code, title, detail, fix, List.of());
    }
}
