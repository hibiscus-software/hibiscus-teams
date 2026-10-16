# Changelog

All notable changes to Hibiscus Teams are listed here, newest first. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## Versioning

Versions are `MAJOR.MINOR.PATCH+MINECRAFT` (for example `1.0.0+26.3`), set by `mod_version` and `minecraft_version` in
`gradle.properties`, and each is released as the tag `vMAJOR.MINOR.PATCH+MINECRAFT`. Each Minecraft version has its own
branch and its own changelog; this one is Minecraft 26.3's (`main`). The same version number on two Minecraft versions
has the same changes.

- **PATCH** (1.0.0 → 1.0.1): bug fixes and small tweaks. Configs and saved teams carry over untouched.
- **MINOR** (1.0.0 → 1.1.0): new features, commands or settings. Saved teams carry over.
- **MAJOR** (1.x → 2.0.0): anything that breaks saved teams, configs or mods using Hibiscus Teams.

Changes land under **Unreleased** as they're made; when a version is released, that section gets its number and
date.

## [Unreleased]

### Added

- Teams: every player has a player team (its id is the player's UUID); players can create **parties** with an owner,
  officers and members, and ops can create **server teams** with no owner. Team properties: name (unique, 1-32
  characters), tag (1-5 characters), colour, description, icon, free to join, member limit, ally requests on/off,
  friendly fire, team chat by default, and for server teams, locked.
- Invites (with an expiry time), free-to-join parties, leaving, kicking (only players ranked below you), promoting and
  demoting (owner only), transferring ownership, disbanding. An owner can't leave without transferring or disbanding
  first.
- Allies (request and accept, between parties) and enemies.
- Friendly fire: players in the same team can't hurt each other unless the team allows it, optionally allies too.
- Commands, `/hibiscusteams` and its alias `/teams`: `party create|invite|join|accept|decline|leave|kick|promote|
  demote|transfer_owner|settings|disband`, `allies list|add|remove|accept|decline`, `enemies list|add|remove`,
  `info`, `list`, `msg`, `chat`, and for ops `server create|delete|add|remove|settings` and
  `admin force_join|force_leave|rename|disband|reload|export`. Messages are translatable, with clickable
  `[Accept]`-style buttons and names that show details on hover; team arguments are suggested (quoted when needed).
- Permission nodes `hibiscusteams.admin.server`, `.members`, `.rename`, `.disband`, `.reload` and `.export`, granted
  to ops (level 2) by default.
- The Hibiscus Lib Teams API: `TeamsApi.get()` is Hibiscus Teams once it loads, with every event posted in Lib's
  documented order, and `extraData` saved with each team. On the first start in a world, players' names and data kept
  by Lib's solo-team fallback are copied into their player teams.
- Saving: one SNBT file per team in `<world>/hibiscusteams/`, saved shortly after each change and when the server
  stops; a file that won't load is reported and left untouched.
- Server config `hibiscusteams-server.toml`: parties on/off, member limit, name length, free-to-join on/off, invite
  expiry, allies on/off, and whether allies count for friendly fire.
- Game tests for the team rules, the Teams API events and persistence, the fallback migration, and the commands.
- The Teams screen, opened from a button beside the inventory (with an unbound key mapping, and a badge counting
  pending invites, plus ally requests for officers). On your own: create a party (name, colour, icon from your
  inventory, free to join), or accept, decline or join from a list of your invites and open parties. In a party or
  server team: a Members tab (faces with an online dot, ranks, pending invites; right-click for promote, demote, make
  owner, remove, message; an Invite dialog for online players or any name), an Allies tab (allies, ally requests both
  ways, enemies; an Add dialog to ask a party to ally or mark it an enemy) and a Settings tab (every property, saved
  together; read-only for members, and for server teams unless you're an admin; Leave and a type-the-name Disband).
  The screen updates live as the team changes.
- Toasts for party invites, new members, being removed, ally requests, new owners and disbanded parties, and the same
  news in chat with its buttons. Each player chooses toasts and/or chat in `hibiscusteams-client.toml`.
- The client side of the Teams API (`ClientTeams`): the player's own team in full and every party and server team's
  summary, kept in sync by the server, which only sends what changed.
- A mod list icon and banner.
- Game tests for what each player is sent and for the network payloads.
- Team chat: with team chat as their default (the party's `team_chat_default`, or their own `/teams chat` or the
  screen's switch), a member's chat goes to their team; starting a message with `!` sends it to everyone.
  `chat.teamChatEnabled` turns team chat off for the whole server.
- Team tags: a member's `[TAG]`, in their team's colour, before their name (`chat.showTag`).
- The scoreboard mirror (`display.scoreboardMirror`): each party and server team has a vanilla scoreboard team
  (named `hbt_` and part of its id) holding its members, so the player list, name tags, vanilla clients and other mods
  see the teams, with the tag as the prefix and, with `display.nameTagColors`, names in the nearest vanilla team
  colour. Switching it off removes the mirrored teams and leaves the scoreboard alone.
- Game tests for the scoreboard mirror, the tag and team chat.
