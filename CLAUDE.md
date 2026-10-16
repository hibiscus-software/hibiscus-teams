# Hibiscus Teams

Player teams for NeoForge, Minecraft 26.3, Java 25: player teams, parties with ranks, invites and allies, server
teams, team chat and friendly fire, through a small GUI and a full command set (`/hibiscusteams`, alias `/teams`). It
implements Hibiscus Lib's Teams API (`software.hibiscus.hibiscuslib.api.team`); Hibiscus Quests (`hibiscusquests`)
stores progress per team through that API.

- Clean room: FTB Teams (https://github.com/FTBTeam/FTB-Teams) is a behavioural reference only. Never copy its code,
  textures or lang text.
- Package `software.hibiscus.hibiscusteams`. Built on Hibiscus Lib (`hibiscuslib_version` in `gradle.properties`);
  read Lib's `docs/API.md` and use what it has (GUI widgets, `HibiscusNetwork`, `SnbtFiles`, `PermissionLevels`,
  `Profiles`, `RateLimiter`) rather than writing your own. If Lib is missing something, change Lib in its own repo
  (bump its version; API changes only add default methods) instead of working around it here.
- Building against Lib: from GitHub Packages (needs `gpr.user` / `gpr.key` in `~/.gradle/gradle.properties`), or a
  local checkout through the `includeBuild` in `settings.gradle` (`../hibiscus-lib` by default; elsewhere pass
  `-Phibiscuslib_dir=...`).
- Layout: `team/` is the server side (`TeamData` the Team, `TeamManagerImpl` the TeamManager and saving,
  `TeamStorage` the files, `TeamOps` every change with its checks and events, `TeamSetting` the editable
  properties), `command/` the commands, `config/` the server config, `network/` the payloads, the GUI requests'
  handling (`TeamNetwork`) and the client sync (`TeamSync`, which sends each player only what changed), `client/` the
  client state (`ClientTeamState`, also the `ClientTeams` provider), config and screens, `integration/` chat (team
  chat redirect, the [TAG] on names) and the vanilla scoreboard mirror, `gametest/` the tests.
- Server-authoritative: every change goes through `TeamOps` (commands and GUI payloads alike), which checks rank,
  admin permission and rate, then changes, posts the Teams API events in Lib's documented order, saves and syncs.
  Clients never decide what a player may do.
- Teams API contract: a player team's id is the player's UUID; `teamOf` always returns a team; `extraData` is per
  mod, saved with the team, server-only; `PlayerJoinedTeamEvent` follows every change of `teamOf`. The
  `events_in_order` gametest pins the order down.
- Saving: one SNBT file per team in `<world>/hibiscusteams/`, written by `SnbtFiles` (atomic, `.bak`), debounced, and
  on server stop. A file that won't load is logged and never written over.
- Dedicated servers: no client classes on the server. Client code lives under `client/` and is only reached from
  `HibiscusTeamsClient` (`@Mod(dist = Dist.CLIENT)`).
- Every user-facing string goes through `assets/hibiscusteams/lang/en_us.json`.
- GUI: build on Hibiscus Lib's widgets and theme. Screens rebuild on every sync, so keep their state (tab, selection,
  unsaved settings) in fields, not widgets. Check every change with `./gradlew runScreenshotClient` (source set
  `src/screenshots`, never in a jar): it opens a flat creative world, sets up demo teams, screenshots every screen
  state at GUI scales 1-3 on 1280x720 and scale 4 on 1440x960 into `run/screenshotClient/screenshots/`, and quits.
  The smallest GUI to fit is 427x240 (scale 3 on 1280x720). The inventory button's sprite comes from
  `tools/gui_sprite_gen.py`.
- Comments: a short comment above each class and non-obvious method saying what it's for, as in the code already.
- License header on every Java file (the MIT block comment at the top of the existing sources).
- 26.3 names: `Identifier` (not ResourceLocation), `GuiGraphicsExtractor` with `extractRenderState` /
  `extractBackground`, `MouseButtonEvent` / `KeyEvent`, `minecraft.gui.setScreen(...)` / `minecraft.gui.screen()`,
  `InputConstants.MOUSE_BUTTON_LEFT` (= 1) and `MOUSE_BUTTON_RIGHT` (= 3). Config types are `LOCAL`, `CLIENT`,
  `SYNCED` (the server config) and `STARTUP`. When unsure, grep the decompiled sources (unzip
  `build/neoForm/neoFormJoined26.3-1/steps/patchUserDev/outputs.jar`), Hibiscus Lib or Arcforge.
- NeoForge range: the jar declares every 26.3 build from 26.3.0.0-beta (`neo_version_range`) while compiling against
  `neo_version`. To check an older build, run the already compiled classes on it in a fresh Minecraft workspace:
  `rm -rf build/neoForm` then `./gradlew runGameTestServer -x compileJava -Pneo_version=26.3.0.0-beta
  --no-configuration-cache --no-build-cache` (add `runScreenshotClient -x compileScreenshotsJava` for GUI changes).
  Otherwise the workspace made for `neo_version` can come back from the cache and the run fails with a
  NoClassDefFoundError. Something that differs between builds is looked up by name, as
  `HibiscusTeams.syncedConfigType()` does for `ModConfig.Type.SYNCED` (`SERVER` before 26.3.0.37-beta).
- Item stacks can't be made during mod construction or client setup in 26.3 (data components aren't bound yet): use
  suppliers.
- Tests: `./gradlew build runGameTestServer`. Gametests live in `gametest/` and are registered in
  `HibiscusTeamsGameTests`; Hibiscus Lib's run alongside them (`neoforge.enabledGameTestNamespaces`) and must keep
  passing.
- Changes go under `## [Unreleased]` in CHANGELOG.md as they're made.
- Releasing: follow docs/RELEASING.md. A pushed `v<mod_version>+<minecraft_version>` tag (e.g. `v1.0.0+26.3`) runs
  `.github/workflows/release.yml`: a GitHub Release with the jar, and CurseForge once `CURSEFORGE_PROJECT_ID` is set.
  The README's images are crops of the screenshot harness's scale 2 shots, in `docs/images/`; refresh them when the
  GUI changes.
- Commits and PRs: the maintainer commits, pushes and opens PRs. Don't run `git commit`, and don't add yourself as a
  co-author; hand over a one-line commit message and the PR text instead.
- Minecraft versions: one branch each; `main` is the newest, `mc/<major.minor>` the others. The same version number
  has the same changes on every branch: make a change on one branch and cherry-pick it to the others.
