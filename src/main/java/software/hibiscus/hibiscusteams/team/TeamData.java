/*
 * Copyright (C), 2026 Hibiscus Software. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package software.hibiscus.hibiscusteams.team;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import software.hibiscus.hibiscuslib.api.team.Team;
import software.hibiscus.hibiscuslib.api.team.TeamRank;
import software.hibiscus.hibiscuslib.api.team.TeamType;

// One team on the server: a player team, a party or a server team, with its settings, members, invites, allies and
// other mods' extraData. The live object the Teams API hands out; only TeamManagerImpl and TeamOps change it.
// Saved as one SNBT file per team (see TeamStorage), in a layout meant to be readable and hand-editable.
public final class TeamData implements Team {
    // A pending invite to the team: who sent it and when it lapses (epoch millis).
    public record Invite(@Nullable UUID by, long expires) {}

    private final UUID id;
    private final TeamType type;
    private String name;
    private String tag;
    private int color;
    private String description = "";
    private @Nullable Identifier icon;
    private boolean freeToJoin;
    private int maxMembers;
    private boolean allowAllyInvites = true;
    private boolean friendlyFire;
    private boolean teamChatDefault;
    private boolean locked;
    private long created;
    // Members in join order (the owner first for a new party), so ownership can pass to the longest-serving member.
    private final LinkedHashMap<UUID, TeamRank> members = new LinkedHashMap<>();
    private final LinkedHashMap<UUID, Invite> invites = new LinkedHashMap<>();
    private final LinkedHashSet<UUID> allies = new LinkedHashSet<>();
    private final LinkedHashSet<UUID> enemies = new LinkedHashSet<>();
    // Incoming ally requests: the asking team's id and when the request lapses.
    private final LinkedHashMap<UUID, Long> allyRequests = new LinkedHashMap<>();
    // Player teams only: the player's own team chat toggle (null = follow their party's team_chat_default).
    private @Nullable Boolean teamChat;
    private final Map<Identifier, CompoundTag> extraData = new HashMap<>();
    // Set once the team has been removed, so late extraData calls get a throwaway tag (as the API documents).
    private boolean deleted;

    TeamData(UUID id, TeamType type, String name, String tag, int color) {
        this.id = id;
        this.type = type;
        this.name = name;
        this.tag = tag;
        this.color = color;
    }

    // ---- Team API ----

    @Override
    public UUID id() {
        return id;
    }

    @Override
    public TeamType type() {
        return type;
    }

    @Override
    public Component displayName() {
        return Component.literal(name).withColor(color);
    }

    // The tag for parties and server teams; a player team's is its player's name.
    @Override
    public String shortName() {
        return type.isPlayer() ? name : tag;
    }

    @Override
    public int color() {
        return color;
    }

    @Override
    public Optional<UUID> owner() {
        for (Map.Entry<UUID, TeamRank> entry : members.entrySet()) {
            if (entry.getValue() == TeamRank.OWNER) {
                return Optional.of(entry.getKey());
            }
        }
        return Optional.empty();
    }

    @Override
    public Map<UUID, TeamRank> members() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(members));
    }

    @Override
    public Set<UUID> allies() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(allies));
    }

    @Override
    public TeamRank rankOf(UUID player) {
        TeamRank rank = members.get(player);
        if (rank != null) {
            return rank;
        }
        if (hasInvite(player, System.currentTimeMillis())) {
            return TeamRank.INVITED;
        }
        if (type.isPlayer() || !TeamManagerImpl.INSTANCE.isRunning()) {
            return TeamRank.NONE;
        }
        UUID theirs = TeamManagerImpl.INSTANCE.teamOf(player).id();
        if (allies.contains(theirs)) {
            return TeamRank.ALLY;
        }
        if (enemies.contains(theirs)) {
            return TeamRank.ENEMY;
        }
        return TeamRank.NONE;
    }

    @Override
    public List<ServerPlayer> onlineMembers(MinecraftServer server) {
        List<ServerPlayer> online = new ArrayList<>();
        for (UUID member : members.keySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(member);
            if (player != null) {
                online.add(player);
            }
        }
        return online;
    }

    @Override
    public CompoundTag extraData(Identifier owningMod) {
        if (deleted) {
            return new CompoundTag();
        }
        return extraData.computeIfAbsent(owningMod, key -> new CompoundTag());
    }

    @Override
    public void markDirty() {
        if (!deleted) {
            TeamManagerImpl.INSTANCE.markDirty(this);
        }
    }

    // ---- Hibiscus Teams state (read by commands, the GUI sync and integrations) ----

    public String name() {
        return name;
    }

    public String tag() {
        return tag;
    }

    public String description() {
        return description;
    }

    public Optional<Identifier> icon() {
        return Optional.ofNullable(icon);
    }

    public boolean freeToJoin() {
        return freeToJoin;
    }

    public int maxMembers() {
        return maxMembers;
    }

    public boolean allowAllyInvites() {
        return allowAllyInvites;
    }

    public boolean friendlyFire() {
        return friendlyFire;
    }

    public boolean teamChatDefault() {
        return teamChatDefault;
    }

    public boolean locked() {
        return locked;
    }

    public long created() {
        return created;
    }

    public Map<UUID, Invite> invites() {
        return Collections.unmodifiableMap(invites);
    }

    public Set<UUID> enemies() {
        return Collections.unmodifiableSet(enemies);
    }

    public Map<UUID, Long> allyRequests() {
        return Collections.unmodifiableMap(allyRequests);
    }

    public Optional<Boolean> teamChat() {
        return Optional.ofNullable(teamChat);
    }

    public boolean hasInvite(UUID player, long now) {
        Invite invite = invites.get(player);
        return invite != null && invite.expires() > now;
    }

    public boolean hasAllyRequest(UUID fromTeam, long now) {
        Long expires = allyRequests.get(fromTeam);
        return expires != null && expires > now;
    }

    public TeamRank memberRank(UUID player) {
        return members.getOrDefault(player, TeamRank.NONE);
    }

    public boolean isDeleted() {
        return deleted;
    }

    // ---- Mutators, for TeamManagerImpl and TeamOps only ----

    void setName(String name) {
        this.name = name;
    }

    void setTag(String tag) {
        this.tag = tag;
    }

    void setColor(int color) {
        this.color = color & 0xFFFFFF;
    }

    void setDescription(String description) {
        this.description = description;
    }

    void setIcon(@Nullable Identifier icon) {
        this.icon = icon;
    }

    void setFreeToJoin(boolean freeToJoin) {
        this.freeToJoin = freeToJoin;
    }

    void setMaxMembers(int maxMembers) {
        this.maxMembers = maxMembers;
    }

    void setAllowAllyInvites(boolean allowAllyInvites) {
        this.allowAllyInvites = allowAllyInvites;
    }

    void setFriendlyFire(boolean friendlyFire) {
        this.friendlyFire = friendlyFire;
    }

    void setTeamChatDefault(boolean teamChatDefault) {
        this.teamChatDefault = teamChatDefault;
    }

    void setLocked(boolean locked) {
        this.locked = locked;
    }

    void setCreated(long created) {
        this.created = created;
    }

    void setTeamChat(@Nullable Boolean teamChat) {
        this.teamChat = teamChat;
    }

    void putMember(UUID player, TeamRank rank) {
        members.put(player, rank);
    }

    void removeMember(UUID player) {
        members.remove(player);
    }

    void putInvite(UUID player, Invite invite) {
        invites.put(player, invite);
    }

    boolean removeInvite(UUID player) {
        return invites.remove(player) != null;
    }

    void addAlly(UUID team) {
        allies.add(team);
    }

    boolean removeAlly(UUID team) {
        return allies.remove(team);
    }

    void addEnemy(UUID team) {
        enemies.add(team);
    }

    boolean removeEnemy(UUID team) {
        return enemies.remove(team);
    }

    void putAllyRequest(UUID fromTeam, long expires) {
        allyRequests.put(fromTeam, expires);
    }

    boolean removeAllyRequest(UUID fromTeam) {
        return allyRequests.remove(fromTeam) != null;
    }

    Map<Identifier, CompoundTag> extraDataMap() {
        return extraData;
    }

    void markDeleted() {
        deleted = true;
    }

    // Drops invites and ally requests that have lapsed; true if anything went.
    boolean pruneExpired(long now) {
        boolean changed = invites.values().removeIf(invite -> invite.expires() <= now);
        changed |= allyRequests.values().removeIf(expires -> expires <= now);
        return changed;
    }

    // ---- SNBT ----

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", id.toString());
        tag.putString("type", type.name().toLowerCase(Locale.ROOT));
        tag.putString("name", name);
        if (!type.isPlayer()) {
            tag.putString("tag", this.tag);
            tag.putString("color", String.format(Locale.ROOT, "#%06X", color));
            tag.putString("description", description);
            if (icon != null) {
                tag.putString("icon", icon.toString());
            }
            CompoundTag settings = new CompoundTag();
            settings.putBoolean("free_to_join", freeToJoin);
            settings.putInt("max_members", maxMembers);
            settings.putBoolean("allow_ally_invites", allowAllyInvites);
            settings.putBoolean("friendly_fire", friendlyFire);
            settings.putBoolean("team_chat_default", teamChatDefault);
            if (type == TeamType.SERVER) {
                settings.putBoolean("locked", locked);
            }
            tag.put("settings", settings);
        }
        tag.putLong("created", created);

        ListTag memberList = new ListTag();
        members.forEach((player, rank) -> {
            CompoundTag member = new CompoundTag();
            member.putString("id", player.toString());
            member.putString("rank", rank.name().toLowerCase(Locale.ROOT));
            memberList.add(member);
        });
        tag.put("members", memberList);

        if (!invites.isEmpty()) {
            ListTag inviteList = new ListTag();
            invites.forEach((player, invite) -> {
                CompoundTag entry = new CompoundTag();
                entry.putString("id", player.toString());
                if (invite.by() != null) {
                    entry.putString("by", invite.by().toString());
                }
                entry.putLong("expires", invite.expires());
                inviteList.add(entry);
            });
            tag.put("invites", inviteList);
        }
        if (!allies.isEmpty()) {
            tag.put("allies", uuidList(allies));
        }
        if (!enemies.isEmpty()) {
            tag.put("enemies", uuidList(enemies));
        }
        if (!allyRequests.isEmpty()) {
            ListTag requestList = new ListTag();
            allyRequests.forEach((team, expires) -> {
                CompoundTag entry = new CompoundTag();
                entry.putString("team", team.toString());
                entry.putLong("expires", expires);
                requestList.add(entry);
            });
            tag.put("ally_requests", requestList);
        }
        if (teamChat != null) {
            tag.putBoolean("team_chat", teamChat);
        }
        if (!extraData.isEmpty()) {
            CompoundTag data = new CompoundTag();
            extraData.forEach((key, value) -> data.put(key.toString(), value.copy()));
            tag.put("data", data);
        }
        return tag;
    }

    // Reads a saved team. Throws IllegalArgumentException for anything that can't be made sense of, so the file is
    // reported and left alone rather than half-loaded.
    static TeamData load(CompoundTag tag) {
        UUID id = UUID.fromString(tag.getStringOr("id", ""));
        TeamType type = TeamType.valueOf(tag.getStringOr("type", "").toUpperCase(Locale.ROOT));
        String name = tag.getStringOr("name", "");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("missing name");
        }
        TeamData team = new TeamData(id, type, name, tag.getStringOr("tag", name), parseColor(tag.getStringOr("color", "#FFFFFF")));
        team.description = tag.getStringOr("description", "");
        String icon = tag.getStringOr("icon", "");
        team.icon = icon.isEmpty() ? null : Identifier.parse(icon);
        CompoundTag settings = tag.getCompoundOrEmpty("settings");
        team.freeToJoin = settings.getBooleanOr("free_to_join", false);
        team.maxMembers = Math.max(0, settings.getIntOr("max_members", 0));
        team.allowAllyInvites = settings.getBooleanOr("allow_ally_invites", true);
        team.friendlyFire = settings.getBooleanOr("friendly_fire", false);
        team.teamChatDefault = settings.getBooleanOr("team_chat_default", false);
        team.locked = settings.getBooleanOr("locked", false);
        team.created = tag.getLongOr("created", 0L);

        for (CompoundTag member : tag.getListOrEmpty("members").compoundStream().toList()) {
            TeamRank rank = TeamRank.valueOf(member.getStringOr("rank", "member").toUpperCase(Locale.ROOT));
            if (!rank.isMember()) {
                throw new IllegalArgumentException("members can only have rank member, officer or owner, not " + rank);
            }
            team.members.put(UUID.fromString(member.getStringOr("id", "")), rank);
        }
        for (CompoundTag invite : tag.getListOrEmpty("invites").compoundStream().toList()) {
            String by = invite.getStringOr("by", "");
            team.invites.put(UUID.fromString(invite.getStringOr("id", "")),
                    new Invite(by.isEmpty() ? null : UUID.fromString(by), invite.getLongOr("expires", 0L)));
        }
        readUuids(tag.getListOrEmpty("allies"), team.allies);
        readUuids(tag.getListOrEmpty("enemies"), team.enemies);
        for (CompoundTag request : tag.getListOrEmpty("ally_requests").compoundStream().toList()) {
            team.allyRequests.put(UUID.fromString(request.getStringOr("team", "")), request.getLongOr("expires", 0L));
        }
        if (tag.contains("team_chat")) {
            team.teamChat = tag.getBooleanOr("team_chat", false);
        }
        CompoundTag data = tag.getCompoundOrEmpty("data");
        for (String key : data.keySet()) {
            team.extraData.put(Identifier.parse(key), data.getCompoundOrEmpty(key).copy());
        }
        return team;
    }

    // "#RRGGBB" or "RRGGBB" to 0xRRGGBB.
    static int parseColor(String text) {
        String hex = text.startsWith("#") ? text.substring(1) : text;
        if (hex.length() != 6) {
            throw new IllegalArgumentException("colour must be #RRGGBB, not " + text);
        }
        return Integer.parseInt(hex, 16);
    }

    private static ListTag uuidList(Set<UUID> ids) {
        ListTag list = new ListTag();
        ids.forEach(teamId -> list.add(StringTag.valueOf(teamId.toString())));
        return list;
    }

    private static void readUuids(ListTag list, Set<UUID> into) {
        for (Tag entry : list) {
            entry.asString().ifPresent(text -> into.add(UUID.fromString(text)));
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof TeamData team && team.id.equals(id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "TeamData[" + type + " " + name + ", " + id + "]";
    }
}
