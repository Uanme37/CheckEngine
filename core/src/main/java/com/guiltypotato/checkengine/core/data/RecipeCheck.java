package com.guiltypotato.checkengine.core.data;

import com.guiltypotato.checkengine.core.scan.Finding;
import com.guiltypotato.checkengine.core.scan.Finding.Severity;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Recipe clashes (two crafting recipes with the same grid that make different things), recipes nobody can make
 * (a slot whose tag has no items), and items a quest or a crafting recipe needs that nothing gives.
 * No Minecraft code: the mod feeds it the recipes the game actually loaded, after KubeJS and CraftTweaker.
 */
public final class RecipeCheck {
    /** Clash lines listed in the report before "...and N more". */
    private static final int MAX_LISTED = 50;
    /** The label of an ingredient that checks more than the item (a mod's own ingredient type); its items can't be trusted. */
    public static final String SPECIAL = "a special ingredient";

    /**
     * One crafting-grid slot.
     *
     * @param items the items that fit (item id, plus a suffix when only a special version fits)
     * @param label what the recipe asks for, for messages ("#c:ingots/tin"); empty for an empty slot
     */
    public record Slot(Set<String> items, String label) {
        public static final Slot EMPTY = new Slot(Set.of(), "");

        public boolean isEmpty() {
            return label.isEmpty() && items.isEmpty();
        }

        /** Asks for something, but nothing fits: a tag with no items in it. */
        boolean dead() {
            return !label.isEmpty() && items.isEmpty();
        }

        boolean overlaps(Slot other) {
            if (isEmpty() || other.isEmpty()) return isEmpty() && other.isEmpty();
            Set<String> small = items.size() <= other.items.size() ? items : other.items;
            Set<String> big = small == items ? other.items : items;
            for (String s : small) if (big.contains(s)) return true;
            return false;
        }

        /** Takes exactly the same items. */
        boolean same(Slot other) {
            return items.equals(other.items);
        }
    }

    /** Two recipes some grid fits; only an exact clash means one of them can never be crafted. */
    private record Clash(Recipe a, Recipe b, boolean exact) {}

    private record Recipe(String id, String source, String result, int width, int height, List<Slot> slots,
                          boolean shapeless) {
        /** The slots that hold something, which is all a shapeless recipe looks at. */
        List<Slot> filled() {
            return slots.stream().filter(s -> !s.isEmpty()).toList();
        }

        String mod() {
            int colon = id.indexOf(':');
            return colon > 0 ? id.substring(0, colon) : id;
        }
    }

    private final Function<String, String> names;
    private final Function<String, String> modNames;
    private final List<Recipe> recipes = new ArrayList<>();
    private final Set<String> made = new HashSet<>();
    private final Map<String, Set<String>> questNeeds = new TreeMap<>();

    /** @param names item id to the name players see, or the id itself when there's no better name */
    public RecipeCheck(Function<String, String> names) {
        this(names, id -> null);
    }

    /** @param modNames mod id to the mod's display name, or null when it isn't known */
    public RecipeCheck(Function<String, String> names, Function<String, String> modNames) {
        this.names = names;
        this.modNames = modNames;
    }

    public void shaped(String id, String source, String result, int width, int height, List<Slot> slots) {
        if (slots.size() != width * height) throw new IllegalArgumentException(id + ": " + slots.size() + " slots for " + width + "x" + height);
        recipes.add(new Recipe(id, source, result, width, height, List.copyOf(slots), false));
        made(result);
    }

    public void shapeless(String id, String source, String result, List<Slot> slots) {
        recipes.add(new Recipe(id, source, result, 0, 0, List.copyOf(slots), true));
        made(result);
    }

    /** Something gives this item: any recipe's output, a loot table, a quest reward. */
    public void made(String item) {
        if (item != null && !item.isEmpty()) made.add(base(item));
    }

    public void madeAll(Collection<String> items) {
        items.forEach(this::made);
    }

    /** A quest asks the player to hand in or collect this item. */
    public void questNeeds(String questFile, String item) {
        questNeeds.computeIfAbsent(base(item), k -> new LinkedHashSet<>()).add(questFile);
    }

    public int checked() {
        return recipes.size();
    }

    public List<Finding> findings() {
        List<Finding> out = new ArrayList<>();
        List<Clash> all = clashes();
        List<List<Recipe>> clashes = groups(all.stream().filter(Clash::exact).toList());
        List<List<Recipe>> overlaps = groups(all.stream().filter(c -> !c.exact()).toList());
        if (!clashes.isEmpty()) {
            out.add(new Finding(Severity.WARNING, Finding.RECIPE_CLASH,
                    clashes.size() + (clashes.size() == 1 ? " recipe clash" : " recipe clashes"),
                    "Each group below is crafting recipes with exactly the same ingredients but different results. "
                            + "Only one recipe of each group ever works (which one depends on load order):\n\n"
                            + list(clashes.stream().map(g -> groupLine(g, true)).toList(), true)
                            + "\n\n" + idList(clashes),
                    "Remove or change the extra recipes with KubeJS or CraftTweaker, or add Polymorph so players can pick.",
                    List.of()));
        }
        if (!overlaps.isEmpty()) {
            out.add(new Finding(Severity.INFO, Finding.RECIPE_CLASH,
                    overlaps.size() + (overlaps.size() == 1 ? " group of recipes shares" : " groups of recipes share")
                            + " some ingredients",
                    "All of these can still be crafted, but where their ingredients overlap only one comes out, e.g. "
                            + "any planks for a Crafting Table and one kind of planks for a wooden one. Usually fine; if "
                            + "players say a recipe gives the wrong thing, look here:\n\n"
                            + list(overlaps.stream().map(g -> groupLine(g, false)).toList(), true),
                    null, List.of()));
        }
        List<Recipe> dead = deadRecipes();
        if (!dead.isEmpty()) {
            out.add(new Finding(Severity.WARNING, Finding.DEAD_RECIPE,
                    dead.size() + (dead.size() == 1 ? " recipe can't be crafted" : " recipes can't be crafted"),
                    "A slot asks for a tag with no items in it, so nothing fits:\n"
                            + list(dead.stream().map(this::deadLine).toList(), false)
                            + "\n\nRecipe ids, for KubeJS or CraftTweaker:\n"
                            + list(dead.stream().map(Recipe::id).toList(), false),
                    "Add an item to the tag (KubeJS tags event or a datapack), or change the recipe.", List.of()));
        }
        List<String> quests = unobtainableQuestItems();
        if (!quests.isEmpty()) {
            // Info, not a warning: mob drops, spells and machines without recipe files are common in big packs.
            out.add(new Finding(Severity.INFO, Finding.UNOBTAINABLE_ITEM,
                    quests.size() + (quests.size() == 1 ? " quest item nothing gives" : " quest items nothing gives"),
                    "A quest asks for these, but no recipe, loot table or quest reward gives them. Most come another "
                            + "way (a mob drop, a spell, a machine without a recipe file, trading); check the ones that "
                            + "don't:\n" + list(quests, false),
                    "Give the item a recipe or a loot drop, or change the quest.", List.of()));
        }
        List<String> ingredients = unobtainableIngredients();
        if (!ingredients.isEmpty()) {
            out.add(new Finding(Severity.INFO, Finding.UNOBTAINABLE_ITEM,
                    ingredients.size() + (ingredients.size() == 1 ? " crafting ingredient nothing gives"
                            : " crafting ingredients nothing gives"),
                    "Crafting recipes need these, but no recipe, loot table or quest reward gives them, so those "
                            + "recipes may never be made. Often fine (mob drops, world blocks, trading):\n" + list(ingredients, false),
                    null, List.of()));
        }
        return out;
    }

    // ---- clashes ----

    private List<Clash> clashes() {
        // A mod's own ingredient type checks more than the item, so its item list would make false clashes.
        List<Recipe> live = recipes.stream().filter(r -> r.slots().stream()
                .noneMatch(s -> s.dead() || SPECIAL.equals(s.label()))).toList();
        // Bucket by what must match exactly, then only compare recipes that share an item in their first slot.
        Map<String, List<Recipe>> buckets = new HashMap<>();
        for (Recipe r : live) {
            buckets.computeIfAbsent(r.shapeless() ? "n" + r.filled().size() : r.width() + "x" + r.height(), k -> new ArrayList<>()).add(r);
            // A shaped recipe also clashes with a shapeless one holding the same items.
            if (!r.shapeless()) buckets.computeIfAbsent("n" + r.filled().size(), k -> new ArrayList<>()).add(r);
        }
        Set<String> seen = new HashSet<>();
        List<Clash> out = new ArrayList<>();
        for (Map.Entry<String, List<Recipe>> bucket : buckets.entrySet()) {
            boolean byCount = bucket.getKey().startsWith("n");
            Map<String, List<Recipe>> byItem = new HashMap<>();
            for (Recipe r : bucket.getValue()) {
                for (Slot s : r.filled()) for (String item : s.items()) byItem.computeIfAbsent(item, k -> new ArrayList<>()).add(r);
            }
            for (Recipe a : bucket.getValue()) {
                List<Slot> filled = a.filled();
                if (filled.isEmpty()) continue;
                // A partner shares an item with every filled slot, so look up through the smallest one. Identity set:
                // hashing a Recipe hashes every tag's items, which hung big packs.
                Slot probe = filled.stream().min(java.util.Comparator.comparingInt(s -> s.items().size())).get();
                Set<Recipe> partners = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
                for (String item : probe.items()) partners.addAll(byItem.getOrDefault(item, List.of()));
                for (Recipe b : partners) {
                    if (a == b || base(a.result()).equals(base(b.result()))) continue;
                    // Two shaped recipes are compared as grids; only pairs with a shapeless recipe go by count.
                    if (byCount && !a.shapeless() && !b.shapeless()) continue;
                    if (!byCount && (a.shapeless() || b.shapeless())) continue;
                    String key = a.id().compareTo(b.id()) < 0 ? a.id() + "|" + b.id() : b.id() + "|" + a.id();
                    if (seen.contains(key)) continue;
                    boolean clash = byCount ? matches(a.filled(), b.filled(), Slot::overlaps) : sameGrid(a, b, Slot::overlaps);
                    if (clash) {
                        seen.add(key);
                        Recipe first = a.id().compareTo(b.id()) < 0 ? a : b;
                        Recipe second = first == a ? b : a;
                        boolean exact = byCount ? matches(a.filled(), b.filled(), Slot::same) : sameGrid(a, b, Slot::same);
                        out.add(new Clash(first, second, exact));
                    }
                }
            }
        }
        return out;
    }

    /** Pairs joined into groups: 4 mods with the same steel block recipe are one group, not 6 pairs. */
    private List<List<Recipe>> groups(List<Clash> pairs) {
        Map<Recipe, Recipe> parent = new java.util.IdentityHashMap<>();
        for (Clash c : pairs) {
            Recipe ra = root(parent, c.a());
            Recipe rb = root(parent, c.b());
            if (ra != rb) parent.put(ra, rb);
        }
        Map<Recipe, List<Recipe>> byRoot = new java.util.IdentityHashMap<>();
        for (Recipe r : new ArrayList<>(parent.keySet())) byRoot.computeIfAbsent(root(parent, r), k -> new ArrayList<>()).add(r);
        java.util.Comparator<Recipe> order = java.util.Comparator.comparing((Recipe r) -> plainName(r.result()))
                .thenComparing(Recipe::id);
        List<List<Recipe>> out = new ArrayList<>();
        for (List<Recipe> g : byRoot.values()) {
            g.sort(order);
            out.add(g);
        }
        out.sort(java.util.Comparator.comparing((List<Recipe> g) -> plainName(g.get(0).result())).thenComparing(g -> g.get(0).id()));
        return out;
    }

    private static Recipe root(Map<Recipe, Recipe> parent, Recipe r) {
        parent.putIfAbsent(r, r);
        Recipe p = parent.get(r);
        while (p != parent.get(p)) p = parent.get(p);
        parent.put(r, p);
        return p;
    }

    /**
     * Two lines per group: what's made and from what, then who added the recipes.
     * "Block of Steel (4 versions) - from 9x any steel ingot" / "All the Ores, Oritech, ..."
     */
    private String groupLine(List<Recipe> g, boolean exact) {
        List<String> made = g.stream().map(r -> plainName(r.result())).distinct().toList();
        String what = made.size() == 1 ? made.get(0) + " (" + g.size() + " versions)" : shortList(made, " / ", 5);
        String how = exact ? " - from " + ingredients(g.get(0)) : " - share some ingredients";
        List<String> who = g.stream().map(this::addedBy).distinct().toList();
        return what + how + "\n    " + shortList(who, ", ", 8);
    }

    /** "9x any steel ingot", "4x Depthrock", "1x Raw Beef + 1x Stick". */
    private String ingredients(Recipe r) {
        Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (Slot s : r.filled()) counts.merge(slotName(s), 1, Integer::sum);
        List<String> parts = new ArrayList<>();
        counts.forEach((k, n) -> parts.add(n + "x " + k));
        return shortList(parts, " + ", 4);
    }

    /** What a slot asks for, in words: "any steel ingot" for #c:ingots/steel, the item's name for an item. */
    private String slotName(Slot s) {
        if (s.label().isEmpty()) return s.items().isEmpty() ? "?" : plainName(s.items().iterator().next());
        List<String> parts = new ArrayList<>();
        for (String p : s.label().split(" or ")) {
            if (p.startsWith("#")) parts.add(tagName(p.substring(1)));
            else if (p.indexOf(':') > 0) parts.add(plainName(p));
            else parts.add(p);
        }
        return String.join(" or ", parts);
    }

    /** "c:ingots/steel" to "any steel ingot", "minecraft:planks" to "any planks". */
    public static String tagName(String tag) {
        int colon = tag.indexOf(':');
        String[] path = (colon >= 0 ? tag.substring(colon + 1) : tag).split("/");
        String last = path[path.length - 1].replace('_', ' ');
        if (path.length == 1) return "any " + last;
        // "tools/hammers" says it all in the last part; "ingots/steel" needs both: "steel ingot".
        if (plural(last)) return "any " + last.substring(0, last.length() - 1);
        String kind = path[0].replace('_', ' ');
        if (plural(kind)) kind = kind.substring(0, kind.length() - 1);
        return "any " + last + " " + kind;
    }

    private static boolean plural(String word) {
        return word.endsWith("s") && !word.endsWith("ss");
    }

    /** Who put a recipe in the pack, in words: a mod's name, "your CraftTweaker script", "datapack x". */
    private String addedBy(Recipe r) {
        String src = r.source();
        if (src == null || src.isEmpty() || src.equals("a script")) {
            if (r.mod().equals("crafttweaker")) return "your CraftTweaker script";
            if (r.mod().equals("kubejs")) return "your KubeJS script";
            return "a script";
        }
        if (src.equals("vanilla")) return "Minecraft";
        if (src.startsWith("mod/") || src.startsWith("mod:")) {
            String id = src.substring(4);
            int comma = id.indexOf(',');
            return modName(comma > 0 ? id.substring(0, comma) : id);
        }
        if (src.endsWith(".jar")) return modName(r.mod());
        String pack = src.startsWith("file/") || src.startsWith("data/") ? src.substring(5) : src;
        return "datapack " + pack;
    }

    private String modName(String id) {
        if (id.equals("minecraft")) return "Minecraft";
        String name = modNames.apply(id);
        return name == null || name.isBlank() ? id : name.strip();
    }

    /** One line per group: "Block of Steel: alltheores:crafting/steel_block, oritech:steelblock". */
    private String idList(List<List<Recipe>> groups) {
        List<String> lines = groups.stream().map(g -> plainName(g.get(0).result()) + ": "
                + String.join(", ", g.stream().map(Recipe::id).toList())).toList();
        return "Recipe ids, for KubeJS or CraftTweaker:\n" + list(lines, false);
    }

    private static String shortList(List<String> items, String sep, int max) {
        if (items.size() <= max) return String.join(sep, items);
        return String.join(sep, items.subList(0, max)) + sep + "+" + (items.size() - max) + " more";
    }

    /** Shaped recipes: slot by slot, or against the mirror image, like the crafting table checks. */
    private static boolean sameGrid(Recipe a, Recipe b, java.util.function.BiPredicate<Slot, Slot> fits) {
        if (a.width() != b.width() || a.height() != b.height()) return false;
        boolean straight = true;
        boolean mirrored = true;
        for (int y = 0; y < a.height(); y++) {
            for (int x = 0; x < a.width(); x++) {
                Slot s = a.slots().get(y * a.width() + x);
                if (straight && !fits.test(s, b.slots().get(y * b.width() + x))) straight = false;
                if (mirrored && !fits.test(s, b.slots().get(y * b.width() + (b.width() - 1 - x)))) mirrored = false;
                if (!straight && !mirrored) return false;
            }
        }
        return true;
    }

    /** Shapeless: is there a way to pair every slot of one with a slot of the other that fits it? */
    private static boolean matches(List<Slot> a, List<Slot> b, java.util.function.BiPredicate<Slot, Slot> fits) {
        if (a.size() != b.size()) return false;
        int[] matchOfB = new int[b.size()];
        java.util.Arrays.fill(matchOfB, -1);
        for (int i = 0; i < a.size(); i++) {
            if (!augment(i, a, b, matchOfB, new boolean[b.size()], fits)) return false;
        }
        return true;
    }

    private static boolean augment(int i, List<Slot> a, List<Slot> b, int[] matchOfB, boolean[] visited,
                                   java.util.function.BiPredicate<Slot, Slot> fits) {
        for (int j = 0; j < b.size(); j++) {
            if (visited[j] || !fits.test(a.get(i), b.get(j))) continue;
            visited[j] = true;
            if (matchOfB[j] < 0 || augment(matchOfB[j], a, b, matchOfB, visited, fits)) {
                matchOfB[j] = i;
                return true;
            }
        }
        return false;
    }

    // ---- recipes and items nobody can get ----

    private List<Recipe> deadRecipes() {
        List<Recipe> out = new ArrayList<>();
        for (Recipe r : recipes) if (r.slots().stream().anyMatch(Slot::dead)) out.add(r);
        out.sort(java.util.Comparator.comparing((Recipe r) -> plainName(r.result())).thenComparing(Recipe::id));
        return out;
    }

    /** "Metro Window (Bells & Whistles): needs #c:glass, which has no items" */
    private String deadLine(Recipe r) {
        List<String> labels = r.slots().stream().filter(Slot::dead).map(Slot::label).distinct().toList();
        return plainName(r.result()) + " (" + addedBy(r) + "): needs " + String.join(", ", labels)
                + (labels.size() == 1 ? ", which has no items" : ", which have no items");
    }

    private List<String> unobtainableQuestItems() {
        List<String> out = new ArrayList<>();
        questNeeds.forEach((item, files) -> {
            if (gettable(item)) return;
            out.add(itemWithMod(item) + ": asked in " + String.join(", ", files.stream().map(RecipeCheck::questName).toList()));
        });
        return out;
    }

    private List<String> unobtainableIngredients() {
        Map<String, Set<Recipe>> neededBy = new TreeMap<>();
        for (Recipe r : recipes) {
            for (Slot s : r.slots()) {
                if (s.isEmpty() || s.dead() || s.items().stream().anyMatch(this::gettable)) continue;
                // A slot that takes several items lists the first; one item is enough to make it craftable.
                String item = s.items().iterator().next();
                if (questNeeds.containsKey(base(item))) continue; // already in the quest finding
                neededBy.computeIfAbsent(base(item), k -> new LinkedHashSet<>()).add(r);
            }
        }
        List<String> out = new ArrayList<>();
        neededBy.forEach((item, rs) -> out.add(itemWithMod(item) + ": needed for "
                + plainName(rs.iterator().next().result()) + (rs.size() > 1 ? " and " + (rs.size() - 1) + " more" : "")));
        return out;
    }

    /** Made by something, or an item players normally get from the world rather than a recipe or loot. */
    private boolean gettable(String item) {
        String id = base(item);
        if (made.contains(id)) return true;
        int colon = id.indexOf(':');
        String ns = colon > 0 ? id.substring(0, colon) : "minecraft";
        // Vanilla items come from villagers, bartering, shearing, buckets and more that no data file lists.
        return ns.equals("minecraft") || id.endsWith("_spawn_egg") || id.endsWith("_bucket");
    }

    /** "create:gear{...}" (a special version) to "create:gear". */
    private static String base(String item) {
        int brace = item.indexOf('{');
        return brace >= 0 ? item.substring(0, brace) : item;
    }

    /** The name players see, or the id when there's no better name. */
    private String plainName(String item) {
        String id = base(item);
        String name = cleanName(names.apply(id));
        return name == null || name.isEmpty() ? id : name;
    }

    /** "Ambergris (Alex's Mobs)", or the id when the item has no name. */
    private String itemWithMod(String item) {
        String id = base(item);
        String name = plainName(id);
        if (name.equals(id)) return id;
        int colon = id.indexOf(':');
        return colon > 0 ? name + " (" + modName(id.substring(0, colon)) + ")" : name;
    }

    /** "chapters/act_iv.snbt" to "act_iv". */
    private static String questName(String file) {
        String f = file.startsWith("chapters/") ? file.substring(9) : file;
        return f.endsWith(".snbt") ? f.substring(0, f.length() - 5) : f;
    }

    /** Drops colour codes, and names the game never translated ("item.mod.thing", "Vial of %s"). */
    public static String cleanName(String name) {
        if (name == null) return null;
        name = name.replaceAll("§.", "").trim();
        if (name.contains("%s") || name.matches("(item|block)\\.[a-z0-9_./]+")) return null;
        return name;
    }

    private static String list(List<String> lines, boolean spaced) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size() && i < MAX_LISTED; i++) {
            if (spaced && i > 0) sb.append('\n');
            sb.append("- ").append(lines.get(i)).append('\n');
        }
        if (lines.size() > MAX_LISTED) sb.append("...and ").append(lines.size() - MAX_LISTED).append(" more\n");
        return sb.toString().stripTrailing();
    }

    /** Builds a slot from the items that fit, keeping their order. */
    public static Slot slot(String label, Collection<String> items) {
        return new Slot(new LinkedHashSet<>(items), label == null ? "" : label);
    }
}
