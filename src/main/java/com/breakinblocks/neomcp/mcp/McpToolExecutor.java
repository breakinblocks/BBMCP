package com.breakinblocks.neomcp.mcp;

import com.google.gson.JsonObject;

public interface McpToolExecutor {
    void executeCommand(String command) throws Exception;

    JsonObject getPlayerInfo() throws Exception;
}
