package com.guiltypotato.checkengine;

import com.guiltypotato.checkengine.core.data.RecipeCheck;
import com.guiltypotato.checkengine.core.data.RecipeCheck.Slot;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;

/** Hands the recipes the game actually loaded (after KubeJS, CraftTweaker and Almost Unified) to {@link RecipeCheck}. Server thread only. */
final class RecipeReader {
    private RecipeReader() {}

    /** @param sources recipe id to the datapack or mod its file came from; recipes made by scripts have none */
    static RecipeCheck read(MinecraftServer server, Map<String, String> sources) {
        RecipeCheck check = new RecipeCheck(DataScan::itemName, DataScan::modName);
        for (RecipeHolder<?> holder : server.getRecipeManager().getRecipes()) {
            String id = holder.id().identifier().toString();
            Recipe<?> recipe = holder.value();
            String result;
            try {
                result = result(recipe);
            } catch (RuntimeException e) {
                continue; // a mod's recipe that can't say what it makes
            }
            if (result == null) continue;
            String source = sources.getOrDefault(id, "a script");
            try {
                if (recipe instanceof ShapedRecipe shaped) {
                    check.shaped(id, source, result, shaped.getWidth(), shaped.getHeight(), slots(shaped.getIngredients()));
                } else if (recipe instanceof ShapelessRecipe shapeless && !shapeless.placementInfo().isImpossibleToPlace()) {
                    check.shapeless(id, source, result, slots(shapeless.placementInfo().ingredients().stream()
                            .map(Optional::of).toList()));
                } else {
                    check.made(result);
                }
            } catch (RuntimeException e) {
                check.made(result); // an odd ingredient: still counts as a way to get the item
            }
        }
        return check;
    }

    /** What the recipe makes, from what the recipe book would show; null when it isn't one plain item. */
    private static String result(Recipe<?> recipe) {
        for (RecipeDisplay display : recipe.display()) {
            SlotDisplay r = display.result();
            if (r instanceof SlotDisplay.ItemStackSlotDisplay stack) return key(stack.stack());
            if (r instanceof SlotDisplay.ItemSlotDisplay item) return key(item.item());
        }
        return null;
    }

    private static List<Slot> slots(List<Optional<Ingredient>> ingredients) {
        List<Slot> out = new ArrayList<>();
        for (Optional<Ingredient> o : ingredients) {
            if (o.isEmpty() || o.get().isEmpty()) {
                out.add(Slot.EMPTY);
                continue;
            }
            Ingredient ing = o.get();
            out.add(RecipeCheck.slot(label(ing), ing.items().map(RecipeReader::key).toList()));
        }
        return out;
    }

    /** What the recipe asks for, as a pack maker wrote it: "#c:ingots/tin" or "create:cogwheel". */
    private static String label(Ingredient ing) {
        if (ing.isCustom()) return RecipeCheck.SPECIAL;
        HolderSet<Item> values = ing.getValues();
        Optional<String> tag = values.unwrapKey().map(k -> "#" + k.location());
        if (tag.isPresent()) return tag.get();
        List<String> parts = values.stream().map(RecipeReader::key).toList();
        return parts.isEmpty() ? "an ingredient" : String.join(" or ", parts);
    }

    private static String key(Holder<Item> item) {
        return BuiltInRegistries.ITEM.getKey(item.value()).toString();
    }

    /** "create:cogwheel", plus a marker when only a special version (enchanted, a potion) fits. */
    private static String key(ItemStackTemplate stack) {
        String id = key(stack.item());
        return stack.components().isEmpty() ? id : id + "{" + Integer.toHexString(stack.components().hashCode()) + "}";
    }
}
