package com.guiltypotato.checkengine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guiltypotato.checkengine.core.data.DataScanner;
import com.guiltypotato.checkengine.core.data.RecipeCheck;
import com.guiltypotato.checkengine.core.data.RecipeCheck.Slot;
import com.guiltypotato.checkengine.core.scan.Finding;
import java.time.Duration;
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
                List.of(E, copper, E, copper, item("minecraft:stick"), copper, E, copper, E));
        List<Finding> f = byCode(Finding.RECIPE_CLASH);
        assertEquals(1, f.size());
        assertEquals("1 recipe clash", f.get(0).title());
        assertEquals(Finding.Severity.WARNING, f.get(0).severity());
        assertTrue(f.get(0).detail().contains("- Copper Gear / thermal:copper_gear - from 4x any copper ingot"), f.get(0).detail());
        assertTrue(f.get(0).detail().contains("Copper Gear: create:copper_gear, thermal:gear_copper"), f.get(0).detail());
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
        check.shapeless("e:dust", "mod/e", "e:dust", List.of(tag("c:tools/hammers", "a:hammer", "b:hammer"), item("x:ore")));
        List<Finding> f = byCode(Finding.RECIPE_CLASH);
        // e and a take exactly the same items, and so do c and d (shaped vs shapeless); the other pairs only share some.
        assertEquals("2 recipe clashes", f.get(0).title(), f.get(0).detail());
        assertTrue(f.get(0).detail().contains("- a:dust / e:dust - from 1x x:ore + 1x any hammer"), f.get(0).detail());
        assertTrue(f.get(0).detail().contains("- a:dust / c:dust - from"), f.get(0).detail());
        assertTrue(f.get(1).title().endsWith(" some ingredients"), f.get(1).title());
        assertEquals(Finding.Severity.INFO, f.get(1).severity());
    }

    @Test
    void anyPlanksAgainstOnePlankIsOnlyAnOverlap() {
        // Crafting Table from any planks vs Aspen Crafting Table from aspen planks: both can still be crafted.
        Slot planks = tag("minecraft:planks", "minecraft:oak_planks", "aspen:aspen_planks");
        Slot aspen = item("aspen:aspen_planks");
        check.shaped("minecraft:crafting_table", "mod/minecraft", "minecraft:crafting_table", 2, 2, List.of(planks, planks, planks, planks));
        check.shaped("aspen:crafting_table", "mod/aspen", "aspen:crafting_table", 2, 2, List.of(aspen, aspen, aspen, aspen));
        List<Finding> f = byCode(Finding.RECIPE_CLASH);
        assertEquals(1, f.size(), String.valueOf(f));
        assertEquals(Finding.Severity.INFO, f.get(0).severity());
        assertEquals("1 group of recipes shares some ingredients", f.get(0).title());
    }

    @Test
    void specialIngredientsAreLeftOut() {
        // A mod's own ingredient (e.g. one item with set components) lists plain items, which would look the same.
        Slot log = RecipeCheck.slot(RecipeCheck.SPECIAL, List.of("x:log"));
        check.shapeless("atm:essence_a", "a script", "atm:essence_a", List.of(log));
        check.shapeless("atm:essence_b", "a script", "atm:essence_b", List.of(log));
        assertTrue(byCode(Finding.RECIPE_CLASH).isEmpty(), String.valueOf(check.findings()));
    }

    @Test
    void sameResultAndDifferentSizesDontClash() {
        check.shaped("a:x", "mod/a", "a:x", 1, 1, List.of(item("x:gem")));
        check.shaped("b:x", "mod/b", "b:x", 2, 2, List.of(item("x:gem"), E, E, E));
        check.shaped("c:x", "mod/c", "a:x", 1, 1, List.of(item("x:gem")));
        assertTrue(byCode(Finding.RECIPE_CLASH).isEmpty());
    }

    @Test
    void bigTagsInABigPackStayFast() {
        // A big pack: thousands of 3x3 recipes around one big tag (planks, ingots). Used to hang for minutes.
        String[] planks = new String[400];
        for (int i = 0; i < planks.length; i++) planks[i] = "wood:planks_" + i;
        Slot plank = tag("minecraft:planks", planks);
        for (int i = 0; i < 3000; i++) {
            check.shaped("m:r" + i, "mod/m", "m:out" + i, 3, 3,
                    List.of(plank, plank, plank, plank, item("m:core" + i), plank, plank, plank, plank));
        }
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> assertTrue(byCode(Finding.RECIPE_CLASH).isEmpty()));
    }

    @Test
    void emptyTagMakesARecipeUncraftable() {
        check.shaped("mypack:tin_gear", "kubejs", "mypack:tin_gear", 1, 1, List.of(tag("c:ingots/tin")));
        check.shaped("mypack:other", "kubejs", "mypack:other", 1, 1, List.of(item("mypack:dead")));
        List<Finding> f = byCode(Finding.DEAD_RECIPE);
        assertEquals(1, f.size());
        assertTrue(f.get(0).detail().contains("mypack:tin_gear (datapack kubejs): needs #c:ingots/tin, which has no items"), f.get(0).detail());
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
        assertEquals(Finding.Severity.INFO, f.get(0).severity());
        assertTrue(f.get(0).detail().contains("mypack:steel_plate: asked in steel"), f.get(0).detail());
        assertTrue(!f.get(0).detail().contains("honeycomb"));
    }

    @Test
    void ingredientNothingGivesIsInfo() {
        check.shaped("mypack:engine", "kubejs", "mypack:engine", 1, 2, List.of(item("mypack:piston"), item("minecraft:iron_ingot")));
        check.shaped("mypack:car", "kubejs", "mypack:car", 1, 1, List.of(tag("c:engines", "mypack:engine", "other:engine")));
        List<Finding> f = byCode(Finding.UNOBTAINABLE_ITEM);
        assertEquals(1, f.size());
        assertEquals(Finding.Severity.INFO, f.get(0).severity());
        assertTrue(f.get(0).detail().contains("mypack:piston: needed for mypack:engine"), f.get(0).detail());
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
                 "key": {"C": "#c:ingots/copper", "S": "minecraft:stick"},
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
        assertEquals("1 recipe clash", byCode(Finding.RECIPE_CLASH).get(0).title(), String.valueOf(check.findings()));
        assertTrue(byCode(Finding.DEAD_RECIPE).get(0).detail().contains("#c:ingots/tin"));
    }

    @Test
    void tagsInWords() {
        assertEquals("any steel ingot", RecipeCheck.tagName("c:ingots/steel"));
        assertEquals("any hammer", RecipeCheck.tagName("c:tools/hammers"));
        assertEquals("any planks", RecipeCheck.tagName("minecraft:planks"));
    }

    @Test
    void namesWithoutColourCodesOrUntranslatedKeys() {
        assertEquals("Chaos Component", RecipeCheck.cleanName("§cChaos Component"));
        assertEquals(null, RecipeCheck.cleanName("item.mmorpg.buff_potion.common_arcane"));
        assertEquals(null, RecipeCheck.cleanName("Vial of %s (%s)"));
        assertEquals("Copper Gear", RecipeCheck.cleanName("Copper Gear"));
    }
}
