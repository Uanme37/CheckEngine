package com.guiltypotato.checkengine.core.scan;

import com.guiltypotato.checkengine.core.model.ModInfo;
import com.guiltypotato.checkengine.core.model.ModJar;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The same material from several mods: three aluminum ingots, two tin ores. Players get stuck with the "wrong" one and
 * recipes split. Read from each jar's item models (one per item), so it works without starting the game.
 */
public final class Materials {
    /** "assets/create/models/item/zinc_ingot.json" */
    private static final Pattern ITEM_MODEL = Pattern.compile("^assets/([a-z0-9_.-]+)/models/item/([a-z0-9_]+)\\.json$");
    /** The item kinds that make a material, and how to find the material in the item's name. */
    private static final List<Map.Entry<String, Pattern>> KINDS = List.of(
            Map.entry("ingot", Pattern.compile("^([a-z]+)_ingot$")),
            Map.entry("ore", Pattern.compile("^(?:deepslate_|nether_|end_|stone_)?([a-z]+)_ore$")),
            Map.entry("raw", Pattern.compile("^raw_([a-z]+)$")),
            Map.entry("dust", Pattern.compile("^([a-z]+)_dust$")),
            Map.entry("plate", Pattern.compile("^([a-z]+)_plate$")),
            Map.entry("gear", Pattern.compile("^([a-z]+)_gear$")),
            Map.entry("nugget", Pattern.compile("^([a-z]+)_nugget$")));
    /** Names that look like materials but aren't one ("weighted_pressure_plate", "glow_ingot"...). */
    private static final Set<String> NOT_MATERIALS = Set.of("weighted", "pressure", "light", "heavy", "redstone",
            "glowstone", "sugar", "blaze", "bone", "gunpowder", "wood", "stone", "dirt", "sand", "flour", "wheat",
            "magic", "dark", "pig", "chain", "mob", "ender", "soul", "glow", "blank", "empty", "fake");
    /** Mods that make duplicate materials count as one. */
    static final Map<String, String> UNIFIERS = Map.of("almostunified", "Almost Unified",
            "emendatusenigmatica", "Emendatus Enigmatica");

    /**
     * @param material "aluminum"
     * @param kinds    item kind -> the mods that add one: "ingot" -> [Immersive Engineering, Mekanism]
     */
    public record Duplicate(String material, Map<String, List<String>> kinds) {
        public int mods() {
            Set<String> all = new TreeSet<>();
            kinds.values().forEach(all::addAll);
            return all.size();
        }
    }

    private Materials() {}

    /** Every material that 2+ mods add the same kind of item for, most mods first. */
    public static List<Duplicate> find(List<ModJar> jars) {
        // material -> kind -> mod names
        Map<String, Map<String, Set<String>>> seen = new TreeMap<>();
        for (ModJar jar : jars) {
            if (jar.mods().isEmpty()) continue;
            Map<String, String> names = new LinkedHashMap<>();
            for (ModInfo m : jar.mods()) names.put(m.modId(), m.name());
            String fallback = jar.mods().get(0).name();
            try (ZipFile zip = new ZipFile(jar.file().toFile())) {
                Enumeration<? extends ZipEntry> e = zip.entries();
                while (e.hasMoreElements()) {
                    Matcher m = ITEM_MODEL.matcher(e.nextElement().getName());
                    if (!m.matches() || m.group(1).equals("minecraft")) continue;
                    String mod = names.getOrDefault(m.group(1), fallback);
                    for (Map.Entry<String, Pattern> kind : KINDS) {
                        Matcher k = kind.getValue().matcher(m.group(2));
                        if (!k.matches() || NOT_MATERIALS.contains(k.group(1))) continue;
                        seen.computeIfAbsent(k.group(1), x -> new TreeMap<>())
                                .computeIfAbsent(kind.getKey(), x -> new TreeSet<>()).add(mod);
                    }
                }
            } catch (IOException | RuntimeException ignored) {
                // a jar we can't open adds nothing
            }
        }
        List<Duplicate> out = new ArrayList<>();
        seen.forEach((material, kinds) -> {
            Map<String, List<String>> doubled = new LinkedHashMap<>();
            kinds.forEach((kind, mods) -> {
                if (mods.size() > 1) doubled.put(kind, List.copyOf(mods));
            });
            if (!doubled.isEmpty()) out.add(new Duplicate(material, doubled));
        });
        out.sort((a, b) -> Integer.compare(b.mods(), a.mods()));
        return out;
    }

    /** The unifier mods installed, by name (empty if none). */
    public static List<String> unifiers(List<ModJar> jars) {
        List<String> out = new ArrayList<>();
        for (ModJar j : jars) {
            for (ModInfo m : j.mods()) {
                String name = UNIFIERS.get(m.modId());
                if (name != null && !out.contains(name)) out.add(name);
            }
        }
        return out;
    }

    /** For the CLI and the report. */
    public static String toText(List<Duplicate> dupes, List<String> unifiers) {
        if (dupes.isEmpty()) return "No material is added by more than one mod.\n";
        StringBuilder sb = new StringBuilder("Same material from several mods (" + dupes.size() + "):\n");
        int shown = 0;
        for (Duplicate d : dupes) {
            if (shown++ == 25) {
                sb.append("  ...and ").append(dupes.size() - 25).append(" more\n");
                break;
            }
            // "Steel (ingot, nugget) from 6 mods: Alloyed, Create Big Cannons, ..."
            Set<String> mods = new TreeSet<>();
            d.kinds().values().forEach(mods::addAll);
            sb.append("  ").append(d.material().substring(0, 1).toUpperCase(Locale.ROOT))
                    .append(d.material().substring(1)).append(" (").append(String.join(", ", d.kinds().keySet()))
                    .append(") from ").append(mods.size()).append(" mods: ").append(String.join(", ", mods))
                    .append('\n');
        }
        sb.append(unifiers.isEmpty()
                ? "Tip: a unifier like Almost Unified makes them one item (same drops, same recipes).\n"
                : "You have " + String.join(" and ", unifiers) + ": check it covers these.\n");
        return sb.toString();
    }
}
