package com.guiltypotato.checkengine.core.scan;

import com.guiltypotato.checkengine.core.model.Dependency;
import com.guiltypotato.checkengine.core.model.ModInfo;
import com.guiltypotato.checkengine.core.model.ModJar;
import com.guiltypotato.checkengine.core.version.VersionRange;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The root-cause engine. Takes the dependency rules the mod loader refuses to start over and works out what
 * actually needs fixing: one problem per cause ("Create is missing"), everything it stops from loading
 * (including mods that need those mods), and the likely reason behind it ("it's the Fabric version").
 * The loader adapter (or the checker) decides which rules fail; this class explains them.
 */
public final class LoadBlockers {
    public enum Kind { MISSING, WRONG_VERSION, INCOMPATIBLE }

    /**
     * One broken rule.
     *
     * @param kind      what's wrong
     * @param modId     the mod that has the rule
     * @param modName   its display name, e.g. "Create: Steam 'n' Rails"
     * @param depId     the mod the rule is about, e.g. "create"
     * @param depName   its display name if it's installed, else null
     * @param range     the version range the rule asks for, e.g. "[6.0,)"
     * @param installed the installed version of depId, or null if it isn't installed
     * @param reason    the mod author's own explanation, or null
     */
    public record Blocker(Kind kind, String modId, String modName, String depId, String depName, String range,
                          String installed, String reason) {}

    /** How many affected mods to name before saying "and N more". */
    private static final int LIST_LIMIT = 8;
    private static final Set<String> PLATFORM = Set.of("minecraft", "neoforge", "forge");

    private LoadBlockers() {}

    /**
     * @param blockers   the rules that fail
     * @param loadedMods every mod the loader sees (top-level and jar-in-jar), for "which mods need this mod"
     * @param jars       every jar in the mods folder, for spotting why a mod looks missing (may be empty)
     * @param modsFolder the mods folder, for files that aren't jars (may be null)
     */
    public static List<RootProblem> analyze(List<Blocker> blockers, List<ModInfo> loadedMods, List<ModJar> jars,
                                            Path modsFolder) {
        Pack pack = new Pack(loadedMods, jars, otherFiles(modsFolder));
        Map<String, List<Blocker>> groups = new LinkedHashMap<>();
        for (Blocker b : blockers) {
            String key = b.kind() == Kind.INCOMPATIBLE ? b.kind() + "|" + b.modId() + "|" + b.depId()
                    : b.kind() + "|" + b.depId();
            List<Blocker> group = groups.computeIfAbsent(key, k -> new ArrayList<>());
            if (group.stream().noneMatch(o -> o.modId().equals(b.modId()))) group.add(b);
        }
        List<RootProblem> out = new ArrayList<>();
        for (List<Blocker> group : groups.values()) {
            out.add(switch (group.get(0).kind()) {
                case MISSING -> missing(group, pack);
                case WRONG_VERSION -> wrongVersion(group, pack);
                case INCOMPATIBLE -> incompatible(group.get(0), pack);
            });
        }
        // The cause that stops the most mods goes first.
        out.sort(Comparator.comparingInt((RootProblem p) -> p.affected().size()).reversed());
        return out;
    }

    /** Shortcut when there's no pack information: grouping and plain explanations only. */
    public static List<Finding> explain(List<Blocker> blockers) {
        return analyze(blockers, List.of(), List.of(), null).stream().map(RootProblem::toFinding).toList();
    }

    // ---- missing mods

    private static RootProblem missing(List<Blocker> group, Pack pack) {
        String dep = group.get(0).depId();
        StringBuilder detail = new StringBuilder(needs(group, dep));
        reasons(detail, group);
        Affected affected = pack.affected(group);
        String want = describe(group.get(0).range());

        // Is the mod really missing, or is it there in a form the loader can't use?
        ModJar wrongBuild = pack.jarDeclaring(dep);
        ModJar broken = pack.brokenJarNamed(dep);
        String switchedOff = pack.otherFileNamed(dep);
        String title;
        String fix;
        String certainty = null;
        List<String> files = new ArrayList<>(affected.files());
        if (wrongBuild != null && wrongBuild.kind() == ModJar.Kind.FABRIC) {
            title = name(wrongBuild, dep) + " is the Fabric version";
            detail.insert(0, wrongBuild.fileName() + " is the Fabric build of " + name(wrongBuild, dep)
                    + ". This pack's loader can't use it, so as far as it knows " + dep + " isn't installed.\n");
            fix = "Swap " + wrongBuild.fileName() + " for the NeoForge or Forge version of " + name(wrongBuild, dep)
                    + " (" + want + ").";
            certainty = "very likely";
            files.add(wrongBuild.fileName());
        } else if (wrongBuild != null) {
            title = name(wrongBuild, dep) + " is the wrong build";
            detail.insert(0, wrongBuild.fileName() + " has " + name(wrongBuild, dep) + ", but it's a build for "
                    + "another loader or Minecraft version, so it doesn't load.\n");
            fix = "Download the version of " + name(wrongBuild, dep) + " made for this pack (" + want
                    + ") and remove " + wrongBuild.fileName() + ".";
            certainty = "very likely";
            files.add(wrongBuild.fileName());
        } else if (broken != null) {
            title = dep + "'s file is broken";
            detail.insert(0, broken.fileName() + " looks like " + dep + ", but it can't be opened (probably a "
                    + "half-finished download).\n");
            fix = "Delete " + broken.fileName() + " and download " + dep + " again (" + want + ").";
            certainty = "likely";
            files.add(broken.fileName());
        } else if (switchedOff != null) {
            title = dep + " is switched off";
            detail.insert(0, switchedOff + " looks like " + dep + ", but it isn't a .jar, so it doesn't load "
                    + "(a launcher or someone turned it off).\n");
            fix = "Rename " + switchedOff + " back to a .jar (or turn it back on in your launcher).";
            certainty = "likely";
            files.add(switchedOff);
        } else {
            title = dep + " is missing";
            fix = group.size() == 1
                    ? "Install " + dep + " (" + want + "), or remove " + group.get(0).modName() + "."
                    : "Install " + dep + ", or remove the " + group.size() + " mods that need it.";
        }
        affected.appendTo(detail, group.size());
        return new RootProblem(Finding.MISSING_DEPENDENCY, title, detail.toString(), fix, certainty,
                affected.names(), distinct(files), ids(group));
    }

    private static String needs(List<Blocker> group, String dep) {
        if (group.size() == 1) {
            Blocker b = group.get(0);
            return b.modName() + " needs " + dep + " (" + describe(b.range()) + "), but it isn't installed.";
        }
        StringBuilder sb = new StringBuilder(group.size() + " mods need " + dep + ", but it isn't installed:");
        for (Blocker b : group) sb.append("\n- ").append(b.modName()).append(" (wants ").append(describe(b.range()))
                .append(')');
        return sb.toString();
    }

    // ---- wrong versions

    private static RootProblem wrongVersion(List<Blocker> group, Pack pack) {
        Blocker first = group.get(0);
        String name = first.depName() != null ? first.depName() : first.depId();
        String have = first.installed();
        Affected affected = pack.affected(group);
        StringBuilder detail = new StringBuilder();
        String title;
        String fix;
        if (PLATFORM.contains(first.depId())) {
            title = group.size() == 1 ? first.modName() + " is made for a different " + name + " version"
                    : group.size() + " mods are made for a different " + name + " version";
            detail.append("This pack has ").append(name).append(' ').append(have).append(", but ");
            appendWants(detail, group);
            fix = group.size() == 1
                    ? "Get the version of " + first.modName() + " made for " + name + " " + have + ", or remove it."
                    : "Get the versions of these mods made for " + name + " " + have + ", or remove them.";
        } else {
            boolean tooOld = group.stream().allMatch(b -> VersionRange.parseLenient(b.range()).wantsNewerThan(have));
            boolean tooNew = group.stream().allMatch(b -> VersionRange.parseLenient(b.range()).wantsOlderThan(have));
            detail.append("This pack has ").append(name).append(' ').append(have).append(", but ");
            appendWants(detail, group);
            if (tooOld) {
                title = name + " is too old";
                fix = group.size() == 1 ? "Update " + name + " to " + describe(first.range()) + "."
                        : "Update " + name + " to a version all of them accept (see above).";
            } else if (tooNew) {
                title = name + " is too new" + (group.size() == 1 ? " for " + first.modName() : "");
                fix = "Use an older " + name + " (see above), or update " + (group.size() == 1 ? first.modName()
                        : "the mods that want an older one") + ".";
            } else {
                title = "No single version of " + name + " works for these mods";
                fix = "Update the mods that want an older " + name + ", or remove them.";
            }
        }
        reasons(detail, group);
        affected.appendTo(detail, group.size());
        return new RootProblem(Finding.WRONG_VERSION, title, detail.toString(), fix, null, affected.names(),
                affected.files(), ids(group));
    }

    private static void appendWants(StringBuilder detail, List<Blocker> group) {
        if (group.size() == 1) {
            Blocker b = group.get(0);
            detail.append(b.modName()).append(" wants ").append(describe(b.range())).append('.');
            return;
        }
        detail.append("these mods want something else:");
        for (Blocker b : group) detail.append("\n- ").append(b.modName()).append(" wants ").append(describe(b.range()));
    }

    // ---- incompatible mods

    private static RootProblem incompatible(Blocker b, Pack pack) {
        String name = b.depName() != null ? b.depName() : b.depId();
        StringBuilder detail = new StringBuilder(b.modName() + " doesn't work with " + name
                + (VersionRange.parseLenient(b.range()).matchesAnything() ? "" : " " + describe(b.range()))
                + " (this pack has " + b.installed() + ").");
        reasons(detail, List.of(b));
        Affected affected = pack.affected(List.of(b));
        affected.appendTo(detail, 1);
        return new RootProblem(Finding.INCOMPATIBLE_MOD, b.modName() + " doesn't work with " + name,
                detail.toString(), "Remove " + b.modName() + " or " + name + ".", null, affected.names(),
                affected.files(), ids(List.of(b)));
    }

    // ---- shared

    /** The mods with the broken rule, then the mod the rule is about. */
    private static List<String> ids(List<Blocker> group) {
        List<String> ids = new ArrayList<>();
        group.forEach(b -> ids.add(b.modId()));
        ids.add(group.get(0).depId());
        return distinct(ids);
    }

    private static String describe(String range) {
        return VersionRange.parseLenient(range).describe();
    }

    private static void reasons(StringBuilder detail, List<Blocker> group) {
        group.stream().map(Blocker::reason).filter(r -> r != null && !r.isBlank()).distinct()
                .forEach(r -> detail.append("\nThe mod says: \"").append(r.strip()).append('"'));
    }

    private static String name(ModJar jar, String id) {
        return Stream.concat(jar.mods().stream(), jar.nestedMods().stream()).filter(m -> m.modId().equals(id))
                .findFirst().map(ModInfo::name).orElse(id);
    }

    private static List<String> distinct(List<String> list) {
        return List.copyOf(new LinkedHashSet<>(list));
    }

    /** Files in the mods folder that aren't jars, e.g. "create-6.0.4.jar.disabled" or a .zip. */
    private static List<String> otherFiles(Path modsFolder) {
        if (modsFolder == null || !Files.isDirectory(modsFolder)) return List.of();
        try (Stream<Path> files = Files.list(modsFolder)) {
            return files.filter(Files::isRegularFile).map(p -> p.getFileName().toString())
                    .filter(n -> !n.toLowerCase(Locale.ROOT).endsWith(".jar")).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** Mods this problem stops: the ones with the broken rule, then everything that needs them, and so on. */
    private record Affected(Map<String, String> via, Pack pack) {
        List<String> names() {
            return via.keySet().stream().map(pack::nameOf).toList();
        }

        List<String> files() {
            return via.keySet().stream().map(pack.fileOf::get).filter(f -> f != null).distinct().toList();
        }

        /** "Because of this, 3 more mods can't load (they need those mods): ..." when there are any. */
        void appendTo(StringBuilder detail, int direct) {
            List<Map.Entry<String, String>> more = via.entrySet().stream().filter(e -> e.getValue() != null).toList();
            if (more.isEmpty()) return;
            detail.append("\nBecause of this, ").append(more.size()).append(more.size() == 1 ? " more mod" : " more mods")
                    .append(" can't load (").append(more.size() == 1 ? "it needs" : "they need").append(" ")
                    .append(direct == 1 ? "that mod" : "those mods").append("):");
            int shown = 0;
            for (Map.Entry<String, String> e : more) {
                if (shown++ == LIST_LIMIT) {
                    detail.append("\n- and ").append(more.size() - LIST_LIMIT).append(" more");
                    break;
                }
                detail.append("\n- ").append(pack.nameOf(e.getKey())).append(" (needs ")
                        .append(pack.nameOf(e.getValue())).append(')');
            }
        }
    }

    /** What the engine knows about the pack. */
    private static final class Pack {
        final Map<String, String> names = new HashMap<>();
        final Map<String, String> fileOf = new HashMap<>();
        /** mod id -> mods that require it */
        final Map<String, Set<String>> neededBy = new HashMap<>();
        final List<ModJar> jars;
        final List<String> otherFiles;

        Pack(List<ModInfo> loadedMods, List<ModJar> jars, List<String> otherFiles) {
            this.jars = jars;
            this.otherFiles = otherFiles;
            for (ModInfo mod : loadedMods) {
                names.putIfAbsent(mod.modId(), mod.name());
                for (Dependency d : mod.dependencies()) {
                    if (d.type() == Dependency.Type.REQUIRED && !d.modId().equals(mod.modId())) {
                        neededBy.computeIfAbsent(d.modId(), k -> new LinkedHashSet<>()).add(mod.modId());
                    }
                }
            }
            for (ModJar jar : jars) {
                for (ModInfo mod : jar.mods()) fileOf.putIfAbsent(mod.modId(), jar.fileName());
            }
        }

        String nameOf(String id) {
            return names.getOrDefault(id, id);
        }

        Affected affected(List<Blocker> group) {
            Map<String, String> via = new LinkedHashMap<>();
            Deque<String> queue = new ArrayDeque<>();
            for (Blocker b : group) {
                names.putIfAbsent(b.modId(), b.modName());
                if (!via.containsKey(b.modId())) {
                    via.put(b.modId(), null); // null = has the broken rule itself
                    queue.add(b.modId());
                }
            }
            Set<String> seen = new LinkedHashSet<>(via.keySet());
            while (!queue.isEmpty()) {
                String id = queue.poll();
                for (String dependent : neededBy.getOrDefault(id, Set.of())) {
                    if (seen.add(dependent)) {
                        via.put(dependent, id);
                        queue.add(dependent);
                    }
                }
            }
            return new Affected(via, this);
        }

        /** A jar in the mods folder that has this mod but didn't load (wrong loader or Minecraft version). */
        ModJar jarDeclaring(String id) {
            for (ModJar jar : jars) {
                if (jar.kind() == ModJar.Kind.BROKEN) continue;
                boolean has = jar.mods().stream().anyMatch(m -> m.modId().equals(id));
                if (has && jar.kind() != ModJar.Kind.LIBRARY) return jar;
            }
            return null;
        }

        ModJar brokenJarNamed(String id) {
            Pattern p = startsWithId(id);
            return jars.stream().filter(j -> j.kind() == ModJar.Kind.BROKEN && p.matcher(j.fileName()).find())
                    .findFirst().orElse(null);
        }

        String otherFileNamed(String id) {
            Pattern p = startsWithId(id);
            return otherFiles.stream().filter(n -> p.matcher(n).find()).findFirst().orElse(null);
        }

        /** "create" matches "Create-1.21.1-6.0.4.jar.disabled" but not "createaddition-1.0.jar". */
        private static Pattern startsWithId(String id) {
            StringBuilder regex = new StringBuilder("^");
            for (char c : id.toCharArray()) {
                regex.append(c == '_' ? "[-_ ]?" : Pattern.quote(String.valueOf(c)));
            }
            return Pattern.compile(regex.append("(?![a-z])").toString(), Pattern.CASE_INSENSITIVE);
        }
    }
}
