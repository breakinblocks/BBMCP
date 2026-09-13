package com.breakinblocks.bbmcp.recipe;

import net.neoforged.fml.ModList;

import java.util.ArrayList;
import java.util.List;

/** Viewer detection that keeps optional viewer classes off the normal client class path. */
public final class ClientRecipeViewerSupport {
    private static final List<Viewer> VIEWERS = List.of(
            new Viewer("jei"),
            new Viewer("emi"),
            new Viewer("rei"));

    private static final String JEI_PLUGIN_CLASS =
            "com.breakinblocks.bbmcp.recipe.BbmcpJeiPlugin";

    private ClientRecipeViewerSupport() {
    }

    public static List<String> detectedViewers() {
        List<String> detected = new ArrayList<>();
        for (Viewer viewer : VIEWERS) {
            if (ModList.get().isLoaded(viewer.id())) {
                detected.add(viewer.id());
            }
        }
        return List.copyOf(detected);
    }

    public static boolean jeiRuntimeAvailable() {
        if (!ModList.get().isLoaded("jei")) {
            return false;
        }
        try {
            Class<?> pluginClass = Class.forName(JEI_PLUGIN_CLASS, false,
                    ClientRecipeViewerSupport.class.getClassLoader());
            Object runtime = pluginClass.getMethod("runtimeOrNull").invoke(null);
            return runtime != null;
        } catch (ReflectiveOperationException | LinkageError exception) {
            throw new IllegalStateException("JEI is loaded but BBMCP could not query its runtime", exception);
        }
    }

    public record Viewer(String id) {
    }
}
