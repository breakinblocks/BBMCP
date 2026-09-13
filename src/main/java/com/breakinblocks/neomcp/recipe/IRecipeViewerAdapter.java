package com.breakinblocks.neomcp.recipe;

import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * Client-side recipe-viewer boundary. Implementations may use a viewer API,
 * while callers remain independent from JEI, EMI, or REI types.
 */
public interface IRecipeViewerAdapter {
    String id();

    JsonObject getRecipeTree(ResourceLocation itemId, int maxDepth);

    JsonObject getItemUsages(ResourceLocation itemId);

    JsonObject getWorkstationRecipes(ResourceLocation workstationId);

    JsonObject scanForLoops(ResourceLocation itemId, int maxDepth);

    void openRecipe(ResourceLocation recipeId, String mode);

    void renderRecipeCard(ResourceLocation recipeId, GuiGraphics graphics, int width, int height);
}
