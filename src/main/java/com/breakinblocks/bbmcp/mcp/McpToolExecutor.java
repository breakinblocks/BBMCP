package com.breakinblocks.bbmcp.mcp;

import com.google.gson.JsonObject;

public interface McpToolExecutor {
    void executeCommand(String command) throws Exception;

    JsonObject listMods() throws Exception;

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

    JsonObject lookAt(double x, double y, double z, int durationTicks) throws Exception;

    JsonObject jump() throws Exception;

    JsonObject move(String direction, int durationTicks) throws Exception;

    JsonObject interact(String target, String hand) throws Exception;

    JsonObject getActionStatus(long actionId) throws Exception;

    JsonObject cancelAction(long actionId) throws Exception;

    JsonObject recipeCapabilities() throws Exception;

    JsonObject findRecipes(String query, String recipeType, int limit) throws Exception;

    JsonObject getRecipe(String recipeId) throws Exception;

    JsonObject viewRecipe(String recipeId, String viewer, String mode) throws Exception;

    JsonObject getRecipeTree(String itemId, int maxDepth) throws Exception;

    JsonObject getItemUsages(String itemId) throws Exception;

    JsonObject getWorkstationRecipes(String machineId) throws Exception;

    JsonObject scanForLoops(String itemId, int maxDepth) throws Exception;

    JsonObject captureRecipeCard(String recipeId, boolean savePng) throws Exception;

    JsonObject dumpRecipes(String modNamespace, String recipeType, boolean saveJson) throws Exception;

    JsonObject analyzeRecipeComplexity(String itemId, boolean saveJson) throws Exception;

    JsonObject checkRecipeCycles(String itemId, boolean saveJson) throws Exception;

    JsonObject findUnderutilizedItems(String modNamespace, boolean saveJson) throws Exception;
}
