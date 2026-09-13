package com.breakinblocks.bbmcp.recipe;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;

/** Client-only entry point. It always reads the server-synchronised recipe manager. */
public final class ClientRecipeCatalog {
    private static ClientPacketListener cachedConnection;
    private static RecipeCatalog cachedCatalog;

    private ClientRecipeCatalog() {
    }

    public static synchronized RecipeCatalog create() {
        Minecraft minecraft = Minecraft.getInstance();
        ClientPacketListener connection = minecraft.getConnection();
        if (connection == null) {
            clear();
            throw new IllegalStateException("A connected client is required for recipe access");
        }
        if (connection != cachedConnection) {
            cachedConnection = connection;
            cachedCatalog = new RecipeCatalog(connection.getRecipeManager(), connection.registryAccess());
        }
        return cachedCatalog;
    }

    public static synchronized void clear() {
        cachedConnection = null;
        cachedCatalog = null;
    }
}
