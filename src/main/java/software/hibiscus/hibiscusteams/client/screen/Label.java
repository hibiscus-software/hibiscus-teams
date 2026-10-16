/*
 * Copyright (C), 2026 Hibiscus Software. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package software.hibiscus.hibiscusteams.client.screen;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.chat.Component;
import software.hibiscus.hibiscuslib.client.gui.widget.HibiscusWidget;

// One line of plain text, cut to its width: field labels and values. Unlike TextLayoutWidget it reads no markup, so
// names and descriptions players typed show exactly as typed. Takes no input and can't be focused.
final class Label extends HibiscusWidget {
    private final boolean muted;

    Label(int width, Component text, boolean muted) {
        super(0, 0, width, 10, text);
        this.muted = muted;
        this.active = false;
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        int color = muted ? theme().textMuted() : theme().text();
        graphics.text(font(), TeamIcons.fit(font(), getMessage(), getWidth()), getX(), getY(), color, false);
    }

    @Override
    public void playDownSound(SoundManager soundManager) {}
}
