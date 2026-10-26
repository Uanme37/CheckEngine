package com.guiltypotato.checkengine.core.data;

import com.guiltypotato.checkengine.core.data.RecipeCheck.Slot;
import com.guiltypotato.checkengine.core.json.Json;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads crafting recipes and item tags from their JSON files for {@link RecipeCheck}, for when the game's own
 * recipe list can't be used. Misses what KubeJS and CraftTweaker scripts change, since those never become files.
 */
public final class RecipeFiles {
    private final Map<String, Set<String>> tagEntries = new HashMap<>();
    private final Map<String, Set<String>> resolved = new HashMap<>();

    /** One tag file, e.g. id "c:ingots/copper" from data/c/tags/item/ingots/copper.json. Files for one tag add up. */
    public void tag(String id, String json) {
        Map<String, Object> obj;
        try {
            obj = Json.parseObject(json);
        } catch (RuntimeException e) {
            return;
        }
        Set<String> entries = tagEntries.computeIfAbsent(id, k -> new LinkedHashSet<>());
        if (Boolean.TRUE.equals(obj.get("replace"))) entries.clear();
        if (!(obj.get("values") instanceof List<?> values)) return;
        for (Object v : values) {
            if (v instanceof String s) entries.add(s);
            else if (v instanceof Map<?, ?> m && m.get("id") instanceof String s) entries.add(s);
        }
        resolved.clear();
    }

    /** Hands a crafting table recipe to {@code check}; anything else only counts as a way to get its output. */
    public void recipe(RecipeCheck check, String id, String source, String json) {
        Map<String, Object> obj;
        try {
            obj = Json.parseObject(json);
        } catch (RuntimeException e) {
            return;
        }
        // Conditional recipes may or may not load; leave them out rather than guess.
        if (obj.containsKey("neoforge:conditions") || obj.containsKey("forge:conditions") || obj.containsKey("conditions")) return;
        String type = String.valueOf(obj.get("type"));
        String result = result(obj.get("result"));
        if (result == null) return;
        if (type.equals("minecraft:crafting_shaped") && obj.get("pattern") instanceof List<?> rows
                && obj.get("key") instanceof Map<?, ?> key) {
            List<String> pattern = trim(rows.stream().map(String::valueOf).toList());
            if (pattern.isEmpty()) return;
            int width = pattern.get(0).length();
            List<Slot> slots = new ArrayList<>();
            for (String row : pattern) {
                for (int x = 0; x < width; x++) {
                    char c = x < row.length() ? row.charAt(x) : ' ';
                    if (c == ' ') {
                        slots.add(Slot.EMPTY);
                    } else {
                        Slot s = slot(key.get(String.valueOf(c)));
                        if (s == null) return;
                        slots.add(s);
                    }
                }
            }
            check.shaped(id, source, result, width, pattern.size(), slots);
        } else if (type.equals("minecraft:crafting_shapeless") && obj.get("ingredients") instanceof List<?> ingredients) {
            List<Slot> slots = new ArrayList<>();
            for (Object o : ingredients) {
                Slot s = slot(o);
                if (s == null) return;
                slots.add(s);
            }
            check.shapeless(id, source, result, slots);
        }
    }

    /** "ns:item", "#ns:tag", {"item": ...}, {"tag": ...} or a list of those. Null for custom ingredients. */
    private Slot slot(Object ingredient) {
        Set<String> items = new LinkedHashSet<>();
        List<String> labels = new ArrayList<>();
        if (!add(ingredient, items, labels)) return null;
        return new Slot(items, String.join(" or ", labels));
    }

    private boolean add(Object ingredient, Set<String> items, List<String> labels) {
        if (ingredient instanceof String s) {
            if (s.startsWith("#")) items.addAll(tagItems(s.substring(1)));
            else items.add(s);
            labels.add(s);
            return true;
        }
        if (ingredient instanceof Map<?, ?> m) {
            if (m.get("type") != null) return false; // NeoForge/Forge custom ingredient: can't tell what fits
            if (m.get("item") instanceof String s) return add(s, items, labels);
            if (m.get("tag") instanceof String s) return add("#" + s, items, labels);
            return false;
        }
        if (ingredient instanceof List<?> list) {
            for (Object o : list) if (!add(o, items, labels)) return false;
            return !list.isEmpty();
        }
        return false;
    }

    /** Every item in a tag, following tags inside tags. */
    public Set<String> tagItems(String tag) {
        Set<String> done = resolved.get(tag);
        if (done != null) return done;
        Set<String> out = new LinkedHashSet<>();
        expand(tag, out, new HashSet<>());
        resolved.put(tag, out);
        return out;
    }

    private void expand(String tag, Set<String> out, Set<String> visiting) {
        if (!visiting.add(tag)) return;
        for (String e : tagEntries.getOrDefault(tag, Set.of())) {
            if (e.startsWith("#")) expand(e.substring(1), out, visiting);
            else out.add(e);
        }
    }

    private static String result(Object result) {
        if (result instanceof String s) return s;
        if (result instanceof Map<?, ?> m) {
            if (m.get("id") instanceof String s) return s;
            if (m.get("item") instanceof String s) return s;
        }
        return null;
    }

    /** The crafting table ignores empty rows and columns around a pattern. */
    private static List<String> trim(List<String> rows) {
        int width = rows.stream().mapToInt(String::length).max().orElse(0);
        List<String> padded = rows.stream().map(r -> r + " ".repeat(width - r.length())).toList();
        int top = 0, bottom = padded.size() - 1, left = width, right = -1;
        while (top <= bottom && padded.get(top).isBlank()) top++;
        while (bottom >= top && padded.get(bottom).isBlank()) bottom--;
        for (int y = top; y <= bottom; y++) {
            String r = padded.get(y);
            for (int x = 0; x < width; x++) {
                if (r.charAt(x) != ' ') {
                    left = Math.min(left, x);
                    right = Math.max(right, x);
                }
            }
        }
        if (top > bottom || right < 0) return List.of();
        List<String> out = new ArrayList<>();
        for (int y = top; y <= bottom; y++) out.add(padded.get(y).substring(left, right + 1));
        return out;
    }
}
