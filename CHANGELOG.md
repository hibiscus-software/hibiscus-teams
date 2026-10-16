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
