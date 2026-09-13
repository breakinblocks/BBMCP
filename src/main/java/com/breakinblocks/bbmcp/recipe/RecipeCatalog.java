package com.breakinblocks.bbmcp.recipe;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** A viewer-neutral, read-only view of a client-synchronised recipe manager. */
public final class RecipeCatalog {
    public static final String UNAVAILABLE_RECIPE_TYPE = "unavailable";

    private final RecipeManager recipes;
    private final HolderLookup.Provider registries;
    private Snapshot snapshot;

    public RecipeCatalog(RecipeManager recipes, HolderLookup.Provider registries) {
        this.recipes = Objects.requireNonNull(recipes, "recipes");
        this.registries = Objects.requireNonNull(registries, "registries");
    }

    public List<RecipeView> list() {
        return snapshot().recipes();
    }

    public List<RecipeView> search(String query) {
        String needle = Objects.requireNonNull(query, "query").trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            return list();
        }
        return recipes.getOrderedRecipes().stream()
                .filter(holder -> matches(holder, needle))
                .map(this::view)
                .toList();
    }

    public RecipeView get(ResourceLocation id) {
        return view(holder(Objects.requireNonNull(id, "id")));
    }

    public Optional<RecipeView> find(ResourceLocation id) {
        return recipes.byKey(Objects.requireNonNull(id, "id")).map(this::view);
    }

    public RecipeHolder<?> holder(ResourceLocation id) {
        return recipes.byKey(Objects.requireNonNull(id, "id"))
                .orElseThrow(() -> new IllegalArgumentException("Unknown recipe: " + id));
    }

    public HolderLookup.Provider registries() {
        return registries;
    }

    public List<RecipeView> recipesProducing(ResourceLocation itemId) {
        String requestedId = Objects.requireNonNull(itemId, "itemId").toString();
        return recipes.getOrderedRecipes().stream()
                .filter(holder -> {
                    ItemStack result = holder.value().getResultItem(registries);
                    return !result.isEmpty() && requestedId.equals(itemId(result));
                })
                .map(this::view)
                .toList();
    }

    public List<RecipeView> recipesUsing(ResourceLocation itemId) {
        String requestedId = Objects.requireNonNull(itemId, "itemId").toString();
        return snapshot().consuming().getOrDefault(requestedId, List.of());
    }

    public JsonArray listJson() {
        return toJson(list());
    }

    public JsonArray searchJson(String query) {
        return toJson(search(query));
    }

    public JsonObject getJson(ResourceLocation id) {
        return get(id).toJson();
    }

    private List<RecipeView> views(Collection<RecipeHolder<?>> holders) {
        return holders.stream().map(this::view).toList();
    }

    synchronized Snapshot snapshot() {
            List<RecipeHolder<?>> ordered = List.copyOf(recipes.getOrderedRecipes());
        if (snapshot == null || !snapshot.sameHolders(ordered)) {
            List<RecipeView> views = views(ordered);
            Map<String, List<RecipeView>> producing = new LinkedHashMap<>();
            Map<String, List<RecipeView>> consuming = new LinkedHashMap<>();
            for (RecipeView recipe : views) {
                if (recipe.result().count() > 0) {
                    producing.computeIfAbsent(recipe.result().id(), ignored -> new ArrayList<>()).add(recipe);
                }
                Set<String> ingredientItems = new LinkedHashSet<>();
                for (IngredientView ingredient : recipe.ingredients()) {
                    for (ItemView item : ingredient.items()) {
                        ingredientItems.add(item.id());
                    }
                }
                for (String ingredientItem : ingredientItems) {
                    consuming.computeIfAbsent(ingredientItem, ignored -> new ArrayList<>()).add(recipe);
                }
            }
            producing.replaceAll((key, value) -> List.copyOf(value));
            consuming.replaceAll((key, value) -> List.copyOf(value));
            snapshot = new Snapshot(List.copyOf(ordered), List.copyOf(views), Map.copyOf(producing),
                    Map.copyOf(consuming));
        }
        return snapshot;
    }

    record Snapshot(List<RecipeHolder<?>> holders, List<RecipeView> recipes,
                    Map<String, List<RecipeView>> producing, Map<String, List<RecipeView>> consuming) {
        private boolean sameHolders(List<RecipeHolder<?>> current) {
            if (holders.size() != current.size()) return false;
            for (int index = 0; index < holders.size(); index++) {
                if (holders.get(index) != current.get(index)) return false;
            }
            return true;
        }
    }

    private RecipeView view(RecipeHolder<?> holder) {
        Recipe<?> recipe = holder.value();
        ResourceLocation type = BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType());
        ResourceLocation serializer = requiredKey(
                BuiltInRegistries.RECIPE_SERIALIZER.getKey(recipe.getSerializer()), "recipe serializer");
        ItemStack result = recipe.getResultItem(registries);
        List<IngredientView> ingredients = recipe.getIngredients().stream().map(this::ingredient).toList();
        return new RecipeView(holder.id().toString(), type == null ? UNAVAILABLE_RECIPE_TYPE : type.toString(),
                serializer.toString(), recipe.getGroup(),
                item(result), ingredients);
    }

    private boolean matches(RecipeHolder<?> holder, String needle) {
        Recipe<?> recipe = holder.value();
        if (holder.id().toString().toLowerCase(Locale.ROOT).contains(needle)
                || recipe.getGroup().toLowerCase(Locale.ROOT).contains(needle)) {
            return true;
        }
        if (item(recipe.getResultItem(registries)).matches(needle)) {
            return true;
        }
        return recipe.getIngredients().stream().map(this::ingredient).anyMatch(value -> value.matches(needle));
    }

    private IngredientView ingredient(Ingredient ingredient) {
        List<ItemView> items = new ArrayList<>();
        for (ItemStack stack : ingredient.getItems()) {
            items.add(item(stack));
        }
        List<String> tags = new ArrayList<>();
        for (Ingredient.Value value : ingredient.getValues()) {
            if (value instanceof Ingredient.TagValue tagValue) {
                tags.add(tagValue.tag().location().toString());
            }
        }
        return new IngredientView(ingredient.isCustom() ? "custom" : "items", items, tags);
    }

    private ItemView item(ItemStack stack) {
        if (stack.isEmpty()) {
            return new ItemView("minecraft:air", 0, new JsonObject());
        }
        String key = itemId(stack);
        JsonElement encoded = ItemStack.CODEC.encodeStart(
                registries.createSerializationContext(JsonOps.INSTANCE), stack)
                .resultOrPartial(message -> {
                    throw new IllegalStateException("Unable to serialize item stack " + key + ": " + message);
                }).orElseThrow(() -> new IllegalStateException("Unable to serialize item stack: " + key));
        return new ItemView(key, stack.getCount(), encoded);
    }

    private static String itemId(ItemStack stack) {
        return itemId(BuiltInRegistries.ITEM.getKey(stack.getItem())).toString();
    }

    private static ResourceLocation itemId(ResourceLocation id) {
        return requiredKey(id, "item");
    }

    private static JsonArray toJson(List<RecipeView> views) {
        JsonArray result = new JsonArray();
        views.forEach(view -> result.add(view.toJson()));
        return result;
    }

    private static ResourceLocation requiredKey(ResourceLocation key, String kind) {
        return Objects.requireNonNull(key, "Missing registered " + kind + " key");
    }

    public record ItemView(String id, int count, JsonElement encoded) {
        public ItemView {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(encoded, "encoded");
            if (count < 0) throw new IllegalArgumentException("Item count cannot be negative");
            if (count == 0 && !"minecraft:air".equals(id)) {
                throw new IllegalArgumentException("Only minecraft:air may have a zero item count");
            }
        }
        public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("id", id);
            json.addProperty("count", count);
            json.addProperty("empty", count == 0);
            json.add("stack", encoded.deepCopy());
            return json;
        }
        private boolean matches(String needle) { return id.toLowerCase(Locale.ROOT).contains(needle); }
    }

    public record IngredientView(String kind, List<ItemView> items, List<String> tags) {
        public IngredientView {
            Objects.requireNonNull(kind, "kind");
            items = List.copyOf(items);
            tags = List.copyOf(tags);
        }
        private boolean matches(String needle) {
            return items.stream().anyMatch(item -> item.matches(needle)) || tags.stream()
                    .map(value -> value.toLowerCase(Locale.ROOT)).anyMatch(value -> value.contains(needle));
        }
        public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("kind", kind);
            JsonArray values = new JsonArray();
            items.forEach(item -> values.add(item.toJson()));
            json.add("items", values);
            JsonArray tagValues = new JsonArray();
            tags.forEach(tagValues::add);
            json.add("tags", tagValues);
            return json;
        }
    }

    public record RecipeView(String id, String type, String serializer, String group, ItemView result,
                             List<IngredientView> ingredients) {
        public RecipeView {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(serializer, "serializer");
            Objects.requireNonNull(group, "group");
            Objects.requireNonNull(result, "result");
            ingredients = List.copyOf(ingredients);
        }
        public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("id", id);
            json.addProperty("type", type);
            json.addProperty("serializer", serializer);
            json.addProperty("group", group);
            json.add("result", result.toJson());
            JsonArray values = new JsonArray();
            ingredients.forEach(ingredient -> values.add(ingredient.toJson()));
            json.add("ingredients", values);
            return json;
        }
    }
}
