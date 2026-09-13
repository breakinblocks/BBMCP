package com.breakinblocks.neomcp.recipe;

import mezz.jei.api.JeiPlugin;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.resources.ResourceLocation;

/**
 * Optional JEI entry point. JEI discovers this class only when JEI itself is installed.
 *
 * The root build integrator must add the JEI Maven repository
 * {@code https://maven.blamejared.com} and this compile-only dependency:
 * {@code mezz.jei:jei-1.21.1-neoforge-api:19.25.0.322}.
 */
@JeiPlugin
public final class NeoMcpJeiPlugin implements IModPlugin {
    private static final ResourceLocation UID = ResourceLocation.fromNamespaceAndPath("neomcp", "jei_plugin");
    private static volatile IJeiRuntime runtime;

    @Override public ResourceLocation getPluginUid() { return UID; }

    @Override public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
        runtime = jeiRuntime;
    }

    @Override public void onRuntimeUnavailable() {
        runtime = null;
    }

    public static IJeiRuntime runtimeOrNull() {
        return runtime;
    }

    public static IJeiRuntime runtime() {
        IJeiRuntime value = runtime;
        if (value == null) throw new IllegalStateException("JEI runtime is not available yet");
        return value;
    }
}
