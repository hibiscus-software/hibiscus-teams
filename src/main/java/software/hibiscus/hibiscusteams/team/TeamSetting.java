/*
 * Copyright (C), 2026 Hibiscus Software. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package software.hibiscus.hibiscusteams.team;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import software.hibiscus.hibiscuslib.api.team.TeamType;
import software.hibiscus.hibiscuslib.api.team.event.TeamPropertiesChangedEvent.Property;
import software.hibiscus.hibiscusteams.config.TeamsConfig;

// The team properties players and admins can read and set, by the key commands use (party settings <key> [value]):
// how to show each one, parse and check a new value, and which Teams API Property a change counts as.
public enum TeamSetting {
    NAME("name", Property.DISPLAY_NAME),
    TAG("tag", Property.SHORT_NAME),
    COLOR("color", Property.COLOR),
    DESCRIPTION("description", Property.OTHER),
    ICON("icon", Property.OTHER),
    FREE_TO_JOIN("free_to_join", Property.OTHER),
    MAX_MEMBERS("max_members", Property.OTHER),
    ALLOW_ALLY_INVITES("allow_ally_invites", Property.OTHER),
    FRIENDLY_FIRE("friendly_fire", Property.OTHER),
    TEAM_CHAT_DEFAULT("team_chat_default", Property.OTHER),
    // Server teams only: members can't leave on their own.
    LOCKED("locked", Property.OTHER);

    public static final int TAG_MAX_LENGTH = 5;
    public static final int DESCRIPTION_MAX_LENGTH = 256;
    public static final int MAX_MEMBERS_LIMIT = 1000;
    // No spaces or formatting codes, as the Teams API's shortName promises.
    private static final Pattern TAG_PATTERN = Pattern.compile("[^\\s§]{1," + TAG_MAX_LENGTH + "}");

    private final String key;
    private final Property property;

    TeamSetting(String key, Property property) {
        this.key = key;
        this.property = property;
    }

    public String key() {
        return key;
    }

    public Property property() {
        return property;
    }

    public boolean appliesTo(TeamType type) {
        return switch (type) {
            case PLAYER -> false;
            case PARTY -> this != LOCKED;
            case SERVER -> true;
        };
    }

    public static Optional<TeamSetting> byKey(String key) {
        String wanted = key.toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(setting -> setting.key.equals(wanted)).findFirst();
    }

    public static List<TeamSetting> forType(TeamType type) {
        return Arrays.stream(values()).filter(setting -> setting.appliesTo(type)).toList();
    }

    // The current value, as a player would type it.
    public String get(TeamData team) {
        return switch (this) {
            case NAME -> team.name();
            case TAG -> team.tag();
            case COLOR -> TeamColors.hex(team.color());
            case DESCRIPTION -> team.description();
            case ICON -> team.icon().map(Identifier::toString).orElse("none");
            case FREE_TO_JOIN -> Boolean.toString(team.freeToJoin());
            case MAX_MEMBERS -> Integer.toString(team.maxMembers());
            case ALLOW_ALLY_INVITES -> Boolean.toString(team.allowAllyInvites());
            case FRIENDLY_FIRE -> Boolean.toString(team.friendlyFire());
            case TEAM_CHAT_DEFAULT -> Boolean.toString(team.teamChatDefault());
            case LOCKED -> Boolean.toString(team.locked());
        };
    }

    // Values worth suggesting when typing this setting.
    public List<String> suggestions() {
        return switch (this) {
            case NAME, TAG, DESCRIPTION -> List.of();
            case COLOR -> Arrays.stream(TeamColors.PRESETS).mapToObj(TeamColors::hex).toList();
            case ICON -> {
                List<String> items = new ArrayList<>();
                items.add("none");
                BuiltInRegistries.ITEM.keySet().forEach(id -> items.add(id.toString()));
                yield items;
            }
            case MAX_MEMBERS -> List.of("0", "2", "4", "8", "16");
            case FREE_TO_JOIN, ALLOW_ALLY_INVITES, FRIENDLY_FIRE, TEAM_CHAT_DEFAULT, LOCKED -> List.of("true", "false");
        };
    }

    // Checks a typed value and applies it. Throws TeamException (with nothing changed) if it isn't valid. Returns
    // false if the setting already had that value.
    boolean set(TeamData team, String raw) {
        String value = raw.strip();
        String before = get(team);
        switch (this) {
            case NAME -> team.setName(TeamOps.checkName(value, team));
            case TAG -> team.setTag(checkTag(value));
            case COLOR -> {
                try {
                    team.setColor(TeamData.parseColor(value));
                } catch (IllegalArgumentException e) {
                    throw TeamException.of("invalid_color", value);
                }
            }
            case DESCRIPTION -> {
                if (value.length() > DESCRIPTION_MAX_LENGTH || value.indexOf('§') >= 0) {
                    throw TeamException.of("invalid_description", DESCRIPTION_MAX_LENGTH);
                }
                team.setDescription(value);
            }
            case ICON -> {
                if (value.equalsIgnoreCase("none")) {
                    team.setIcon(null);
                } else {
                    Identifier id = Identifier.tryParse(value);
                    if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
                        throw TeamException.of("invalid_item", value);
                    }
                    team.setIcon(id);
                }
            }
            case FREE_TO_JOIN -> {
                boolean free = parseBoolean(value);
                if (free && !TeamsConfig.PARTIES_ALLOW_FREE_TO_JOIN.getAsBoolean()) {
                    throw TeamException.of("free_to_join_disabled");
                }
                team.setFreeToJoin(free);
            }
            case MAX_MEMBERS -> {
                int max;
                try {
                    max = Integer.parseInt(value);
                } catch (NumberFormatException e) {
                    throw TeamException.of("invalid_number", value);
                }
                int cap = TeamsConfig.PARTIES_MAX_MEMBERS.getAsInt();
                int limit = cap > 0 ? cap : MAX_MEMBERS_LIMIT;
                if (max < 0 || max > limit) {
                    throw TeamException.of("invalid_max_members", limit);
                }
                team.setMaxMembers(max);
            }
            case ALLOW_ALLY_INVITES -> team.setAllowAllyInvites(parseBoolean(value));
            case FRIENDLY_FIRE -> team.setFriendlyFire(parseBoolean(value));
            case TEAM_CHAT_DEFAULT -> team.setTeamChatDefault(parseBoolean(value));
            case LOCKED -> team.setLocked(parseBoolean(value));
        }
        return !before.equals(get(team));
    }

    static String checkTag(String value) {
        if (!TAG_PATTERN.matcher(value).matches()) {
            throw TeamException.of("invalid_tag", TAG_MAX_LENGTH);
        }
        return value;
    }

    // A tag made from a name: its letters and digits, up to five, upper-cased ("Red Dragons" gives "REDDR").
    static String defaultTag(String name) {
        StringBuilder tag = new StringBuilder();
        name.codePoints().filter(Character::isLetterOrDigit).limit(TAG_MAX_LENGTH).forEach(tag::appendCodePoint);
        return tag.isEmpty() ? "TEAM" : tag.toString().toUpperCase(Locale.ROOT);
    }

    private static boolean parseBoolean(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "true", "on", "yes" -> true;
            case "false", "off", "no" -> false;
            default -> throw TeamException.of("invalid_boolean", value);
        };
    }
}
