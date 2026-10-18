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
    private record Clash(String line, boolean exact) {}

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
    private final List<Recipe> recipes = new ArrayList<>();
    private final Set<String> made = new HashSet<>();
    private final Map<String, Set<String>> questNeeds = new TreeMap<>();

    /** @param names item id to the name players see, or the id itself when there's no better name */
    public RecipeCheck(Function<String, String> names) {
        this.names = names;
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
        List<String> clashes = all.stream().filter(Clash::exact).map(Clash::line).toList();
        List<String> overlaps = all.stream().filter(c -> !c.exact()).map(Clash::line).toList();
        if (!clashes.isEmpty()) {
            out.add(new Finding(Severity.WARNING, Finding.RECIPE_CLASH,
                    clashes.size() + (clashes.size() == 1 ? " recipe clash" : " recipe clashes"),
                    "These crafting recipes take exactly the same items but make different things, so only one of each "
                            + "pair can ever be crafted (which one depends on load order):\n" + list(clashes),
                    "Remove or change one recipe of each pair with KubeJS or CraftTweaker, or add Polymorph so players can pick.",
                    List.of()));
        }
        if (!overlaps.isEmpty()) {
            out.add(new Finding(Severity.INFO, Finding.RECIPE_CLASH,
                    overlaps.size() + (overlaps.size() == 1 ? " pair of recipes shares" : " pairs of recipes share")
                            + " some inputs",
                    "Both recipes of each pair can still be crafted, but with the items they share only one of them "
                            + "comes out, e.g. any planks for a Crafting Table and one kind of planks for a wooden one. "
                            + "Usually fine; if players say a recipe gives the wrong thing, look here:\n" + list(overlaps),
                    null, List.of()));
        }
        List<String> dead = deadRecipes();
        if (!dead.isEmpty()) {
            out.add(new Finding(Severity.WARNING, Finding.DEAD_RECIPE,
                    dead.size() + (dead.size() == 1 ? " recipe can't be crafted" : " recipes can't be crafted"),
                    "A slot asks for a tag that has no items in it, so nothing fits:\n" + list(dead),
                    "Add an item to the tag (KubeJS tags event or a datapack), or change the recipe.", List.of()));
        }
        List<String> quests = unobtainableQuestItems();
        if (!quests.isEmpty()) {
            // Info, not a warning: mob drops, spells and machines without recipe files are common in big packs.
            out.add(new Finding(Severity.INFO, Finding.UNOBTAINABLE_ITEM,
                    quests.size() + (quests.size() == 1 ? " quest item nothing gives" : " quest items nothing gives"),
                    "A quest asks for these, but no recipe, loot table or quest reward gives them. Most come another "
                            + "way (a mob drop, a spell, a machine without a recipe file, trading); check the ones that "
                            + "don't:\n" + list(quests),
                    "Give the item a recipe or a loot drop, or change the quest.", List.of()));
        }
        List<String> ingredients = unobtainableIngredients();
        if (!ingredients.isEmpty()) {
            out.add(new Finding(Severity.INFO, Finding.UNOBTAINABLE_ITEM,
                    ingredients.size() + (ingredients.size() == 1 ? " crafting ingredient nothing gives"
                            : " crafting ingredients nothing gives"),
                    "Crafting recipes need these, but no recipe, loot table or quest reward gives them, so those "
                            + "recipes may never be made. Often fine (mob drops, world blocks, trading):\n" + list(ingredients),
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
                        out.add(new Clash(clashLine(first, second), exact));
                    }
                }
            }
        }
        out.sort(java.util.Comparator.comparing(Clash::line));
        return out;
    }

    private String clashLine(Recipe a, Recipe b) {
        return name(a.result()) + " (" + a.id() + from(a) + ") and " + name(b.result()) + " (" + b.id() + from(b) + ")";
    }

    private static String from(Recipe r) {
        if (r.source() == null || r.source().isEmpty() || r.source().equals("mod/" + r.mod()) || r.source().equals("mod:" + r.mod())) return "";
        return ", from " + r.source();
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

    private List<String> deadRecipes() {
        List<String> out = new ArrayList<>();
        for (Recipe r : recipes) {
            List<String> labels = r.slots().stream().filter(Slot::dead).map(Slot::label).distinct().toList();
            if (!labels.isEmpty()) out.add(r.id() + from(r) + " (makes " + name(r.result()) + "): " + String.join(", ", labels));
        }
        out.sort(null);
        return out;
    }

    private List<String> unobtainableQuestItems() {
        List<String> out = new ArrayList<>();
        questNeeds.forEach((item, files) -> {
            if (gettable(item)) return;
            out.add(name(item) + ": " + String.join(", ", files));
        });
        return out;
    }

    private List<String> unobtainableIngredients() {
        Map<String, Set<String>> neededBy = new TreeMap<>();
        for (Recipe r : recipes) {
            for (Slot s : r.slots()) {
                if (s.isEmpty() || s.dead() || s.items().stream().anyMatch(this::gettable)) continue;
                // A slot that takes several items lists the first; one item is enough to make it craftable.
                String item = s.items().iterator().next();
                if (questNeeds.containsKey(base(item))) continue; // already in the quest finding
                neededBy.computeIfAbsent(base(item), k -> new LinkedHashSet<>()).add(r.id());
            }
        }
        List<String> out = new ArrayList<>();
        neededBy.forEach((item, ids) -> out.add(name(item) + ": needed by " + ids.iterator().next()
                + (ids.size() > 1 ? " and " + (ids.size() - 1) + " more" : "")));
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

    private String name(String item) {
        String id = base(item);
        String name = names.apply(id);
        return name == null || name.isEmpty() || name.equals(id) ? id : name + " (" + id + ")";
    }

    private static String list(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size() && i < MAX_LISTED; i++) sb.append("- ").append(lines.get(i)).append('\n');
        if (lines.size() > MAX_LISTED) sb.append("...and ").append(lines.size() - MAX_LISTED).append(" more\n");
        return sb.toString().stripTrailing();
    }

    /** Builds a slot from the items that fit, keeping their order. */
    public static Slot slot(String label, Collection<String> items) {
        return new Slot(new LinkedHashSet<>(items), label == null ? "" : label);
    }
}
