package com.breakinblocks.bbmcp.recipe;

import com.breakinblocks.bbmcp.config.BbmcpConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Viewer-neutral analysis of the canonical, synchronized recipe catalog.
 *
 * <p>This class intentionally does not infer workstations or catalysts from a
 * recipe type, serializer, or ingredient. The canonical catalog currently has
 * no such metadata, so the workstation/catalyst metric is zero and is marked
 * unavailable in its result.</p>
 */
public final class RecipeAnalysis {
    /** Underutilized means exactly zero canonical recipe consumers. */
    public static final int UNDERUTILIZED_CONSUMER_THRESHOLD = 0;

    private final RecipeCatalog catalog;

    public RecipeAnalysis(RecipeCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    /** Returns every canonical recipe serialized using {@link RecipeCatalog.RecipeView#toJson()}. */
    public JsonObject dumpRecipes(String modNamespace, String recipeType, int inlineLimit, long inlineByteLimit) {
        Objects.requireNonNull(modNamespace, "modNamespace");
        Objects.requireNonNull(recipeType, "recipeType");
        List<RecipeCatalog.RecipeView> recipes = catalog.list().stream()
                .filter(recipe -> modNamespace.isBlank() || recipe.id().startsWith(modNamespace + ":"))
                .filter(recipe -> recipeType.isBlank() || recipeType.equals(recipe.type()))
                .toList();
        if (inlineLimit < 1 || inlineByteLimit < 1) {
            throw new IllegalArgumentException("inlineLimit must be positive");
        }
        return serialized("dump", recipes, inlineLimit, inlineByteLimit);
    }

    /**
     * Computes bounded complexity metrics for one result item. Processing depth
     * follows canonical ingredient options to recipes producing those options.
     */
    public JsonObject analyzeItem(ResourceLocation itemId) {
        Objects.requireNonNull(itemId, "itemId");
        Map<String, List<RecipeCatalog.RecipeView>> index = catalog.snapshot().producing();
        Budget budget = new Budget(BbmcpConfig.maxRecipeGraphNodes());
        Set<String> namespaces = new LinkedHashSet<>();
        Map<DepthKey, DepthMemo> memo = new HashMap<>();
        DepthResult depth = depth(itemId.toString(), index, budget, new HashSet<>(), namespaces, 0, memo);
        JsonObject result = new JsonObject();
        result.addProperty("item_id", itemId.toString());
        result.addProperty("mod_namespace_diversity", namespaces.size());
        result.addProperty("max_sequential_processing_depth", depth.value());
        result.addProperty("depth_limit", BbmcpConfig.maxRecipeTreeDepth());
        result.addProperty("unique_workstation_catalyst_count", 0);
        result.addProperty("workstation_catalyst_metadata_available", false);
        result.addProperty("budget", BbmcpConfig.maxRecipeGraphNodes());
        result.addProperty("nodes_visited", budget.consumed);
        result.addProperty("truncated", budget.exhausted() || depth.truncated());
        return result;
    }

    /** Detects cycles reachable from one item under one global budget. */
    public JsonObject detectCycles(ResourceLocation itemId) {
        Objects.requireNonNull(itemId, "itemId");
        Map<String, List<RecipeCatalog.RecipeView>> index = catalog.snapshot().producing();
        Budget budget = new Budget(BbmcpConfig.maxRecipeGraphNodes());
        JsonArray cycles = new JsonArray();
        Set<String> emitted = new HashSet<>();
        boolean[] depthTruncated = {false};
        String item = itemId.toString();
        findCycles(item, index, new ArrayList<>(List.of(item)), new ArrayList<>(), cycles, emitted, budget, 0,
                depthTruncated);
        JsonObject result = new JsonObject();
        result.addProperty("item_id", item);
        result.addProperty("count", cycles.size());
        result.addProperty("depth_limit", BbmcpConfig.maxRecipeLoopDepth());
        result.addProperty("budget", BbmcpConfig.maxRecipeGraphNodes());
        result.addProperty("nodes_visited", budget.consumed);
        result.addProperty("truncated", budget.exhausted() || depthTruncated[0]);
        result.add("cycles", cycles);
        return result;
    }

    /** Returns produced items in {@code namespace} with zero canonical consumers. */
    public JsonObject underutilizedItems(String namespace, int inlineLimit) {
        Objects.requireNonNull(namespace, "namespace");
        if (namespace.isBlank() || namespace.indexOf(':') >= 0) {
            throw new IllegalArgumentException("namespace must be a non-empty namespace, not an item id");
        }
        if (inlineLimit < 1) {
            throw new IllegalArgumentException("inlineLimit must be positive");
        }
        Map<String, Integer> produced = new LinkedHashMap<>();
        Map<String, Set<String>> consumers = new HashMap<>();
        for (RecipeCatalog.RecipeView recipe : catalog.snapshot().recipes()) {
            if (recipe.result().count() > 0 && namespace.equals(idNamespace(recipe.result().id()))) {
                produced.merge(recipe.result().id(), 1, Integer::sum);
            }
            for (RecipeCatalog.IngredientView ingredient : recipe.ingredients()) {
                for (RecipeCatalog.ItemView item : ingredient.items()) {
                    if (namespace.equals(idNamespace(item.id()))) {
                        consumers.computeIfAbsent(item.id(), ignored -> new HashSet<>()).add(recipe.id());
                    }
                }
            }
        }
        JsonArray candidates = new JsonArray();
        produced.forEach((item, recipeCount) -> {
            int consumerCount = consumers.getOrDefault(item, Set.of()).size();
            if (consumerCount <= UNDERUTILIZED_CONSUMER_THRESHOLD) {
                JsonObject candidate = new JsonObject();
                candidate.addProperty("item_id", item);
                candidate.addProperty("producer_count", recipeCount);
                candidate.addProperty("consumer_count", consumerCount);
                candidates.add(candidate);
            }
        });
        JsonObject result = new JsonObject();
        result.addProperty("namespace", namespace);
        result.addProperty("consumer_threshold", UNDERUTILIZED_CONSUMER_THRESHOLD);
        result.addProperty("count", Math.min(candidates.size(), inlineLimit));
        result.addProperty("total_count", candidates.size());
        result.addProperty("truncated", candidates.size() > inlineLimit);
        JsonArray resultItems = candidates;
        if (candidates.size() > inlineLimit) {
            JsonArray capped = new JsonArray();
            for (int index = 0; index < inlineLimit; index++) {
                capped.add(candidates.get(index));
            }
            resultItems = capped;
        }
        result.add("items", resultItems);
        return result;
    }

    private static JsonObject serialized(
            String operation, List<RecipeCatalog.RecipeView> recipes, int inlineLimit, long inlineByteLimit) {
        JsonObject result = new JsonObject();
        result.addProperty("operation", operation);
        result.addProperty("total_count", recipes.size());
        JsonArray values = new JsonArray();
        long bytes = 0;
        int count = 0;
        boolean truncated = false;
        for (RecipeCatalog.RecipeView recipe : recipes) {
            if (count >= inlineLimit) {
                truncated = true;
                break;
            }
            JsonObject value = recipe.toJson();
            long valueBytes = value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (bytes + valueBytes > inlineByteLimit) {
                truncated = true;
                break;
            }
            values.add(value);
            bytes += valueBytes;
            count++;
        }
        result.addProperty("count", count);
        result.addProperty("inline_bytes", bytes);
        result.addProperty("inline_byte_limit", inlineByteLimit);
        result.addProperty("truncated", truncated);
        result.add("recipes", values);
        return result;
    }

    private static DepthResult depth(
            String item, Map<String, List<RecipeCatalog.RecipeView>> index, Budget budget, Set<String> path,
            Set<String> namespaces, int currentDepth, Map<DepthKey, DepthMemo> memo) {
        DepthKey key = new DepthKey(item, currentDepth);
        DepthMemo cached = memo.get(key);
        if (cached != null && cached.reachableItems().stream().noneMatch(path::contains)
                && budget.remaining() >= cached.cost()) {
            budget.consume(cached.cost());
            cached.namespaceVisits().forEach(namespaces::add);
            return new DepthResult(cached.value(), false, cached.reachableItems(), cached.namespaceVisits());
        }
        if (!budget.consume()) return new DepthResult(0, true, Set.of(), List.of());
        namespaces.add(idNamespace(item));
        Set<String> reachableItems = new HashSet<>(Set.of(item));
        List<String> namespaceVisits = new ArrayList<>(List.of(idNamespace(item)));
        if (currentDepth >= BbmcpConfig.maxRecipeTreeDepth()) {
            boolean truncated = !index.getOrDefault(item, List.of()).isEmpty();
            if (!truncated) {
                memo.put(key, new DepthMemo(0, 1, Set.copyOf(reachableItems), List.copyOf(namespaceVisits)));
            }
            return new DepthResult(0, truncated, reachableItems, namespaceVisits);
        }
        if (!path.add(item)) return new DepthResult(0, false, reachableItems, namespaceVisits);
        int best = 0;
        boolean truncated = false;
        int startConsumed = budget.consumed();
        for (RecipeCatalog.RecipeView recipe : index.getOrDefault(item, List.of())) {
            if (!budget.consume()) {
                truncated = true;
                break;
            }
            int recipeDepth = 1;
            for (RecipeCatalog.IngredientView ingredient : recipe.ingredients()) {
                int optionDepth = 0;
                for (RecipeCatalog.ItemView option : ingredient.items()) {
                    if (!budget.consume()) return new DepthResult(best, true, reachableItems, namespaceVisits);
                    DepthResult child = depth(option.id(), index, budget, path, namespaces, currentDepth + 1, memo);
                    optionDepth = Math.max(optionDepth, child.value());
                    if (child.truncated()) truncated = true;
                    reachableItems.addAll(child.reachableItems());
                    namespaceVisits.addAll(child.namespaceVisits());
                }
                // Ingredients are consumed by the same recipe step; only the
                // longest prerequisite chain contributes to sequential depth.
                recipeDepth = Math.max(recipeDepth, 1 + optionDepth);
            }
            best = Math.max(best, recipeDepth);
        }
        path.remove(item);
        boolean pathIndependent = path.stream().noneMatch(reachableItems::contains);
        if (!truncated && pathIndependent) {
            memo.put(key, new DepthMemo(best, budget.consumed() - startConsumed + 1,
                    Set.copyOf(reachableItems), List.copyOf(namespaceVisits)));
        }
        return new DepthResult(best, truncated, reachableItems, namespaceVisits);
    }

    private record DepthKey(String item, int currentDepth) {
    }

    private record DepthMemo(int value, int cost, Set<String> reachableItems, List<String> namespaceVisits) {
    }

    private static void findCycles(String item, Map<String, List<RecipeCatalog.RecipeView>> index,
                                   List<String> itemPath, List<String> recipePath,
                                   JsonArray cycles, Set<String> emitted, Budget budget, int currentDepth,
                                   boolean[] depthTruncated) {
        if (!budget.consume()) return;
        if (currentDepth >= BbmcpConfig.maxRecipeLoopDepth()) {
            depthTruncated[0] |= !index.getOrDefault(item, List.of()).isEmpty();
            return;
        }
        for (RecipeCatalog.RecipeView recipe : index.getOrDefault(item, List.of())) {
            if (!budget.consume()) return;
            for (RecipeCatalog.IngredientView ingredient : recipe.ingredients()) {
                for (RecipeCatalog.ItemView option : ingredient.items()) {
                    if (!budget.consume()) return;
                    String next = option.id();
                    int start = itemPath.indexOf(next);
                    if (start >= 0) {
                        List<String> items = new ArrayList<>(itemPath.subList(start, itemPath.size()));
                        items.add(next);
                        List<String> recipes = new ArrayList<>(recipePath.subList(start, recipePath.size()));
                        recipes.add(recipe.id());
                        String key = items + "|" + recipes;
                        if (emitted.add(key)) {
                            JsonObject cycle = new JsonObject();
                            JsonArray cycleItems = new JsonArray(); items.forEach(cycleItems::add);
                            JsonArray cycleRecipes = new JsonArray(); recipes.forEach(cycleRecipes::add);
                            cycle.add("items", cycleItems); cycle.add("recipes", cycleRecipes); cycles.add(cycle);
                        }
                    } else {
                        List<String> nextItems = new ArrayList<>(itemPath); nextItems.add(next);
                        List<String> nextRecipes = new ArrayList<>(recipePath); nextRecipes.add(recipe.id());
                        findCycles(next, index, nextItems, nextRecipes, cycles, emitted, budget, currentDepth + 1,
                                depthTruncated);
                        if (budget.exhausted()) return;
                    }
                }
            }
        }
    }

    private static String idNamespace(String id) {
        int separator = id.indexOf(':');
        return separator < 1 ? id : id.substring(0, separator);
    }

    private static final class Budget {
        private int remaining;
        private int consumed;
        private Budget(int limit) { remaining = limit; }
        private boolean consume() { if (remaining == 0) return false; remaining--; consumed++; return true; }
        private int remaining() { return remaining; }
        private int consumed() { return consumed; }
        private void consume(int count) {
            if (count < 0 || count > remaining) {
                throw new IllegalArgumentException("Cannot consume more recipe nodes than remain in budget");
            }
            remaining -= count;
            consumed += count;
        }
        private boolean exhausted() { return remaining == 0; }
    }

    private record DepthResult(int value, boolean truncated, Set<String> reachableItems, List<String> namespaceVisits) {
    }
}
