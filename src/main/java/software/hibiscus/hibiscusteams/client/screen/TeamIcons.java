/*
 * Copyright (C), 2026 Hibiscus Software. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package software.hibiscus.hibiscusteams.client.screen;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import software.hibiscus.hibiscuslib.client.gui.HibiscusTheme;
import software.hibiscus.hibiscuslib.client.gui.Icon;
import software.hibiscus.hibiscusteams.network.TeamViews;

// Icons and text bits the Teams screens share: a team's icon (its item, else a swatch of its colour), player faces
// with the online dot, item stacks for icon ids, and text cut to fit. Stacks and faces are cached, since screens are
// rebuilt on every change.
final class TeamIcons {
    private static final Map<Identifier, ItemStack> STACKS = new HashMap<>();
    private static final Map<UUID, Icon> FACES = new HashMap<>();

    private TeamIcons() {}

    static Optional<ItemStack> stack(Optional<Identifier> item) {
        return item.flatMap(id -> Optional.ofNullable(STACKS.computeIfAbsent(id,
                key -> BuiltInRegistries.ITEM.getOptional(key).map(ItemStack::new).orElse(null))));
    }

    static Icon team(TeamViews.Summary summary) {
        return stack(summary.icon()).map(Icon::item).orElseGet(() -> Icon.color(0xFF000000 | summary.color()));
    }

    static Icon face(UUID player, boolean online) {
        Icon face = FACES.computeIfAbsent(player, Icon::playerFace);
        return online ? face.withDot(HibiscusTheme.current().color(HibiscusTheme.POSITIVE)) : face;
    }

    // The team's name in its colour, followed by its tag.
    static Component name(TeamViews.Summary summary) {
        Component name = Component.literal(summary.name()).withColor(summary.color());
        if (summary.type().isPlayer()) {
            return name;
        }
        return Component.empty().append(name).append(Component.literal(" [" + summary.tag() + "]")
                .withColor(HibiscusTheme.current().textMuted()));
    }

    // Text cut to a width for one line, ending in an ellipsis when it doesn't fit.
    static FormattedCharSequence fit(Font font, Component text, int width) {
        if (font.width(text) <= width) {
            return text.getVisualOrderText();
        }
        String ellipsis = "…";
        net.minecraft.network.chat.FormattedText cut = font.substrByWidth(text, Math.max(0, width - font.width(ellipsis)));
        return Language.getInstance().getVisualOrder(net.minecraft.network.chat.FormattedText.composite(cut, net.minecraft.network.chat.FormattedText.of(ellipsis)));
    }

    static Font font() {
        return Minecraft.getInstance().font;
    }

    // "expires in 52 min" / "in 3 h" / nothing for invites that never lapse.
    static Component expiry(long expires) {
        if (expires == Long.MAX_VALUE) {
            return Component.empty();
        }
        long minutes = Math.max(1, (expires - System.currentTimeMillis()) / 60_000);
        return minutes < 120
                ? Component.translatable("screen.hibiscusteams.expires_minutes", minutes)
                : Component.translatable("screen.hibiscusteams.expires_hours", minutes / 60);
    }
}
