package com.guiltypotato.checkengine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guiltypotato.checkengine.core.data.DataScanner;
import com.guiltypotato.checkengine.core.data.RecipeCheck;
import com.guiltypotato.checkengine.core.data.RecipeCheck.Slot;
import com.guiltypotato.checkengine.core.scan.Finding;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RecipeCheckTest {
    private final RecipeCheck check = new RecipeCheck(Map.of("create:copper_gear", "Copper Gear")::get);

    private static Slot item(String id) {
        return RecipeCheck.slot(id, List.of(id));
    }

    private static Slot tag(String tag, String... items) {
        return RecipeCheck.slot("#" + tag, List.of(items));
    }

    private static final Slot E = Slot.EMPTY;

    private List<Finding> byCode(String code) {
        return check.findings().stream().filter(f -> f.code().equals(code)).toList();
    }

    @Test
    void sameShapedGridDifferentResultsClash() {
        Slot copper = tag("c:ingots/copper", "minecraft:copper_ingot", "thermal:copper_ingot");
        check.shaped("create:copper_gear", "mod/create", "create:copper_gear", 3, 3,
                List.of(E, copper, E, copper, item("minecraft:stick"), copper, E, copper, E));
        check.shaped("thermal:gear_copper", "mod/thermal", "thermal:copper_gear", 3, 3,
                List.of(E, item("minecraft:copper_ingot"), E, item("minecraft:copper_ingot"), item("minecraft:stick"),
                        item("minecraft:copper_ingot"), E, item("minecraft:copper_ingot"), E));
        List<Finding> f = byCode(Finding.RECIPE_CLASH);
        assertEquals(1, f.size());
        assertEquals("1 recipe clash", f.get(0).title());
        assertTrue(f.get(0).detail().contains("Copper Gear (create:copper_gear) (create:copper_gear)"), f.get(0).detail());
        assertTrue(f.get(0).detail().contains("thermal:copper_gear (thermal:gear_copper)"), f.get(0).detail());
    }

    @Test
    void mirroredGridClashesButDifferentItemsDont() {
        check.shaped("a:axe", "mod/a", "a:axe", 2, 3,
                List.of(item("x:gem"), item("x:gem"), item("x:gem"), item("minecraft:stick"), E, item("minecraft:stick")));
        check.shaped("b:axe", "mod/b", "b:axe", 2, 3,
                List.of(item("x:gem"), item("x:gem"), item("minecraft:stick"), item("x:gem"), item("minecraft:stick"), E));
        check.shaped("c:axe", "mod/c", "c:axe", 2, 3,
                List.of(item("x:ruby"), item("x:ruby"), item("x:ruby"), item("minecraft:stick"), E, item("minecraft:stick")));
        List<Finding> f = byCode(Finding.RECIPE_CLASH);
        assertEquals(1, f.size(), String.valueOf(f));
        assertTrue(f.get(0).detail().contains("a:axe") && f.get(0).detail().contains("b:axe"));
        assertTrue(!f.get(0).detail().contains("c:axe"));
    }

    @Test
    void shapelessClashesInAnyOrderAndWithShaped() {
        check.shapeless("a:dust", "mod/a", "a:dust", List.of(item("x:ore"), tag("c:tools/hammers", "a:hammer", "b:hammer")));
        check.shapeless("b:dust", "mod/b", "b:dust", List.of(item("b:hammer"), item("x:ore")));
        check.shaped("c:dust", "mod/c", "c:dust", 1, 2, List.of(item("x:ore"), item("a:hammer")));
        // Same result as a:dust: a harmless duplicate. b:hammer isn't a:hammer, so b and c don't clash.
        check.shapeless("d:dust", "mod/d", "a:dust", List.of(item("x:ore"), item("a:hammer")));
        List<Finding> f = byCode(Finding.RECIPE_CLASH);
        assertEquals("3 recipe clashes", f.get(0).title(), f.get(0).detail());
    }

    @Test
    void sameResultAndDifferentSizesDontClash() {
        check.shaped("a:x", "mod/a", "a:x", 1, 1, List.of(item("x:gem")));
        check.shaped("b:x", "mod/b", "b:x", 2, 2, List.of(item("x:gem"), E, E, E));
        check.shaped("c:x", "mod/c", "a:x", 1, 1, List.of(item("x:gem")));
        assertTrue(byCode(Finding.RECIPE_CLASH).isEmpty());
    }

    @Test
    void emptyTagMakesARecipeUncraftable() {
        check.shaped("mypack:tin_gear", "kubejs", "mypack:tin_gear", 1, 1, List.of(tag("c:ingots/tin")));
        check.shaped("mypack:other", "kubejs", "mypack:other", 1, 1, List.of(item("mypack:dead")));
        List<Finding> f = byCode(Finding.DEAD_RECIPE);
        assertEquals(1, f.size());
        assertTrue(f.get(0).detail().contains("mypack:tin_gear, from kubejs (makes mypack:tin_gear): #c:ingots/tin"), f.get(0).detail());
    }

    @Test
    void questItemNothingGives() {
        check.questNeeds("chapters/steel.snbt", "mypack:steel_plate");
        check.questNeeds("chapters/steel.snbt", "mypack:steel_ingot");
        check.questNeeds("chapters/steel.snbt", "minecraft:honeycomb");
        check.made("mypack:steel_ingot");
        List<Finding> f = byCode(Finding.UNOBTAINABLE_ITEM);
        assertEquals(1, f.size());
        assertEquals("1 quest item nothing gives", f.get(0).title());
        assertTrue(f.get(0).detail().contains("mypack:steel_plate: chapters/steel.snbt"), f.get(0).detail());
        assertTrue(!f.get(0).detail().contains("honeycomb"));
    }

    @Test
    void ingredientNothingGivesIsInfo() {
        check.shaped("mypack:engine", "kubejs", "mypack:engine", 1, 2, List.of(item("mypack:piston"), item("minecraft:iron_ingot")));
        check.shaped("mypack:car", "kubejs", "mypack:car", 1, 1, List.of(tag("c:engines", "mypack:engine", "other:engine")));
        List<Finding> f = byCode(Finding.UNOBTAINABLE_ITEM);
        assertEquals(1, f.size());
        assertEquals(Finding.Severity.INFO, f.get(0).severity());
        assertTrue(f.get(0).detail().contains("mypack:piston: needed by mypack:engine"), f.get(0).detail());
    }

    @Test
    void dataScannerReadsQuestTasksRewardsAndRecipeOutputs() {
        DataScanner scanner = new DataScanner(Set.of("minecraft", "mypack", "create")::contains);
        scanner.questFile("chapters/steel.snbt", """
                {
                	quests: [{
                		icon: "mypack:icon_only"
                		tasks: [{
                			id: "1A2B3C"
                			item: { count: 1, id: "mypack:steel_plate" }
                			type: "item"
                		}, {
                			item: "mypack:old_style"
                			type: "item"
                		}]
                		rewards: [{
                			item: { id: "mypack:reward_gem" }
                			type: "item"
                		}]
                	}]
                }
                """);
        scanner.recipe("create:crushing/ore", "mod/create", """
                {"type": "create:crushing", "ingredients": [{"item": "minecraft:stone"}],
                 "results": [{"id": "create:crushed_iron"}, {"id": "create:experience_nugget", "chance": 0.75}]}
                """);
        assertEquals(Set.of("mypack:steel_plate", "mypack:old_style"), scanner.questTaskItems().keySet());
        assertTrue(scanner.gives().containsAll(Set.of("mypack:reward_gem", "create:crushed_iron", "create:experience_nugget")),
                String.valueOf(scanner.gives()));
        assertTrue(!scanner.gives().contains("minecraft:stone"));
    }

    @Test
    void recipeFilesFindClashesThroughTags() {
        com.guiltypotato.checkengine.core.data.RecipeFiles files = new com.guiltypotato.checkengine.core.data.RecipeFiles();
        files.tag("c:ingots/copper", """
                {"values": ["minecraft:copper_ingot", "#c:ingots/copper_extra", {"id": "thermal:copper_ingot", "required": false}]}
                """);
        files.tag("c:ingots/copper_extra", """
                {"values": ["other:copper_ingot"]}
                """);
        assertEquals(Set.of("minecraft:copper_ingot", "other:copper_ingot", "thermal:copper_ingot"),
                files.tagItems("c:ingots/copper"));
        files.recipe(check, "create:copper_gear", "mod/create", """
                {"type": "minecraft:crafting_shaped", "pattern": ["   ", " C ", "CSC", " C "],
                 "key": {"C": {"tag": "c:ingots/copper"}, "S": {"item": "minecraft:stick"}},
                 "result": {"id": "create:copper_gear"}}
                """);
        files.recipe(check, "thermal:gear_copper", "mod/thermal", """
                {"type": "minecraft:crafting_shaped", "pattern": [" C", "CSC", " C "],
                 "key": {"C": "other:copper_ingot", "S": "minecraft:stick"},
                 "result": {"id": "thermal:copper_gear"}}
                """);
        files.recipe(check, "mypack:tin", "file/pack.zip", """
                {"type": "minecraft:crafting_shapeless", "ingredients": ["#c:ingots/tin"], "result": {"id": "mypack:tin_dust"}}
                """);
        files.recipe(check, "mypack:custom", "file/pack.zip", """
                {"type": "minecraft:crafting_shapeless", "ingredients": [{"type": "neoforge:components", "items": "minecraft:potion"}],
                 "result": {"id": "mypack:x"}}
                """);
        assertEquals(3, check.checked());
        // The same 3x3 shape once the empty top row is trimmed; short rows count as padded with spaces.
        assertEquals(1, byCode(Finding.RECIPE_CLASH).size(), String.valueOf(check.findings()));
        assertTrue(byCode(Finding.DEAD_RECIPE).get(0).detail().contains("#c:ingots/tin"));
    }
}
