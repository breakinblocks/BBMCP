package com.breakinblocks.bbmcp.recipe;

import java.util.Objects;

/** Selects the highest-priority loaded viewer without linking optional viewers on neutral paths. */
public final class RecipeViewerAdapterRegistry {
    private static final String JEI_ADAPTER_CLASS =
            "com.breakinblocks.bbmcp.recipe.JeiPluginAdapter";

    private RecipeViewerAdapterRegistry() {
    }

    public static IRecipeViewerAdapter create(RecipeCatalog catalog) {
        Objects.requireNonNull(catalog, "catalog");
        if (ClientRecipeViewerSupport.jeiRuntimeAvailable()) {
            try {
                Class<?> adapterClass = Class.forName(JEI_ADAPTER_CLASS, true,
                        RecipeViewerAdapterRegistry.class.getClassLoader());
                if (!IRecipeViewerAdapter.class.isAssignableFrom(adapterClass)) {
                    throw new IllegalStateException("JEI adapter does not implement IRecipeViewerAdapter");
                }
                Object adapter = adapterClass.getConstructor(RecipeCatalog.class).newInstance(catalog);
                return (IRecipeViewerAdapter) adapter;
            } catch (ReflectiveOperationException | LinkageError exception) {
                throw new IllegalStateException("JEI is available but its BBMCP adapter could not be loaded", exception);
            }
        }
        return new VanillaRecipeViewerAdapter(catalog);
    }
}
