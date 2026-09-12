package com.breakinblocks.neomcp.ftbquests;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import dev.ftb.mods.ftbquests.client.ClientQuestFile;
import dev.ftb.mods.ftbquests.client.FTBQuestsClient;
import dev.ftb.mods.ftbquests.client.gui.quests.QuestScreen;
import dev.ftb.mods.ftbquests.quest.BaseQuestFile;
import dev.ftb.mods.ftbquests.quest.Chapter;
import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.QuestLink;
import dev.ftb.mods.ftbquests.quest.QuestObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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

    public static JsonObject getChapterLayout(long chapterId) {
        if (!FTBQuestsClient.isClientDataLoaded() || !ClientQuestFile.exists()) {
            throw new IllegalStateException("FTB Quests client data is unavailable");
        }
        BaseQuestFile file = FTBQuestsClient.getClientQuestFile();
        Chapter chapter = file.getChapter(chapterId);
        if (chapter == null) {
            throw new IllegalArgumentException("FTB Quests chapter does not exist: " + chapterId);
        }

        List<Quest> quests = new ArrayList<>(chapter.getQuests());
        quests.sort(Comparator.comparingLong(Quest::getId));
        JsonArray questData = new JsonArray();
        JsonArray dependencyLinks = new JsonArray();
        for (Quest quest : quests) {
            JsonArray dependencies = new JsonArray();
            quest.streamDependencies()
                    .map(QuestObject::getId)
                    .distinct()
                    .sorted()
                    .forEach(dependencyId -> {
                        dependencies.add(dependencyId);
                        JsonObject dependencyLink = new JsonObject();
                        dependencyLink.addProperty("parent_id", dependencyId);
                        dependencyLink.addProperty("child_id", quest.getId());
                        dependencyLinks.add(dependencyLink);
                    });

            JsonObject questObject = new JsonObject();
            questObject.addProperty("id", quest.getId());
            questObject.addProperty("title", quest.getTitle().getString());
            questObject.addProperty("x", quest.getX());
            questObject.addProperty("y", quest.getY());
            questObject.addProperty("width", quest.getWidth());
            questObject.addProperty("height", quest.getHeight());
            questObject.addProperty("size", quest.getSize());
            questObject.addProperty("shape", quest.getShape());
            questObject.add("dependencies", dependencies);
            questData.add(questObject);
        }

        List<QuestLink> questLinks = new ArrayList<>(chapter.getQuestLinks());
        questLinks.sort(Comparator.comparingLong(QuestLink::getId));
        JsonArray linkedQuestData = new JsonArray();
        for (QuestLink questLink : questLinks) {
            JsonObject linkObject = new JsonObject();
            linkObject.addProperty("id", questLink.getId());
            linkObject.addProperty("chapter_id", questLink.getParentID());
            questLink.getQuest().ifPresent(quest -> linkObject.addProperty("linked_quest_id", quest.getId()));
            linkObject.addProperty("x", questLink.getX());
            linkObject.addProperty("y", questLink.getY());
            linkObject.addProperty("width", questLink.getWidth());
            linkObject.addProperty("height", questLink.getHeight());
            linkObject.addProperty("shape", questLink.getShape());
            linkedQuestData.add(linkObject);
        }

        JsonObject result = new JsonObject();
        result.addProperty("chapter_id", chapter.getId());
        result.addProperty("title", chapter.getTitle().getString());
        result.addProperty("default_quest_size", chapter.getDefaultQuestSize());
        result.addProperty("default_quest_shape", chapter.getDefaultQuestShape());
        result.addProperty("quest_count", questData.size());
        result.add("quests", questData);
        result.add("dependency_links", dependencyLinks);
        result.add("quest_links", linkedQuestData);
        return result;
    }
}
