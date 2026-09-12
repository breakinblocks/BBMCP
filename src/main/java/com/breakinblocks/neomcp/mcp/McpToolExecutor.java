package com.breakinblocks.neomcp.mcp;

import com.google.gson.JsonObject;

public interface McpToolExecutor {
    void executeCommand(String command) throws Exception;

    JsonObject getPlayerInfo() throws Exception;

    JsonObject getBlockEntityData(int x, int y, int z) throws Exception;

    JsonObject inspectItemComponents() throws Exception;

    JsonObject queryRegistry(String registryName, String namespace) throws Exception;
}
