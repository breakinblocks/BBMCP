package com.breakinblocks.neomcp.ftbquests;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.ftb.mods.ftbquests.client.ClientQuestFile;
import dev.ftb.mods.ftbquests.client.FTBQuestsClient;
import dev.ftb.mods.ftbquests.client.gui.quests.QuestPanel;
import dev.ftb.mods.ftbquests.client.gui.quests.QuestScreen;
import dev.ftb.mods.ftbquests.client.gui.quests.QuestPositionableButton;
import dev.ftb.mods.ftblibrary.ui.Widget;
import dev.ftb.mods.ftbquests.quest.BaseQuestFile;
import dev.ftb.mods.ftbquests.quest.Chapter;
import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.QuestLink;
import dev.ftb.mods.ftbquests.quest.QuestObject;
import dev.ftb.mods.ftbquests.quest.QuestObjectBase;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;

public final class FtbQuestsIntegration {
    private static final int CANVAS_BACKGROUND_COLOR = 0xFF202124;
    private static final int CANVAS_EDGE_PADDING = 2;
    private static final int MAX_CANVAS_DIMENSION = 8192;
    private static final long MAX_CANVAS_PIXELS = 16_777_216L;

    private FtbQuestsIntegration() {
    }

    public static JsonObject openQuestGui(long id, String objectType) {
        if (!FTBQuestsClient.isClientDataLoaded() || !ClientQuestFile.exists()) {
            throw new IllegalStateException("FTB Quests client data is unavailable");
        }
        BaseQuestFile file = FTBQuestsClient.getClientQuestFile();
        JsonObject result = new JsonObject();
        result.addProperty("id", QuestObjectBase.getCodeString(id));
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
                        dependencies.add(QuestObjectBase.getCodeString(dependencyId));
                        JsonObject dependencyLink = new JsonObject();
                        dependencyLink.addProperty("parent_id", QuestObjectBase.getCodeString(dependencyId));
                        dependencyLink.addProperty("child_id", QuestObjectBase.getCodeString(quest.getId()));
                        dependencyLinks.add(dependencyLink);
                    });

            JsonObject questObject = new JsonObject();
            questObject.addProperty("id", QuestObjectBase.getCodeString(quest.getId()));
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
            linkObject.addProperty("id", QuestObjectBase.getCodeString(questLink.getId()));
            linkObject.addProperty("chapter_id", QuestObjectBase.getCodeString(questLink.getParentID()));
            questLink.getQuest().ifPresent(quest ->
                    linkObject.addProperty("linked_quest_id", QuestObjectBase.getCodeString(quest.getId())));
            linkObject.addProperty("x", questLink.getX());
            linkObject.addProperty("y", questLink.getY());
            linkObject.addProperty("width", questLink.getWidth());
            linkObject.addProperty("height", questLink.getHeight());
            linkObject.addProperty("shape", questLink.getShape());
            linkedQuestData.add(linkObject);
        }

        JsonObject result = new JsonObject();
        result.addProperty("chapter_id", QuestObjectBase.getCodeString(chapter.getId()));
        result.addProperty("title", chapter.getTitle().getString());
        result.addProperty("default_quest_size", chapter.getDefaultQuestSize());
        result.addProperty("default_quest_shape", chapter.getDefaultQuestShape());
        result.addProperty("quest_count", questData.size());
        result.add("quests", questData);
        result.add("dependency_links", dependencyLinks);
        result.add("quest_links", linkedQuestData);
        return result;
    }

    public static JsonObject exportChapterCanvas(long chapterId) {
        if (!FTBQuestsClient.isClientDataLoaded() || !ClientQuestFile.exists()) {
            throw new IllegalStateException("FTB Quests client data is unavailable");
        }
        if (!RenderSystem.isOnRenderThread()) {
            throw new IllegalStateException("FTB Quests canvas export is not running on the render thread");
        }

        BaseQuestFile file = FTBQuestsClient.getClientQuestFile();
        Chapter chapter = file.getChapter(chapterId);
        if (chapter == null) {
            throw new IllegalArgumentException("FTB Quests chapter does not exist: " + chapterId);
        }

        ClientQuestFile clientFile = ClientQuestFile.INSTANCE;
        if (clientFile == null) {
            throw new IllegalStateException("FTB Quests client quest file is unavailable");
        }

        QuestScreen screen = new QuestScreen(clientFile, null);
        screen.initGui();
        screen.selectChapter(chapter);
        QuestPanelState panelState = prepareQuestPanel(screen);
        CanvasSize canvasSize = calculateCanvasSize(screen, panelState.panel());
        Minecraft minecraft = Minecraft.getInstance();
        RenderTarget mainRenderTarget = minecraft.getMainRenderTarget();
        TextureTarget canvasTarget = null;
        try {
            canvasTarget = new TextureTarget(canvasSize.width(), canvasSize.height(), true, true);
            canvasTarget.bindWrite(true);
            canvasTarget.clear(true);

            MultiBufferSource.BufferSource bufferSource = minecraft.renderBuffers().bufferSource();
            GuiGraphics graphics = new GuiGraphics(minecraft, bufferSource);
            graphics.fill(0, 0, canvasSize.width(), canvasSize.height(), CANVAS_BACKGROUND_COLOR);
            panelState.panel().draw(
                    graphics,
                    screen.getTheme(),
                    0,
                    0,
                    canvasSize.width(),
                    canvasSize.height());
            graphics.flush();

            NativeImage image = Screenshot.takeScreenshot(canvasTarget);
            try {
                byte[] png = image.asByteArray();
                JsonObject result = new JsonObject();
                result.addProperty("chapter_id", QuestObjectBase.getCodeString(chapter.getId()));
                result.addProperty("title", chapter.getTitle().getString());
                result.addProperty("width", canvasSize.width());
                result.addProperty("height", canvasSize.height());
                result.addProperty("rendered_object_count", panelState.renderedObjectCount());
                result.addProperty("quest_count", chapter.getQuests().size());
                result.addProperty("png_base64", Base64.getEncoder().encodeToString(png));
                return result;
            } catch (java.io.IOException exception) {
                throw new IllegalStateException("Unable to encode the FTB Quests canvas as PNG", exception);
            } finally {
                image.close();
            }
        } finally {
            if (canvasTarget != null) {
                canvasTarget.destroyBuffers();
            }
            mainRenderTarget.bindWrite(true);
        }
    }

    private static QuestPanelState prepareQuestPanel(QuestScreen screen) {
        QuestPanel panel = screen.questPanel;
        panel.clearWidgets();
        panel.addWidgets();
        panel.updateMinMax();
        panel.alignWidgets();
        panel.setScrollX(0.0D);
        panel.setScrollY(0.0D);
        panel.setOnlyRenderWidgetsInside(false);
        panel.setOnlyInteractWithWidgetsInside(false);

        int renderedObjectCount = 0;
        for (Widget widget : panel.getWidgets()) {
            if (widget instanceof QuestPositionableButton) {
                renderedObjectCount++;
            }
        }
        if (renderedObjectCount == 0) {
            throw new IllegalStateException("FTB Quests chapter has no renderable canvas objects");
        }
        return new QuestPanelState(panel, renderedObjectCount);
    }

    private static CanvasSize calculateCanvasSize(QuestScreen screen, QuestPanel panel) {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        int renderableObjectCount = 0;
        for (Widget widget : panel.getWidgets()) {
            if (!(widget instanceof QuestPositionableButton positionable)) {
                continue;
            }
            QuestPositionableButton.Position position = positionable.getPosition();
            validatePosition(position);
            minX = Math.min(minX, position.x() - position.w() / 2.0D);
            minY = Math.min(minY, position.y() - position.h() / 2.0D);
            maxX = Math.max(maxX, position.x() + position.w() / 2.0D);
            maxY = Math.max(maxY, position.y() + position.h() / 2.0D);
            renderableObjectCount++;
        }
        if (renderableObjectCount == 0) {
            throw new IllegalStateException("FTB Quests chapter has no renderable canvas objects");
        }

        minX -= 40.0D;
        minY -= 30.0D;
        maxX += 40.0D;
        maxY += 30.0D;
        double scale = screen.getQuestButtonSize() + screen.getQuestButtonSpacing();
        if (!Double.isFinite(scale) || scale <= 0.0D) {
            throw new IllegalStateException("FTB Quests returned an invalid quest canvas scale: " + scale);
        }
        return new CanvasSize(
                canvasDimension((maxX - minX) * scale, "width"),
                canvasDimension((maxY - minY) * scale, "height"));
    }

    private static void validatePosition(QuestPositionableButton.Position position) {
        if (!Double.isFinite(position.x()) || !Double.isFinite(position.y())
                || !Double.isFinite(position.w()) || !Double.isFinite(position.h())
                || position.w() < 0.0D || position.h() < 0.0D) {
            throw new IllegalStateException("FTB Quests returned an invalid canvas object position: " + position);
        }
    }

    private static int canvasDimension(double value, String axis) {
        if (!Double.isFinite(value) || value <= 0.0D) {
            throw new IllegalStateException("FTB Quests returned an invalid canvas " + axis + ": " + value);
        }
        double padded = Math.ceil(value) + CANVAS_EDGE_PADDING;
        if (padded > MAX_CANVAS_DIMENSION || padded > Integer.MAX_VALUE) {
            throw new IllegalStateException(
                    "FTB Quests canvas " + axis + " exceeds the safe off-screen texture limit: " + padded);
        }
        return (int) padded;
    }

    private record QuestPanelState(QuestPanel panel, int renderedObjectCount) {
    }

    private record CanvasSize(int width, int height) {
        private CanvasSize {
            long pixels = (long) width * height;
            if (pixels > MAX_CANVAS_PIXELS) {
                throw new IllegalStateException("FTB Quests canvas exceeds the safe off-screen pixel limit: " + pixels);
            }
        }
    }
}
