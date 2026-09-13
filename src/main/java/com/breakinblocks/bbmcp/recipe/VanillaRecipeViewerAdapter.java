package com.breakinblocks.bbmcp.recipe;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

/**
 * Viewer-neutral implementation used when JEI is not loaded. It still
 * exposes the synchronized vanilla recipe graph and explicit unsupported
 * errors for GUI-only operations.
 */
public final class VanillaRecipeViewerAdapter implements IRecipeViewerAdapter {
    private final RecipeCatalog catalog;

    public VanillaRecipeViewerAdapter(RecipeCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    @Override
    public String id() {
        return "vanilla";
    }

    @Override
    public JsonObject getRecipeTree(ResourceLocation itemId, int maxDepth) {
        return RecipeGraph.buildTree(catalog, itemId, maxDepth);
    }

    @Override
    public JsonObject getItemUsages(ResourceLocation itemId) {
        JsonArray recipes = RecipeGraph.recipesUsing(catalog, itemId);
        JsonObject result = new JsonObject();
        result.addProperty("item_id", itemId.toString());
        result.addProperty("adapter", id());
        result.addProperty("count", recipes.size());
        result.add("recipes", recipes);
        return result;
    }

    @Override
    public JsonObject getWorkstationRecipes(ResourceLocation workstationId) {
        throw new UnsupportedOperationException(
                "Vanilla recipe data does not expose workstation catalysts: " + workstationId);
    }

    @Override
    public JsonObject scanForLoops(ResourceLocation itemId, int maxDepth) {
        return RecipeGraph.scanLoops(catalog, itemId, maxDepth);
    }

    @Override
    public void openRecipe(ResourceLocation recipeId, String mode) {
        throw new UnsupportedOperationException("No optional recipe viewer is loaded");
    }

    @Override
    public void renderRecipeCard(ResourceLocation recipeId, GuiGraphics graphics, int width, int height) {
        throw new UnsupportedOperationException("No optional recipe viewer is loaded");
    }
}
