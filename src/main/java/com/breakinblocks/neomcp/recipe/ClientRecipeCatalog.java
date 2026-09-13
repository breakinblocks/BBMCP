package com.breakinblocks.neomcp.recipe;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;

/** Client-only entry point. It always reads the server-synchronised recipe manager. */
public final class ClientRecipeCatalog {
    private ClientRecipeCatalog() {
    }

    public static RecipeCatalog create() {
        Minecraft minecraft = Minecraft.getInstance();
        ClientPacketListener connection = minecraft.getConnection();
        if (connection == null) {
            throw new IllegalStateException("A connected client is required for recipe access");
        }
        return new RecipeCatalog(connection.getRecipeManager(), connection.registryAccess());
    }
}
