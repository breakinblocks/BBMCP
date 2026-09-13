package com.breakinblocks.bbmcp.recipe;

import com.breakinblocks.bbmcp.config.BbmcpConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Deterministic, bounded graph operations over the synchronized recipe catalog. */
final class RecipeGraph {
    private RecipeGraph() {
    }

    static JsonObject buildTree(RecipeCatalog catalog, ResourceLocation itemId, int maxDepth) {
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(itemId, "itemId");
        if (maxDepth < 0 || maxDepth > BbmcpConfig.maxRecipeTreeDepth()) {
            throw new IllegalArgumentException(
                    "maxDepth must be between 0 and " + BbmcpConfig.maxRecipeTreeDepth());
        }

        RecipeCatalog.Snapshot snapshot = catalog.snapshot();
        ExpansionBudget budget = new ExpansionBudget(BbmcpConfig.maxRecipeGraphNodes());
        JsonObject result = new JsonObject();
        result.addProperty("item_id", itemId.toString());
        result.addProperty("max_depth", maxDepth);
        result.add("tree", itemNode(snapshot, itemId, 0, maxDepth, Set.of(), budget));
        result.addProperty("budget", BbmcpConfig.maxRecipeGraphNodes());
        result.addProperty("nodes_visited", budget.consumed());
        result.addProperty("truncated", budget.exhausted());
        return result;
    }

    static JsonArray recipesUsing(RecipeCatalog catalog, ResourceLocation itemId) {
        JsonArray recipes = new JsonArray();
        for (RecipeCatalog.RecipeView recipe : catalog.recipesUsing(itemId)) {
            JsonObject entry = recipe.toJson();
            entry.addProperty("role", "ingredient");
            recipes.add(entry);
        }
        return recipes;
    }

    static JsonObject scanLoops(RecipeCatalog catalog, ResourceLocation itemId, int maxDepth) {
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(itemId, "itemId");
        if (maxDepth < 1 || maxDepth > BbmcpConfig.maxRecipeLoopDepth()) {
            throw new IllegalArgumentException(
                    "maxDepth must be between 1 and " + BbmcpConfig.maxRecipeLoopDepth());
        }

        RecipeCatalog.Snapshot snapshot = catalog.snapshot();
        ExpansionBudget budget = new ExpansionBudget(BbmcpConfig.maxRecipeGraphNodes());
        JsonArray loops = new JsonArray();
        findLoops(snapshot, itemId.toString(), maxDepth, new ArrayList<>(List.of(itemId.toString())),
                new ArrayList<>(), loops, new HashSet<>(), budget);
        JsonObject result = new JsonObject();
        result.addProperty("item_id", itemId.toString());
        result.addProperty("max_depth", maxDepth);
        result.addProperty("count", loops.size());
        result.addProperty("budget", BbmcpConfig.maxRecipeGraphNodes());
        result.addProperty("nodes_visited", budget.consumed());
        result.addProperty("truncated", budget.exhausted());
        result.add("loops", loops);
        return result;
    }

    private static JsonObject itemNode(
            RecipeCatalog.Snapshot snapshot,
            ResourceLocation itemId,
            int depth,
            int maxDepth,
            Set<String> path,
            ExpansionBudget budget) {
        JsonObject node = new JsonObject();
        node.addProperty("item_id", itemId.toString());
        node.addProperty("depth", depth);
        JsonArray recipes = new JsonArray();
        node.add("recipes", recipes);

        if (depth >= maxDepth) {
            node.addProperty("truncated", true);
            node.addProperty("truncation_reason", "max_depth");
            return node;
        }
        if (!budget.consume()) {
            node.addProperty("truncated", true);
            node.addProperty("truncation_reason", "budget_exhausted");
            return node;
        }

        Set<String> nextPath = new LinkedHashSet<>(path);
        nextPath.add(itemId.toString());
        boolean truncated = false;
        for (RecipeCatalog.RecipeView recipe : snapshot.producing().getOrDefault(itemId.toString(), List.of())) {
            if (!budget.consume()) {
                truncated = true;
                break;
            }
            JsonObject recipeData = recipe.toJson();
            JsonArray inputs = new JsonArray();
            recipeData.add("inputs", inputs);
            recipeData.add("required_workstations", new JsonArray());
            for (RecipeCatalog.IngredientView ingredient : recipe.ingredients()) {
                if (!budget.consume()) {
                    truncated = true;
                    break;
                }
                JsonObject input = ingredient.toJson();
                input.addProperty("amount", 1);
                JsonArray children = new JsonArray();
                input.add("children", children);
                for (RecipeCatalog.ItemView option : ingredient.items()) {
                    if (!budget.consume()) {
                        truncated = true;
                        break;
                    }
                    ResourceLocation childId = ResourceLocation.tryParse(option.id());
                    if (childId == null) {
                        throw new IllegalStateException("Recipe ingredient has an invalid item id: " + option.id());
                    }
                    if (nextPath.contains(childId.toString())) {
                        JsonObject cycle = new JsonObject();
                        cycle.addProperty("item_id", childId.toString());
                        cycle.addProperty("cycle", true);
                        children.add(cycle);
                    } else {
                        children.add(itemNode(snapshot, childId, depth + 1, maxDepth, nextPath, budget));
                    }
                }
                if (truncated) {
                    break;
                }
                inputs.add(input);
            }
            if (truncated) {
                break;
            }
            recipes.add(recipeData);
        }
        node.addProperty("truncated", truncated);
        if (truncated) {
            node.addProperty("truncation_reason", "budget_exhausted");
        }
        return node;
    }

    private static void findLoops(
            RecipeCatalog.Snapshot snapshot,
            String currentItem,
            int maxDepth,
            List<String> itemPath,
            List<String> recipePath,
            JsonArray loops,
            Set<String> emitted,
            ExpansionBudget budget) {
        if (recipePath.size() >= maxDepth || budget.exhausted()) {
            return;
        }
        ResourceLocation currentId = ResourceLocation.tryParse(currentItem);
        if (currentId == null) {
            throw new IllegalStateException("Recipe graph contains an invalid item id: " + currentItem);
        }
        for (RecipeCatalog.RecipeView recipe : snapshot.producing().getOrDefault(currentId.toString(), List.of())) {
            if (!budget.consume()) {
                return;
            }
            for (RecipeCatalog.IngredientView ingredient : recipe.ingredients()) {
                if (!budget.consume()) {
                    return;
                }
                for (RecipeCatalog.ItemView option : ingredient.items()) {
                    if (!budget.consume()) {
                        return;
                    }
                    String nextItem = option.id();
                    int cycleStart = itemPath.indexOf(nextItem);
                    if (cycleStart >= 0) {
                        List<String> cycleItems = new ArrayList<>(itemPath.subList(cycleStart, itemPath.size()));
                        cycleItems.add(nextItem);
                        List<String> cycleRecipes = new ArrayList<>(recipePath.subList(cycleStart, recipePath.size()));
                        cycleRecipes.add(recipe.id());
                        String key = cycleItems + "|" + cycleRecipes;
                        if (emitted.add(key)) {
                            JsonObject loop = new JsonObject();
                            JsonArray items = new JsonArray();
                            cycleItems.forEach(items::add);
                            JsonArray recipes = new JsonArray();
                            cycleRecipes.forEach(recipes::add);
                            loop.add("items", items);
                            loop.add("recipes", recipes);
                            loops.add(loop);
                        }
                    } else {
                        List<String> nextItems = new ArrayList<>(itemPath);
                        nextItems.add(nextItem);
                        List<String> nextRecipes = new ArrayList<>(recipePath);
                        nextRecipes.add(recipe.id());
                        findLoops(snapshot, nextItem, maxDepth, nextItems, nextRecipes, loops, emitted, budget);
                        if (budget.exhausted()) {
                            return;
                        }
                    }
                }
            }
        }
    }

    private static final class ExpansionBudget {
        private int remaining;
        private int consumed;

        private ExpansionBudget(int limit) {
            if (limit < 1) {
                throw new IllegalArgumentException("Recipe graph budget must be positive");
            }
            this.remaining = limit;
        }

        private boolean consume() {
            if (remaining == 0) {
                return false;
            }
            remaining--;
            consumed++;
            return true;
        }

        private boolean exhausted() {
            return remaining == 0;
        }

        private int consumed() {
            return consumed;
        }
    }
}
