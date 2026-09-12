package com.breakinblocks.neomcp.ftbquests;

import com.google.gson.JsonObject;
import dev.ftb.mods.ftbquests.client.ClientQuestFile;
import dev.ftb.mods.ftbquests.client.FTBQuestsClient;
import dev.ftb.mods.ftbquests.client.gui.quests.QuestScreen;
import dev.ftb.mods.ftbquests.quest.BaseQuestFile;
import dev.ftb.mods.ftbquests.quest.Chapter;
import dev.ftb.mods.ftbquests.quest.Quest;

public final class FtbQuestsIntegration {
    private FtbQuestsIntegration() {
    }

    public static JsonObject openQuestGui(long id, String objectType) {
        if (!FTBQuestsClient.isClientDataLoaded() || !ClientQuestFile.exists()) {
            throw new IllegalStateException("FTB Quests client data is unavailable");
        }
        BaseQuestFile file = FTBQuestsClient.getClientQuestFile();
        JsonObject result = new JsonObject();
        result.addProperty("id", id);
        result.addProperty("type", objectType);

        if ("chapter".equals(objectType)) {
            Chapter chapter = file.getChapter(id);
            if (chapter == null) {
                throw new IllegalArgumentException("FTB Quests chapter does not exist: " + id);
            }
            QuestScreen screen = ClientQuestFile.openGui();
            if (screen == null) {
                throw new IllegalStateException("FTB Quests did not open a quest screen");
            }
            screen.selectChapter(chapter);
        } else if ("quest".equals(objectType)) {
            Quest quest = file.getQuest(id);
            if (quest == null) {
                throw new IllegalArgumentException("FTB Quests quest does not exist: " + id);
            }
            QuestScreen screen = ClientQuestFile.openGui(quest, false);
            if (screen == null) {
                throw new IllegalStateException("FTB Quests did not open a quest screen");
            }
        } else {
            throw new IllegalArgumentException("Unsupported FTB Quests object type: " + objectType);
        }

        result.addProperty("opened", true);
        return result;
    }
}
