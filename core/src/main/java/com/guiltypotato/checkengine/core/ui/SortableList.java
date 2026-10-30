package com.guiltypotato.checkengine.core.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * A list of rows that can be sorted a few named ways and searched by text. No game code and no Check Engine
 * logic, so it can move into a shared UI library.
 */
public final class SortableList<T> {
    private final List<T> rows;
    private final Function<T, String> searchText;
    private final Map<String, Comparator<T>> sorts = new LinkedHashMap<>();
    private String sort;
    private String query = "";

    public SortableList(List<T> rows, Function<T, String> searchText) {
        this.rows = List.copyOf(rows);
        this.searchText = searchText;
    }

    /** Adds a way to sort; the first one added is the starting sort. */
    public SortableList<T> sortBy(String name, Comparator<T> order) {
        sorts.put(name, order);
        if (sort == null) sort = name;
        return this;
    }

    public List<String> sortNames() {
        return List.copyOf(sorts.keySet());
    }

    public String sort() {
        return sort;
    }

    public void sort(String name) {
        if (sorts.containsKey(name)) sort = name;
    }

    public String query() {
        return query;
    }

    public void query(String text) {
        query = text == null ? "" : text.strip();
    }

    /** The rows that match the search (every word, any case), in the chosen order. */
    public List<T> view() {
        String[] words = query.toLowerCase(Locale.ROOT).split("\s+");
        List<T> out = new ArrayList<>();
        for (T row : rows) {
            String text = searchText.apply(row).toLowerCase(Locale.ROOT);
            boolean all = true;
            for (String w : words) if (!w.isEmpty() && !text.contains(w)) all = false;
            if (all) out.add(row);
        }
        if (sort != null) out.sort(sorts.get(sort));
        return out;
    }

    public int size() {
        return rows.size();
    }
}
