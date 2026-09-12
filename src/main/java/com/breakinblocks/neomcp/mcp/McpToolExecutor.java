package com.breakinblocks.neomcp.mcp;

import com.google.gson.JsonObject;

public interface McpToolExecutor {
    void executeCommand(String command) throws Exception;

    JsonObject getPlayerInfo() throws Exception;

    JsonObject getBlockEntityData(int x, int y, int z) throws Exception;

    JsonObject inspectItemComponents() throws Exception;

    JsonObject queryRegistry(String registryName, String namespace) throws Exception;

    JsonObject readLatestLogs() throws Exception;

    JsonObject injectKubejsScript(String script) throws Exception;

    JsonObject getNearbyEntities(double x, double y, double z, double radius) throws Exception;

    JsonObject listLootTables() throws Exception;

    JsonObject getLootTable(String lootTableId) throws Exception;

    JsonObject searchLootTables(String itemId) throws Exception;

    JsonObject openQuestGui(long id, String objectType) throws Exception;

    JsonObject getChapterLayout(long chapterId) throws Exception;

    JsonObject exportChapterCanvas(long chapterId, boolean savePng) throws Exception;

    JsonObject takeScreenshot() throws Exception;
}
