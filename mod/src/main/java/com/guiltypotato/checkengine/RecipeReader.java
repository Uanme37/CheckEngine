package com.guiltypotato.checkengine;

import com.guiltypotato.checkengine.core.data.RecipeCheck;
import com.guiltypotato.checkengine.core.data.RecipeCheck.Slot;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;

/** Hands the recipes the game actually loaded (after KubeJS and CraftTweaker) to {@link RecipeCheck}. Server thread only. */
final class RecipeReader {
    private RecipeReader() {}

    /** @param sources recipe id to the datapack or mod its file came from; recipes made by scripts have none */
    static RecipeCheck read(MinecraftServer server, Map<String, String> sources) {
        RecipeCheck check = new RecipeCheck(RecipeReader::name);
        RegistryAccess access = server.registryAccess();
        for (RecipeHolder<?> holder : server.getRecipeManager().getRecipes()) {
            String id = holder.id().toString();
            Recipe<?> recipe = holder.value();
            String result;
            try {
                result = key(recipe.getResultItem(access));
            } catch (RuntimeException e) {
                continue; // a mod's recipe that can't say what it makes
            }
            String source = sources.getOrDefault(id, "a script");
            if (recipe instanceof ShapedRecipe shaped) {
                check.shaped(id, source, result, shaped.getWidth(), shaped.getHeight(), slots(shaped.getIngredients()));
            } else if (recipe instanceof ShapelessRecipe) {
                check.shapeless(id, source, result, slots(recipe.getIngredients()));
            } else {
                check.made(result);
            }
        }
        return check;
    }

    private static List<Slot> slots(List<Ingredient> ingredients) {
        List<Slot> out = new ArrayList<>();
        for (Ingredient ing : ingredients) {
            if (ing.isEmpty()) {
                out.add(Slot.EMPTY);
                continue;
            }
            List<String> items = ing.hasNoItems() ? List.of()
                    : Arrays.stream(ing.getItems()).filter(s -> !s.isEmpty()).map(RecipeReader::key).toList();
            out.add(RecipeCheck.slot(label(ing), items));
        }
        return out;
    }

    /** What the recipe asks for, as a pack maker wrote it: "#c:ingots/tin" or "create:cogwheel". */
    private static String label(Ingredient ing) {
        if (ing.isCustom()) return RecipeCheck.SPECIAL;
        List<String> parts = new ArrayList<>();
        for (Ingredient.Value v : ing.getValues()) {
            if (v instanceof Ingredient.TagValue tag) parts.add("#" + tag.tag().location());
            else if (v instanceof Ingredient.ItemValue item) parts.add(key(item.item()));
        }
        return parts.isEmpty() ? "an ingredient" : String.join(" or ", parts);
    }

    /** "create:cogwheel", plus a marker when only a special version (enchanted, a potion) fits. */
    private static String key(ItemStack stack) {
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        return stack.getComponentsPatch().isEmpty() ? id : id + "{" + Integer.toHexString(stack.getComponentsPatch().hashCode()) + "}";
    }

    private static String name(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null || !BuiltInRegistries.ITEM.containsKey(rl)) return id;
        return BuiltInRegistries.ITEM.get(rl).getDescription().getString();
    }
}
