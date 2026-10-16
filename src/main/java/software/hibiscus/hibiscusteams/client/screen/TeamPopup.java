/*
 * Copyright (C), 2026 Hibiscus Software. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package software.hibiscus.hibiscusteams.client.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import software.hibiscus.hibiscuslib.client.HibiscusClientNetwork;
import software.hibiscus.hibiscuslib.client.gui.HibiscusTheme;
import software.hibiscus.hibiscuslib.client.gui.popup.Popup;

// Base for the Teams screen's dialogs: a titled panel with an accent rule, like Hibiscus Lib's ConfirmDialog. Keeps
// its state in fields, since a popup's widgets are rebuilt whenever the screen is (on resize and on every team change).
public abstract class TeamPopup extends Popup {
    protected static final int PADDING = 8;
    // Where widgets start under the title.
    protected static final int TOP = 22;

    private final Component title;

    protected TeamPopup(Component title, int width, int height) {
        super(width, height);
        this.title = title;
    }

    protected static Component t(String key, Object... args) {
        return Component.translatable("screen.hibiscusteams." + key, args);
    }

    protected static void send(CustomPacketPayload payload) {
        HibiscusClientNetwork.sendToServer(payload);
    }

    protected int innerWidth() {
        return getWidth() - PADDING * 2;
    }

    protected Minecraft minecraft() {
        return Minecraft.getInstance();
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractWidgetRenderState(graphics, mouseX, mouseY, a);
        HibiscusTheme theme = HibiscusTheme.current();
        graphics.fill(getX() + 2, getY() + 2, getRight() - 2, getY() + 3, theme.accent());
        graphics.text(minecraft().font, TeamIcons.fit(minecraft().font, title, innerWidth()), getX() + PADDING, getY() + PADDING, theme.text(), false);
        extractExtra(graphics, mouseX, mouseY, a);
    }

    // Anything else the dialog draws over its widgets.
    protected void extractExtra(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {}
}
