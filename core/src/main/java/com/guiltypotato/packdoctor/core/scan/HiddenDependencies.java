package com.guiltypotato.packdoctor.core.scan;

import com.guiltypotato.packdoctor.core.model.ModJar;
import com.guiltypotato.packdoctor.core.scan.Finding.Severity;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Finds mods whose main class uses another mod's code without listing it as a dependency. NeoForge can't warn
 * about these, so the game just crashes with NoClassDefFoundError (e.g. Bobber Detector using Create).
 * Only the {@code @Mod} classes are checked: code there runs while loading, so a missing mod really crashes it.
 */
public final class HiddenDependencies {
    /** What a class file contains when it's annotated with @Mod (NeoForge, or Forge for 1.20.1 and older). */
    private static final List<String> MOD_ANNOTATIONS = List.of("Lnet/neoforged/fml/common/Mod;",
            "Lnet/minecraftforge/fml/common/Mod;");

    /** Popular mods people build on, by the package their code lives in. */
    static final Map<String, String> PACKAGES = new LinkedHashMap<>();

    static {
        PACKAGES.put("com/simibubi/create/", "create");
        PACKAGES.put("appeng/", "ae2");
        PACKAGES.put("vazkii/botania/", "botania");
        PACKAGES.put("vazkii/patchouli/", "patchouli");
        PACKAGES.put("mekanism/", "mekanism");
        PACKAGES.put("mezz/jei/", "jei");
        PACKAGES.put("top/theillusivec4/curios/", "curios");
        PACKAGES.put("software/bernie/geckolib/", "geckolib");
        PACKAGES.put("dev/architectury/", "architectury");
        PACKAGES.put("me/shedaniel/clothconfig2/", "cloth_config");
        PACKAGES.put("dev/latvian/mods/kubejs/", "kubejs");
        PACKAGES.put("dev/ftb/mods/ftblibrary/", "ftblibrary");
        PACKAGES.put("dev/ftb/mods/ftbquests/", "ftbquests");
        PACKAGES.put("twilightforest/", "twilightforest");
        PACKAGES.put("io/redspace/ironsspellbooks/", "irons_spellbooks");
        PACKAGES.put("com/hollingsworth/arsnouveau/", "ars_nouveau");
        PACKAGES.put("net/p3pp3rf1y/sophisticatedcore/", "sophisticatedcore");
        PACKAGES.put("terrablender/", "terrablender");
        PACKAGES.put("dev/engine_room/flywheel/", "flywheel");
        PACKAGES.put("net/createmod/ponder/", "ponder");
        PACKAGES.put("net/blay09/mods/balm/", "balm");
    }

    private HiddenDependencies() {}

    /** Which popular mod a class belongs to ("com/simibubi/create/Foo" or "com.simibubi.create.Foo"), or null. */
    public static String modForClass(String className) {
        String path = className.replace('.', '/');
        for (Map.Entry<String, String> e : PACKAGES.entrySet()) {
            if (path.startsWith(e.getKey())) return e.getValue();
        }
        return null;
    }

    /**
     * @param jars      jars that get loaded
     * @param installed every mod id that's installed (top level or bundled)
     */
    static List<Finding> check(List<ModJar> jars, Set<String> installed) {
        Map<String, String> missing = new LinkedHashMap<>();
        PACKAGES.forEach((pkg, id) -> {
            if (!installed.contains(id)) missing.put(pkg, id);
        });
        List<Finding> out = new ArrayList<>();
        if (missing.isEmpty()) return out;
        for (ModJar jar : jars) {
            if ((jar.kind() != ModJar.Kind.NEOFORGE && jar.kind() != ModJar.Kind.FORGE) || jar.mods().isEmpty()) continue;
            Set<String> uses = usesFromModClasses(jar, missing);
            // Declared dependencies are already reported as plain missing mods.
            jar.mods().forEach(m -> m.dependencies().forEach(d -> uses.remove(d.modId())));
            if (uses.isEmpty()) continue;
            String name = jar.mods().get(0).name();
            for (String id : uses) {
                out.add(new Finding(Severity.ERROR, Finding.MISSING_DEPENDENCY,
                        "Hidden missing mod: " + name + " uses " + id,
                        name + "'s main code uses " + id + ", but " + id + " isn't installed. The mod doesn't list "
                                + id + " as something it needs, so NeoForge can't warn you: the game will most "
                                + "likely crash while loading with \"NoClassDefFoundError\".",
                        "Install " + id + ", or remove " + name + ".", List.of(jar.fileName())));
            }
        }
        return out;
    }

    private static Set<String> usesFromModClasses(ModJar jar, Map<String, String> missing) {
        Set<String> uses = new LinkedHashSet<>();
        try (ZipFile zip = new ZipFile(jar.file().toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (!e.getName().endsWith(".class") || e.getName().startsWith("META-INF/")) continue;
                String text;
                try (InputStream in = zip.getInputStream(e)) {
                    text = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
                }
                if (MOD_ANNOTATIONS.stream().noneMatch(text::contains)) continue;
                missing.forEach((pkg, id) -> {
                    if (refersTo(text, pkg) && !ownPackage(e.getName(), pkg) && !checksFor(text, id)) uses.add(id);
                });
            }
        } catch (IOException | RuntimeException ignored) {
            // Unreadable jars are reported elsewhere.
        }
        return uses;
    }

    /**
     * True if a class name starts with the package: right at the start of a string entry ("mekanism/api/Foo"),
     * or in a type descriptor ("Lmekanism/api/Foo;"). Not "compat/mekanism/..." in the mod's own code.
     */
    private static boolean refersTo(String classText, String pkg) {
        for (int i = classText.indexOf(pkg); i >= 0; i = classText.indexOf(pkg, i + 1)) {
            if (i >= 1 && classText.charAt(i - 1) == 'L') return true;
            if (i >= 3 && classText.charAt(i - 3) == '\u0001') return true;
        }
        return false;
    }

    /**
     * True if the class has the mod id as a plain string, the way {@code ModList.get().isLoaded("mekanism")} does.
     * Mods that check first only touch that mod's code when it's installed, so they're fine without it.
     * Bobber Detector has neither, and crashes without Create.
     * (A class file stores strings as a 0x01 tag, a 2-byte length, then the text.)
     */
    private static boolean checksFor(String classText, String modId) {
        // Also "ARS_NOUVEAU"/"MEKANISM": many mods keep an enum of optional mods and check Mods.MEKANISM.isLoaded().
        for (String s : List.of(modId, modId.toUpperCase(java.util.Locale.ROOT))) {
            String entry = "\u0001" + (char) (s.length() >> 8) + (char) (s.length() & 0xFF) + s;
            if (classText.contains(entry)) return true;
        }
        return false;
    }

    /** A mod's own classes living under that package (e.g. an add-on in com/simibubi/create/...) don't count. */
    private static boolean ownPackage(String classFile, String pkg) {
        return classFile.startsWith(pkg);
    }
}
