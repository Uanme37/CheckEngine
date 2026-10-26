package com.guiltypotato.checkengine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guiltypotato.checkengine.core.data.DataScanner;
import com.guiltypotato.checkengine.core.scan.Finding;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DataScannerTest {
    private final DataScanner scanner = new DataScanner(Set.of("minecraft", "create", "ftbquests")::contains);

    private List<Finding> byCode(String code) {
        return scanner.findings().stream().filter(f -> f.code().equals(code)).toList();
    }

    @Test
    void recipeWithItemFromRemovedMod() {
        scanner.recipe("mypack:gear", "file/mypack.zip", """
                {"type": "minecraft:crafting_shaped", "pattern": ["#"],
                 "key": {"#": {"item": "alexscaves:scarlet_neodymium_ingot"}},
                 "result": {"id": "create:cogwheel", "count": 1}}
                """);
        List<Finding> f = byCode(Finding.ITEMS_FROM_MISSING_MOD);
        assertEquals(1, f.size());
        assertEquals(Finding.Severity.WARNING, f.get(0).severity());
        assertTrue(f.get(0).title().contains("alexscaves"));
        assertTrue(f.get(0).detail().contains("file/mypack.zip"));
        assertTrue(f.get(0).detail().startsWith("1 recipe uses items"), f.get(0).detail());
    }

    @Test
    void plainStringIngredientsFrom1212On() {
        // 1.21.2+ and 26.x write ingredients as plain strings; tags (#) and installed mods are fine.
        scanner.recipe("mypack:a", "file/mypack.zip", """
                {"type": "minecraft:crafting_shapeless", "ingredients": ["fakemod:mystery_gear", "#c:ingots", "create:cogwheel"],
                 "result": {"id": "minecraft:stone"}}
                """);
        scanner.recipe("mypack:b", "file/mypack.zip", """
                {"type": "minecraft:crafting_shaped", "pattern": ["#"], "key": {"#": ["othermod:bolt", "minecraft:stick"]},
                 "result": {"id": "minecraft:stone"}}
                """);
        scanner.recipe("mypack:c", "file/mypack.zip", """
                {"type": "minecraft:smelting", "ingredient": "thirdmod:ore", "result": {"id": "minecraft:iron_ingot"}}
                """);
        scanner.recipe("mypack:d", "file/mypack.zip", """
                {"type": "minecraft:smelting", "ingredient": {"tag": "c:ores/tin"}, "base": "forge:ingots/tin",
                 "result": {"id": "minecraft:iron_ingot"}}
                """);
        List<String> titles = byCode(Finding.ITEMS_FROM_MISSING_MOD).stream().map(Finding::title).toList();
        assertEquals(List.of("Items from a missing mod: fakemod", "Items from a missing mod: othermod",
                "Items from a missing mod: thirdmod"), titles);
    }

    @Test
    void recipeTypeFromRemovedMod() {
        scanner.recipe("kubejs:mix", "kubejs", """
                {"type": "mekanism:crushing", "input": {"item": "minecraft:stone"}, "output": {"id": "minecraft:gravel"}}
                """);
        assertTrue(byCode(Finding.ITEMS_FROM_MISSING_MOD).get(0).title().contains("mekanism"));
    }

    @Test
    void conditionalFilesAreSkipped() {
        scanner.recipe("create:compat/ae2", "mod:create", """
                {"neoforge:conditions": [{"type": "neoforge:mod_loaded", "modid": "ae2"}],
                 "type": "create:crushing", "ingredients": [{"item": "ae2:certus_quartz_crystal"}]}
                """);
        assertEquals(List.of(), scanner.findings());
    }

    @Test
    void lootTablesKeepNormalDropConditions() {
        // Plain "conditions" in a loot table are drop rules, not load conditions: still check the item.
        scanner.lootTable("minecraft:entities/zombie", "file/loot.zip", """
                {"pools": [{"rolls": 1, "entries": [
                  {"type": "minecraft:item", "name": "artifacts:crystal_heart",
                   "conditions": [{"condition": "minecraft:killed_by_player"}]},
                  {"type": "minecraft:item", "name": "minecraft:rotten_flesh"}]}]}
                """);
        assertEquals(1, byCode(Finding.ITEMS_FROM_MISSING_MOD).size());
    }

    @Test
    void leftoversInsideModsAreOnlyNotes() {
        scanner.lootTable("create:chests/compat", "mod:create", """
                {"pools": [{"entries": [{"type": "minecraft:item", "name": "farmersdelight:tomato"}]}]}
                """);
        // 1.21.1 names mod data packs "mod/<id>" (a real pack: Farming for Blockheads market recipes).
        scanner.recipe("farmingforblockheads:market/byg/aspen_sapling", "mod/farmingforblockheads",
                "{\"type\":\"farmingforblockheads:market\",\"result\":{\"item\":\"byg:aspen_sapling\"}}");
        scanner.recipe("farmingforblockheads:market/quark/blue_blossom_sapling", "mod/farmingforblockheads",
                "{\"type\":\"farmingforblockheads:market\",\"result\":{\"item\":\"quark:blue_blossom_sapling\"}}");
        List<Finding> found = byCode(Finding.ITEMS_FROM_MISSING_MOD);
        assertEquals(1, found.size());
        assertEquals(Finding.Severity.INFO, found.get(0).severity());
        assertTrue(found.get(0).detail().contains("byg (1)"), found.get(0).detail());
    }

    @Test
    void datapackExtrasForAMissingModAreOnlyNotes() {
        // A compat datapack ships recipes under the missing mod's own name (a real pack: a Rubberworks compat zip).
        scanner.recipe("createdieselgenerators:crafting/pumpjack_head", "file/compat.zip",
                "{\"type\":\"minecraft:crafting_shapeless\",\"ingredients\":[{\"item\":\"minecraft:iron_ingot\"}],"
                        + "\"result\":{\"id\":\"createdieselgenerators:pumpjack_head\"}}");
        // Same kind of missing item, but a recipe of the pack's own needs it: that one still warns.
        scanner.recipe("mypack:engine", "file/mypack.zip",
                "{\"type\":\"minecraft:crafting_shapeless\",\"ingredients\":[{\"item\":\"powergrid:wire\"}],"
                        + "\"result\":{\"id\":\"create:cogwheel\"}}");
        List<Finding> found = byCode(Finding.ITEMS_FROM_MISSING_MOD);
        assertEquals(2, found.size(), found.toString());
        Finding warning = found.stream().filter(f -> f.severity() == Finding.Severity.WARNING).findFirst().orElseThrow();
        assertTrue(warning.title().contains("powergrid"), warning.title());
        Finding note = found.stream().filter(f -> f.severity() == Finding.Severity.INFO).findFirst().orElseThrow();
        assertEquals("Datapack extras for mods you don't have", note.title());
        assertTrue(note.detail().startsWith("compat.zip has 1 recipe for a mod you don't have"
                + " (createdieselgenerators)"), note.detail());
    }

    @Test
    void oneNotePerDatapack() {
        for (String r : List.of("offroad:crafting/wheel", "powergrid:crafting/wire", "powergrid:crafting/pole")) {
            scanner.recipe(r, "file/CreateRubberworks_Compat_v6.zip",
                    "{\"type\":\"minecraft:crafting_shapeless\",\"ingredients\":[{\"item\":\"minecraft:stick\"}],"
                            + "\"result\":{\"id\":\"" + r.replace("crafting/", "") + "\"}}");
        }
        List<Finding> found = byCode(Finding.ITEMS_FROM_MISSING_MOD);
        assertEquals(1, found.size(), found.toString());
        assertEquals(Finding.Severity.INFO, found.get(0).severity());
        assertTrue(found.get(0).detail().startsWith("CreateRubberworks_Compat_v6.zip has 3 recipes"
                + " for 2 mods you don't have (offroad, powergrid)"), found.get(0).detail());
    }

    @Test
    void forgeNamesModDataByJar() {
        // Forge 1.20.1 gives the jar file name as the source (a real Forge 1.20.1 pack: Lightman's Currency).
        scanner.recipe("lightmanscurrency:auction_stand/biomesoplenty/fir", "lightmanscurrency-1.20.1-2.3.0.5.jar",
                "{\"type\":\"minecraft:crafting_shapeless\",\"ingredients\":[{\"item\":\"biomesoplenty:fir_log\"}],"
                        + "\"result\":{\"item\":\"lightmanscurrency:auction_stand\"}}");
        List<Finding> found = byCode(Finding.ITEMS_FROM_MISSING_MOD);
        assertEquals(1, found.size());
        assertEquals(Finding.Severity.INFO, found.get(0).severity());
    }

    @Test
    void questsFromBigPackShape() {
        scanner.questFile("productive_bees.snbt", """
                {
                	icon: {
                		components: {
                			"ftbquests:missing_item": "artifacts:crystal_heart"
                		}
                		id: "ftbquests:missing_item"
                	}
                	id: "2E51F09F6D9E5EF8"
                	image: "atm:textures/questpics/chap3/creative_items.png"
                	tasks: [{
                		item: { count: 1, id: "create:cogwheel" }
                		type: "item"
                	}, {
                		item: "mekanism:dust_obsidian"
                		type: "item"
                	}]
                }
                """);
        List<Finding> broken = byCode(Finding.BROKEN_QUEST_ITEM);
        assertEquals(1, broken.size());
        assertTrue(broken.get(0).detail().contains("productive_bees.snbt: artifacts:crystal_heart"));
        // hex quest ids, the texture path and the installed create item are all fine
        List<Finding> missing = byCode(Finding.ITEMS_FROM_MISSING_MOD);
        assertEquals(1, missing.size(), missing.toString());
        assertTrue(missing.get(0).title().contains("mekanism"));
        assertEquals(1, scanner.checked(DataScanner.Kind.QUEST));
    }

    @Test
    void badJsonIsANote() {
        scanner.recipe("mypack:broken", "file/mypack.zip", "{ not json");
        assertEquals(Finding.Severity.INFO, byCode(Finding.UNREADABLE_METADATA).get(0).severity());
    }
}
