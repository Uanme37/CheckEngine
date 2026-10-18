package com.guiltypotato.checkengine.forge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
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
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;

/** Hands the recipes the game actually loaded (after KubeJS and CraftTweaker) to {@link RecipeCheck}. Server thread only. */
final class RecipeReader {
    private RecipeReader() {}

    /** @param sources recipe id to the datapack or mod its file came from; recipes made by scripts have none */
    static RecipeCheck read(MinecraftServer server, Map<String, String> sources) {
        RecipeCheck check = new RecipeCheck(RecipeReader::name);
        RegistryAccess access = server.registryAccess();
        for (Recipe<?> recipe : server.getRecipeManager().getRecipes()) {
            String id = recipe.getId().toString();
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
            String label = label(ing);
            if (ing.isEmpty() && label.isEmpty()) {
                out.add(Slot.EMPTY);
                continue;
            }
            List<String> items = Arrays.stream(ing.getItems()).filter(s -> !s.isEmpty() && !emptyTagMarker(s))
                    .map(RecipeReader::key).toList();
            out.add(RecipeCheck.slot(label.isEmpty() ? "an ingredient" : label, items));
        }
        return out;
    }

    /** Forge shows an empty tag as a barrier named "Empty Tag: ...". */
    private static boolean emptyTagMarker(ItemStack stack) {
        return stack.getItem() == net.minecraft.world.item.Items.BARRIER && stack.getHoverName().getString().startsWith("Empty Tag: ");
    }

    /** What the recipe asks for, as a pack maker wrote it: "#forge:ingots/tin" or "create:cogwheel"; empty for nothing. */
    private static String label(Ingredient ing) {
        try {
            return label(ing.toJson());
        } catch (RuntimeException e) {
            return "a special ingredient";
        }
    }

    private static String label(JsonElement json) {
        if (json instanceof JsonObject obj) {
            if (obj.has("tag")) return "#" + obj.get("tag").getAsString();
            if (obj.has("item")) return obj.get("item").getAsString();
            return obj.size() == 0 ? "" : "a special ingredient";
        }
        if (json instanceof JsonArray arr) {
            List<String> parts = new ArrayList<>();
            for (JsonElement e : arr) {
                String l = label(e);
                if (!l.isEmpty()) parts.add(l);
            }
            return String.join(" or ", parts);
        }
        return "";
    }

    /** "create:cogwheel", plus a marker when only a special version (enchanted, a potion) fits. */
    private static String key(ItemStack stack) {
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        return stack.getTag() == null ? id : id + "{" + Integer.toHexString(stack.getTag().hashCode()) + "}";
    }

    private static String name(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null || !BuiltInRegistries.ITEM.containsKey(rl)) return id;
        return BuiltInRegistries.ITEM.get(rl).getDescription().getString();
    }
}
