package com.breakinblocks.bbmcp.mcp;

import com.google.gson.JsonElement;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;
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
        return encode(DataComponentMap.CODEC, components, registries, "data components");
    }

    public static Tag encodeDataComponentPatch(DataComponentPatch patch, HolderLookup.Provider registries) {
        return encode(DataComponentPatch.CODEC, patch, registries, "data component patch");
    }

    private static <T> Tag encode(Codec<T> codec, T value, HolderLookup.Provider registries, String description) {
        Objects.requireNonNull(codec, "codec");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(registries, "registries");
        DataResult<Tag> result = codec.encodeStart(
                registries.createSerializationContext(NbtOps.INSTANCE),
                value);
        return result.getOrThrow(error -> new IllegalStateException("Unable to encode " + description + ": " + error));
    }
}
