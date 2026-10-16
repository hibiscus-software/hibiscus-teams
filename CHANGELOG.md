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
date. How to cut a release is in [docs/RELEASING.md](docs/RELEASING.md).

## [Unreleased]

## [1.0.1] - 2026-10-16

Needs Hibiscus Lib 1.4.1 or newer.

### Changed

- **Runs on every NeoForge 26.3 build**, from 26.3.0.0-beta to the newest; it needed 26.3.0.84 or later before.

## [1.0.0] - 2026-10-16

The first release of Hibiscus Teams, for Minecraft 26.3. Needs Hibiscus Lib 1.2.1 or newer.

### Added

- **Teams.** Every player has their own player team. Players create **parties** with an owner, officers and members;
  admins create **server teams**, which have no owner and can be locked so members can't leave. Team settings: a
  unique name, a 1-5 character tag, colour, description, an item icon, free to join, member limit, whether the team
  takes ally requests, friendly fire, and team chat by default.
- **Invites, ranks and ownership.** Invites to online or offline players, which expire; free-to-join parties and
  member limits; officers invite and remove members ranked below them; the owner promotes, demotes, hands over
  ownership and disbands (and can't leave without doing one of the last two). When an admin removes an owner, the
  party passes to the first officer, else the first member, or is deleted when it's empty.
- **Allies and enemies.** Parties ask to be allies and accept or decline; any party can mark another an enemy.
- **Friendly fire.** Members of a team can't hurt each other unless the team allows it; optionally allies are
  protected too.
- **Team chat.** `/teams msg`, and team chat as a member's default (the team's setting, or their own `/teams chat`);
  a message starting with `!` goes to everyone.
- **Tags and colours.** A member's `[TAG]` before their name in chat, and through a mirror of the teams on the vanilla
  scoreboard, in the player list and above their head, with names in the team's colour. Vanilla clients and other
  mods see the mirrored teams.
- **The Teams screen**, from a button by the inventory (with a badge for invites and ally requests, and an Open Teams
  key mapping): create, join and answer invites when you're on your own; in a team, the Members tab (faces with an
  online dot, ranks, a right-click menu, an Invite dialog), the Allies tab (requests both ways, allies, enemies) and
  the Settings tab (every setting, read-only for members; leave and a type-the-name disband). It updates live.
- **Toasts and chat messages** for invites, new members, removals, ally requests, new owners and disbanded parties,
  with clickable `[Accept]` buttons in chat. Each player picks toasts, chat or both.
- **Commands:** `/hibiscusteams` (alias `/teams`) with `party`, `allies`, `enemies`, `info`, `list`, `msg` and
  `chat` for players, and `server` and `admin` (force join and leave, rename, disband, reload, export) for admins,
  behind the permission nodes `hibiscusteams.admin.server`, `.members`, `.rename`, `.disband`, `.reload` and
  `.export` (ops by default). Translatable output, clickable buttons and names, and suggestions for every argument.
- **Saving:** one readable SNBT file per team in `<world>/hibiscusteams/`, written safely with a backup shortly after
  each change and when the server stops. A file that won't load is reported and left untouched.
- **Configuration:** `hibiscusteams-server.toml` (parties, member limit, name length, free to join, invite expiry,
  allies, friendly fire for allies, tags, team chat, the scoreboard mirror and name colours) and
  `hibiscusteams-client.toml` (toasts and chat messages).
- **The Hibiscus Lib Teams API**, implemented: `TeamsApi.get()` is Hibiscus Teams, every Teams API event is posted in
  Lib's documented order, other mods' `extraData` is saved with each team, and `ClientTeams` knows the player's team
  and every party and server team. The first time it runs in a world, the names and data Hibiscus Lib kept for each
  player are copied into their player team.
- A mod list icon and banner.

[Unreleased]: https://github.com/hibiscus-software/hibiscus-teams/compare/v1.0.1+26.3...HEAD
[1.0.1]: https://github.com/hibiscus-software/hibiscus-teams/compare/v1.0.0+26.3...v1.0.1+26.3
[1.0.0]: https://github.com/hibiscus-software/hibiscus-teams/commits/v1.0.0+26.3
