package com.breakinblocks.neomcp.mcp;

import com.google.gson.JsonElement;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;

import java.util.Objects;

public final class McpNbtJson {
    private McpNbtJson() {
    }

    public static JsonElement toJson(Tag tag) {
        return NbtOps.INSTANCE.convertTo(JsonOps.INSTANCE, Objects.requireNonNull(tag, "tag"));
    }

    public static Tag encodeDataComponents(DataComponentMap components, HolderLookup.Provider registries) {
        Objects.requireNonNull(components, "components");
        Objects.requireNonNull(registries, "registries");
        DataResult<Tag> result = DataComponentMap.CODEC.encodeStart(
                registries.createSerializationContext(NbtOps.INSTANCE),
                components);
        return result.getOrThrow(error -> new IllegalStateException("Unable to encode data components: " + error));
    }
}
