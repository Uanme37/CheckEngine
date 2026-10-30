package com.guiltypotato.checkengine.core.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The reusable UI pieces: sortable, searchable list and button placement. */
class UiPartsTest {
    record Row(String name, int size) {}

    @Test
    void sortsAndSearches() {
        SortableList<Row> list = new SortableList<>(List.of(new Row("Create", 30), new Row("Jade", 2),
                new Row("Create Addition", 5)), Row::name)
                .sortBy("size", Comparator.comparingInt(Row::size).reversed())
                .sortBy("name", Comparator.comparing(Row::name));
        assertEquals("size", list.sort());
        assertEquals(List.of("Create", "Create Addition", "Jade"), list.view().stream().map(Row::name).toList());
        list.sort("name");
        list.query("  create ");
        assertEquals(List.of("Create", "Create Addition"), list.view().stream().map(Row::name).toList());
        list.query("create add");
        assertEquals(List.of("Create Addition"), list.view().stream().map(Row::name).toList());
        list.sort("nope");
        assertEquals("name", list.sort());
    }

    @Test
    void placesBesideTheAnchorWithoutCoveringOtherButtons() {
        Placement.Box access = new Placement.Box(264, 192, 20, 20);
        Placement.Box lang = new Placement.Box(136, 192, 20, 20);
        assertEquals(new Placement.Box(288, 192, 20, 20),
                Placement.beside(access, List.of(access, lang), 20, 0, 10, 4, 427));
        // another mod's button already sits there: move one further
        Placement.Box other = new Placement.Box(288, 192, 20, 20);
        assertEquals(new Placement.Box(312, 192, 20, 20),
                Placement.beside(access, List.of(access, lang, other), 20, 0, 10, 4, 427));
        // the caption needs room too: a widget just under the spot pushes it along
        Placement.Box under = new Placement.Box(290, 214, 40, 10);
        assertEquals(new Placement.Box(336, 192, 20, 20),
                Placement.beside(access, List.of(access, lang, under), 20, 0, 10, 4, 427));
        // no room on the right: left of the anchor
        assertEquals(new Placement.Box(240, 192, 20, 20),
                Placement.beside(access, List.of(access), 20, 0, 10, 4, 300));
        // a caption on the right needs its room too: too close to the edge on the right, and on the left it must
        // not cover the anchor either
        assertEquals(new Placement.Box(192, 192, 20, 20),
                Placement.beside(access, List.of(access), 20, 30, 0, 4, 330));
        // corners: past another mod's corner button, the other way from the right, or an exact spot
        Placement.Box goggles = new Placement.Box(2, 218, 20, 20);
        assertEquals(new Placement.Box(26, 218, 20, 20),
                Placement.corner("bottom_left", List.of(goggles), 20, 30, 4, 427, 240));
        assertEquals(new Placement.Box(375, 218, 20, 20),
                Placement.corner("bottom_right", List.of(goggles), 20, 30, 4, 427, 240));
        assertEquals(new Placement.Box(2, 2, 20, 20), Placement.corner("top_left", List.of(goggles), 20, 30, 4, 427, 240));
        assertEquals(new Placement.Box(100, 50, 20, 20), Placement.corner("100, 50", List.of(), 20, 30, 4, 427, 240));
        assertEquals(new Placement.Box(377, 220, 20, 20), Placement.corner("900,900", List.of(), 20, 30, 4, 427, 240),
                "kept on the screen");
        assertNull(Placement.corner("middle", List.of(), 20, 30, 4, 427, 240));
        // a bigger corner button a little off the edge: same row (centred on it), a gap after its right edge
        Placement.Box big = new Placement.Box(3, 215, 24, 24);
        assertEquals(new Placement.Box(31, 217, 20, 20),
                Placement.corner("bottom_left", List.of(big), 20, 30, 4, 427, 240));
        assertNull(Placement.beside(new Placement.Box(0, 0, 20, 20), List.of(new Placement.Box(0, 0, 40, 40)), 20, 0,
                10, 4, 30));
    }
}
