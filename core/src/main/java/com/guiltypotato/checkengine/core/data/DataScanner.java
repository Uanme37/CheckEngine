package com.guiltypotato.checkengine.core.data;

import com.guiltypotato.checkengine.core.json.Json;
import com.guiltypotato.checkengine.core.scan.Finding;
import com.guiltypotato.checkengine.core.scan.Finding.Severity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds recipes, loot tables and FTB Quests that use items from mods that aren't installed.
 * No Minecraft code: the mod feeds it file contents and tells it which namespaces exist.
 */
public final class DataScanner {
    /** Shown per missing mod before "...and N more". */
    private static final int MAX_LISTED = 10;

    /** {@code id: "ns:path"}, {@code item: "ns:path"} or {@code icon: "ns:path"} in a quest file. */
    private static final Pattern QUEST_ITEM = Pattern.compile(
            "^\\s*(?:id|item|icon):\\s*\"([a-z0-9_.-]+):([a-z0-9_./-]+)\"", Pattern.MULTILINE);
    /** FTB Quests swaps items it can't find for ftbquests:missing_item and keeps the old id here. */
    private static final Pattern QUEST_MISSING = Pattern.compile(
            "\"ftbquests:missing_item\":\\s*\"([^\"]+)\"");

    /** What kind of file a reference came from. */
    public enum Kind {
        RECIPE("recipe", "recipes"), LOOT_TABLE("loot table", "loot tables"), QUEST("quest file", "quest files");

        final String one;
        final String many;

        Kind(String one, String many) {
            this.one = one;
            this.many = many;
        }
    }

    /**
     * One use of an item.
     *
     * @param kind   recipe, loot table or quest
     * @param file   recipe/loot table id or quest file name
     * @param source datapack or mod it came from ("mod/create" on 1.21.1, "mod:create" on 1.20.1, "file/mypack.zip"),
     *               or null for quests
     * @param item   the item id, e.g. "artifacts:crystal_heart"
     */
    public record Ref(Kind kind, String file, String source, String item) {
        /** True when the reference ships inside a mod's own jar (the mod's problem, not the pack maker's). */
        boolean fromMod() {
            return source != null && (source.startsWith("mod:") || source.startsWith("mod/"));
        }
    }

    private final Predicate<String> namespaceExists;
    private final Map<String, List<Ref>> byMissingMod = new TreeMap<>();
    private final List<Ref> brokenQuestItems = new ArrayList<>();
    private final List<String> unreadable = new ArrayList<>();
    private final Map<Kind, Integer> checked = new LinkedHashMap<>();

    /** @param namespaceExists true for "minecraft", loaded mod ids, and any namespace with registered items */
    public DataScanner(Predicate<String> namespaceExists) {
        this.namespaceExists = namespaceExists;
    }

    public void recipe(String id, String source, String json) {
        Map<String, Object> obj = parse(id, json);
        // Old Forge recipes put load conditions under a plain "conditions" key.
        if (obj == null || isConditional(obj) || obj.containsKey("conditions")) return;
        count(Kind.RECIPE);
        // A recipe type from a missing mod means the whole recipe can't load.
        if (obj.get("type") instanceof String type) use(new Ref(Kind.RECIPE, id, source, type));
        walk(obj, v -> use(new Ref(Kind.RECIPE, id, source, v)), "item", "id");
        // Since 1.21.2 ingredients are plain strings: "ingredients": ["mod:gear"], "key": {"A": "mod:gear"}.
        ingredients(obj, v -> use(new Ref(Kind.RECIPE, id, source, v)), false);
    }

    /** Recipe fields that hold ingredients. */
    private static final Set<String> INGREDIENT_KEYS =
            Set.of("ingredients", "ingredient", "key", "base", "addition", "template", "input", "inputs");

    /** Calls {@code found} for every item id written as a plain string inside an ingredient field (not tags). */
    private static void ingredients(Object node, Consumer<String> found, boolean inside) {
        if (node instanceof Map<?, ?> map) {
            if (isConditional(map)) return;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                boolean in = inside || INGREDIENT_KEYS.contains(e.getKey());
                if (e.getValue() instanceof String s) {
                    if (in && !"tag".equals(e.getKey()) && !"type".equals(e.getKey()) && !s.startsWith("#")) found.accept(s);
                } else {
                    ingredients(e.getValue(), found, in);
                }
            }
        } else if (node instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof String s) {
                    if (inside && !s.startsWith("#")) found.accept(s);
                } else {
                    ingredients(o, found, inside);
                }
            }
        }
    }

    public void lootTable(String id, String source, String json) {
        Map<String, Object> obj = parse(id, json);
        if (obj == null || isConditional(obj)) return;
        count(Kind.LOOT_TABLE);
        walkLootItems(obj, v -> use(new Ref(Kind.LOOT_TABLE, id, source, v)));
    }

    public void questFile(String name, String snbt) {
        count(Kind.QUEST);
        Matcher m = QUEST_ITEM.matcher(snbt);
        while (m.find()) {
            String path = m.group(2);
            if (path.startsWith("textures/") || path.endsWith(".png")) continue; // chapter images
            use(new Ref(Kind.QUEST, name, null, m.group(1) + ":" + path));
        }
        Matcher missing = QUEST_MISSING.matcher(snbt);
        while (missing.find()) brokenQuestItems.add(new Ref(Kind.QUEST, name, null, missing.group(1)));
    }

    public int checked(Kind kind) {
        return checked.getOrDefault(kind, 0);
    }

    public List<Finding> findings() {
        List<Finding> out = new ArrayList<>();
        // Mods like Farming for Blockheads ship recipes for dozens of optional mods; one note covers them all.
        List<String> leftovers = new ArrayList<>();
        byMissingMod.forEach((mod, refs) -> {
            if (refs.stream().allMatch(Ref::fromMod)) {
                leftovers.add(mod + " (" + refs.stream().map(Ref::file).distinct().count() + ")");
            } else {
                out.add(missingMod(mod, refs));
            }
        });
        if (!leftovers.isEmpty()) {
            out.add(new Finding(Severity.INFO, Finding.ITEMS_FROM_MISSING_MOD,
                    "Built-in extras for mods you don't have",
                    "Some mods ship recipes or loot for optional mods that aren't installed. Minecraft just skips "
                            + "them, so there's nothing to fix: " + String.join(", ", leftovers) + ".",
                    null, List.of()));
        }
        if (!brokenQuestItems.isEmpty()) out.add(brokenQuests());
        if (!unreadable.isEmpty()) {
            out.add(new Finding(Severity.INFO, Finding.UNREADABLE_METADATA,
                    unreadable.size() + " data files couldn't be read",
                    "These aren't valid JSON, so Minecraft skips them too:\n" + list(unreadable),
                    null, List.of()));
        }
        out.sort(Comparator.comparing(Finding::severity).thenComparing(Finding::title));
        return out;
    }

    private Finding missingMod(String mod, List<Ref> refs) {
        Map<Kind, Integer> perKind = new LinkedHashMap<>();
        for (Kind k : Kind.values()) {
            int n = (int) refs.stream().filter(r -> r.kind() == k).map(Ref::file).distinct().count();
            if (n > 0) perKind.put(k, n);
        }
        List<String> parts = new ArrayList<>();
        perKind.forEach((k, n) -> parts.add(n + " " + (n == 1 ? k.one : k.many)));
        List<String> lines = refs.stream().map(r -> r.kind().one + " " + r.file()
                        + (r.source() != null ? " (from " + r.source() + ")" : "") + ": " + r.item())
                .distinct().toList();
        boolean one = perKind.size() == 1 && perKind.values().iterator().next() == 1;
        String detail = String.join(", ", parts) + (one ? " uses" : " use") + " items from \"" + mod + "\", but that mod isn't installed:\n"
                + list(lines);
        return new Finding(Severity.WARNING, Finding.ITEMS_FROM_MISSING_MOD, "Items from a missing mod: " + mod,
                detail, "Install " + mod + ", or swap these items for ones that exist.", List.of());
    }

    private Finding brokenQuests() {
        List<String> lines = brokenQuestItems.stream().map(r -> r.file() + ": " + r.item()).distinct().toList();
        return new Finding(Severity.WARNING, Finding.BROKEN_QUEST_ITEM,
                lines.size() + (lines.size() == 1 ? " quest item is" : " quest items are") + " already broken",
                "FTB Quests couldn't find these items and replaced them with a \"missing item\" placeholder:\n"
                        + list(lines),
                "Open the quest book in edit mode and pick a new item, or install the mod that adds it.",
                List.of());
    }

    private void use(Ref ref) {
        int colon = ref.item().indexOf(':');
        if (colon <= 0) return; // "stone" means minecraft:stone
        String ns = ref.item().substring(0, colon);
        // Tags ("#c:ingots"), shared tag namespaces ("c", "forge") and text that isn't an id at all aren't mods.
        if (!ns.matches("[a-z0-9_.-]+") || ns.equals("c") || ns.equals("forge")) return;
        if (!namespaceExists.test(ns)) byMissingMod.computeIfAbsent(ns, k -> new ArrayList<>()).add(ref);
    }

    private void count(Kind kind) {
        checked.merge(kind, 1, Integer::sum);
    }

    private Map<String, Object> parse(String id, String json) {
        try {
            return Json.parseObject(json);
        } catch (RuntimeException e) {
            unreadable.add(id);
            return null;
        }
    }

    /**
     * Files (or parts) that load only when some mod is present are fine to point at that mod.
     * Not plain "conditions": loot tables use that for drop rules like "killed by a player".
     */
    private static boolean isConditional(Map<?, ?> obj) {
        return obj.containsKey("neoforge:conditions") || obj.containsKey("forge:conditions")
                || obj.containsKey("fabric:load_conditions");
    }

    /** Calls {@code found} for every string value of the given keys, anywhere in the tree. */
    private static void walk(Object node, Consumer<String> found, String... keys) {
        if (node instanceof Map<?, ?> map) {
            if (isConditional(map)) return;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (e.getValue() instanceof String s && List.of(keys).contains(e.getKey())) found.accept(s);
                else walk(e.getValue(), found, keys);
            }
        } else if (node instanceof List<?> list) {
            for (Object o : list) walk(o, found, keys);
        }
    }

    /** Loot entries look like {"type": "minecraft:item", "name": "ns:item"}. */
    private static void walkLootItems(Object node, Consumer<String> found) {
        if (node instanceof Map<?, ?> map) {
            if (isConditional(map)) return;
            if (("minecraft:item".equals(map.get("type")) || "item".equals(map.get("type")))
                    && map.get("name") instanceof String name) {
                found.accept(name);
            }
            for (Object v : map.values()) walkLootItems(v, found);
        } else if (node instanceof List<?> list) {
            for (Object o : list) walkLootItems(o, found);
        }
    }

    private static String list(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size() && i < MAX_LISTED; i++) sb.append("- ").append(lines.get(i)).append('\n');
        if (lines.size() > MAX_LISTED) sb.append("...and ").append(lines.size() - MAX_LISTED).append(" more\n");
        return sb.toString().stripTrailing();
    }
}
