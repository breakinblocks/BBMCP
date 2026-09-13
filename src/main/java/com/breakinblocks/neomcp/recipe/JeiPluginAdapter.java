package com.breakinblocks.neomcp.recipe;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.IFocusFactory;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** JEI 19.x adapter backed by the synchronized client recipe manager. */
public final class JeiPluginAdapter implements IRecipeViewerAdapter {
    private final RecipeCatalog catalog;

    public JeiPluginAdapter(RecipeCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    @Override
    public String id() {
        return "jei";
    }

    @Override
    public JsonObject getRecipeTree(ResourceLocation itemId, int maxDepth) {
        IJeiRuntime runtime = requireRuntime();
        JsonObject result = RecipeGraph.buildTree(catalog, itemId, maxDepth);
        enrichWorkstations(runtime, result.getAsJsonObject("tree"));
        result.addProperty("adapter", id());
        return result;
    }

    @Override
    public JsonObject getItemUsages(ResourceLocation itemId) {
        IJeiRuntime runtime = requireRuntime();
        ItemStack stack = itemStack(itemId);
        IFocus<ItemStack> inputFocus = focus(runtime, RecipeIngredientRole.INPUT, stack);
        IFocus<ItemStack> catalystFocus = focus(runtime, RecipeIngredientRole.CATALYST, stack);
        List<IFocus<?>> focuses = List.of(inputFocus, catalystFocus);
        JsonArray categories = categorySummaries(runtime, focuses);
        JsonArray recipes = recipesForFocus(runtime, focuses);
        JsonObject result = new JsonObject();
        result.addProperty("item_id", itemId.toString());
        result.addProperty("adapter", id());
        result.addProperty("count", recipes.size());
        result.add("recipes", recipes);
        result.add("jei_categories", categories);
        return result;
    }

    @Override
    public JsonObject getWorkstationRecipes(ResourceLocation workstationId) {
        IJeiRuntime runtime = requireRuntime();
        ItemStack stack = itemStack(workstationId);
        IFocus<ItemStack> focus = focus(runtime, RecipeIngredientRole.CATALYST, stack);
        IRecipeManager recipeManager = runtime.getRecipeManager();
        List<IFocus<?>> focuses = List.of(focus);
        JsonArray categories = categorySummaries(runtime, focuses);
        JsonArray recipes = new JsonArray();
        Set<String> seen = new HashSet<>();
        for (IRecipeCategory<?> category : recipeManager.createRecipeCategoryLookup()
                .includeHidden()
                .limitFocus(focuses)
                .get()
                .sorted(Comparator.comparing(categoryValue -> categoryValue.getRecipeType().getUid().toString()))
                .toList()) {
            for (Object value : recipeManager.createRecipeLookup(category.getRecipeType()).includeHidden().get().toList()) {
                JsonObject recipe = recipeJson(category, value);
                String key = recipeKey(category, recipe);
                if (seen.add(key)) {
                    recipes.add(recipe);
                }
            }
        }
        JsonObject result = new JsonObject();
        result.addProperty("workstation_id", workstationId.toString());
        result.addProperty("adapter", id());
        result.addProperty("count", recipes.size());
        result.add("categories", categories);
        result.add("recipes", recipes);
        return result;
    }

    private JsonArray recipesForFocus(IJeiRuntime runtime, List<IFocus<?>> focuses) {
        IRecipeManager recipeManager = runtime.getRecipeManager();
        JsonArray recipes = new JsonArray();
        Set<String> seen = new HashSet<>();
        recipeManager.createRecipeCategoryLookup()
                .includeHidden()
                .limitFocus(focuses)
                .get()
                .sorted(Comparator.comparing(category -> category.getRecipeType().getUid().toString()))
                .forEach(category -> recipeManager.createRecipeLookup(category.getRecipeType())
                        .includeHidden()
                        .limitFocus(focuses)
                        .get()
                        .forEach(value -> {
                            JsonObject recipe = recipeJson(category, value);
                            if (seen.add(recipeKey(category, recipe))) {
                                recipes.add(recipe);
                            }
                        }));
        return recipes;
    }

    @Override
    public JsonObject scanForLoops(ResourceLocation itemId, int maxDepth) {
        JsonObject result = RecipeGraph.scanLoops(catalog, itemId, maxDepth);
        result.addProperty("adapter", id());
        return result;
    }

    @Override
    public void openRecipe(ResourceLocation recipeId, String mode) {
        IJeiRuntime runtime = requireRuntime();
        RecipeSelection selection = resolveRecipe(runtime, recipeId);
        if ("uses".equals(mode)) {
            runtime.getRecipesGui().show(List.of(focus(
                    runtime,
                    RecipeIngredientRole.INPUT,
                    selection.recipe().value().getResultItem(catalog.registries()))));
        } else {
            showRecipe(runtime, selection.category(), selection.jeiRecipe());
        }
    }

    @Override
    public void renderRecipeCard(ResourceLocation recipeId, GuiGraphics graphics, int width, int height) {
        Objects.requireNonNull(graphics, "graphics");
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("Recipe card dimensions must be positive");
        }
        IJeiRuntime runtime = requireRuntime();
        RecipeSelection selection = resolveRecipe(runtime, recipeId);
        renderLayout(runtime.getRecipeManager(), selection.category(), selection.jeiRecipe(), graphics, width, height);
    }

    private void enrichWorkstations(IJeiRuntime runtime, JsonObject itemNode) {
        JsonArray recipes = itemNode.getAsJsonArray("recipes");
        for (int index = 0; index < recipes.size(); index++) {
            JsonObject recipe = recipes.get(index).getAsJsonObject();
            ResourceLocation recipeId = requiredResourceLocation(recipe.get("id").getAsString(), "recipe id");
            try {
                recipe.add("required_workstations", workstationIds(runtime, catalog.holder(recipeId).value()));
            } catch (IllegalStateException exception) {
                recipe.addProperty("workstation_metadata_unavailable", exception.getMessage());
            }
            JsonArray inputs = recipe.getAsJsonArray("inputs");
            for (int inputIndex = 0; inputIndex < inputs.size(); inputIndex++) {
                JsonArray children = inputs.get(inputIndex).getAsJsonObject().getAsJsonArray("children");
                for (int childIndex = 0; childIndex < children.size(); childIndex++) {
                    JsonElementAccessor child = new JsonElementAccessor(children.get(childIndex));
                    if (child.isObject() && child.object().has("recipes")) {
                        enrichWorkstations(runtime, child.object());
                    }
                }
            }
        }
    }

    private JsonArray workstationIds(IJeiRuntime runtime, Recipe<?> recipe) {
        ResourceLocation typeId = BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType());
        if (typeId == null) {
            throw new IllegalStateException("Recipe type is unavailable in the Minecraft registry");
        }
        RecipeType<?> jeiType = runtime.getJeiHelpers().getRecipeType(typeId)
                .orElseThrow(() -> new IllegalStateException("JEI recipe type is unavailable: " + typeId));
        JsonArray workstations = new JsonArray();
        runtime.getRecipeManager().createRecipeCatalystLookup(jeiType).includeHidden().getItemStack()
                .map(this::itemId)
                .distinct()
                .sorted()
                .forEach(workstations::add);
        return workstations;
    }

    private JsonArray categorySummaries(IJeiRuntime runtime, List<IFocus<?>> focuses) {
        JsonArray categories = new JsonArray();
        runtime.getRecipeManager().createRecipeCategoryLookup()
                .includeHidden()
                .limitFocus(focuses)
                .get()
                .sorted(Comparator.comparing(category -> category.getRecipeType().getUid().toString()))
                .forEach(category -> {
                    JsonObject value = new JsonObject();
                    value.addProperty("id", category.getRecipeType().getUid().toString());
                    value.addProperty("title", category.getTitle().getString());
                    categories.add(value);
                });
        return categories;
    }

    private JsonObject recipeJson(IRecipeCategory<?> category, Object value) {
        ResourceLocation registryName = recipeRegistryName(category, value);
        JsonObject result = new JsonObject();
        if (registryName == null) {
            result.add("recipe_id", JsonNull.INSTANCE);
            result.addProperty("id_available", false);
            result.addProperty("serialized", value.toString());
        } else {
            result.addProperty("recipe_id", registryName.toString());
            catalog.find(registryName).ifPresent(recipe -> result.add("recipe", recipe.toJson()));
        }
        result.addProperty("category", category.getRecipeType().getUid().toString());
        result.addProperty("title", category.getTitle().getString());
        return result;
    }

    private RecipeSelection resolveRecipe(IJeiRuntime runtime, ResourceLocation recipeId) {
        RecipeHolder<?> holder = catalog.holder(recipeId);
        ResourceLocation typeId = requiredKey(
                BuiltInRegistries.RECIPE_TYPE.getKey(holder.value().getType()), "recipe type");
        RecipeType<?> jeiType = runtime.getJeiHelpers().getRecipeType(typeId)
                .orElseThrow(() -> new IllegalStateException("JEI recipe type is unavailable: " + typeId));
        IRecipeManager recipeManager = runtime.getRecipeManager();
        IRecipeCategory<?> category = recipeManager.getRecipeCategory(jeiType);
        Object jeiRecipe = recipeManager.createRecipeLookup(jeiType)
                .includeHidden()
                .get()
                .filter(value -> recipeId.equals(recipeRegistryName(category, value)))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "JEI did not index the synchronized recipe: " + recipeId));
        return new RecipeSelection(holder, category, jeiRecipe);
    }

    private String recipeKey(IRecipeCategory<?> category, JsonObject recipe) {
        JsonElement recipeId = recipe.get("recipe_id");
        if (recipeId != null && recipeId.isJsonPrimitive()) {
            return recipeId.getAsString();
        }
        return category.getRecipeType().getUid() + "|" + recipe.get("serialized").getAsString();
    }

    private ResourceLocation recipeRegistryName(IRecipeCategory<?> category, Object value) {
        return recipeRegistryNameTyped(category, value);
    }

    private <T> ResourceLocation recipeRegistryNameTyped(IRecipeCategory<T> category, Object value) {
        T recipe = category.getRecipeType().getRecipeClass().cast(value);
        return category.getRegistryName(recipe);
    }

    private IFocus<ItemStack> focus(IJeiRuntime runtime, RecipeIngredientRole role, ItemStack stack) {
        ITypedIngredient<ItemStack> typed = runtime.getIngredientManager().createTypedIngredient(stack, true)
                .orElseThrow(() -> new IllegalStateException("JEI cannot index item stack: " + itemId(stack)));
        IFocusFactory factory = runtime.getJeiHelpers().getFocusFactory();
        return factory.createFocus(role, typed);
    }

    private ItemStack itemStack(ResourceLocation itemId) {
        Item item = BuiltInRegistries.ITEM.getOptional(itemId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown item: " + itemId));
        return new ItemStack(item);
    }

    private String itemId(ItemStack stack) {
        return requiredKey(BuiltInRegistries.ITEM.getKey(stack.getItem()), "item").toString();
    }

    private IJeiRuntime requireRuntime() {
        return NeoMcpJeiPlugin.runtime();
    }

    private ResourceLocation requiredResourceLocation(String value, String description) {
        ResourceLocation id = ResourceLocation.tryParse(value);
        if (id == null) {
            throw new IllegalStateException("Invalid " + description + ": " + value);
        }
        return id;
    }

    private ResourceLocation requiredKey(ResourceLocation value, String description) {
        return Objects.requireNonNull(value, "Missing registered " + description);
    }

    private <T> void renderLayout(
            IRecipeManager manager,
            IRecipeCategory<T> category,
            Object recipeValue,
            GuiGraphics graphics,
            int width,
            int height) {
        T recipe = category.getRecipeType().getRecipeClass().cast(recipeValue);
        IRecipeLayoutDrawable<T> layout = manager.createRecipeLayoutDrawable(
                        category,
                        recipe,
                        NeoMcpJeiPlugin.runtime().getJeiHelpers().getFocusFactory().getEmptyFocusGroup())
                .orElseThrow(() -> new IllegalStateException("JEI could not create a recipe card layout"));
        Rect2i bounds = layout.getRectWithBorder();
        int x = Math.max(0, (width - bounds.getWidth()) / 2);
        int y = Math.max(0, (height - bounds.getHeight()) / 2);
        layout.setPosition(x, y);
        layout.drawRecipe(graphics, -1, -1);
        layout.drawOverlays(graphics, -1, -1);
    }

    private <T> void showRecipe(IJeiRuntime runtime, IRecipeCategory<T> category, Object recipeValue) {
        T recipe = category.getRecipeType().getRecipeClass().cast(recipeValue);
        runtime.getRecipesGui().showRecipes(category, List.of(recipe), List.of());
    }

    private record RecipeSelection(RecipeHolder<?> recipe, IRecipeCategory<?> category, Object jeiRecipe) {
    }

    /** Avoids unchecked JsonElement casts while walking an arbitrary tree node. */
    private record JsonElementAccessor(com.google.gson.JsonElement element) {
        private boolean isObject() { return element.isJsonObject(); }
        private JsonObject object() { return element.getAsJsonObject(); }
    }
}
