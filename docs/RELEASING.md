# Releasing

A release is a pushed `vX.Y.Z+MC` tag, where `X.Y.Z` is `mod_version` and `MC` is `minecraft_version` in
`gradle.properties` (for example `v1.0.0+26.3`). `.github/workflows/release.yml` builds that commit, runs the game
tests (Hibiscus Teams' and Hibiscus Lib's) and then:

1. publishes a GitHub Release, "Hibiscus Teams X.Y.Z for Minecraft MC", with the mod jar and the version's section of
   `CHANGELOG.md` as its notes;
2. in its `curseforge` job, uploads the jar and the same notes to CurseForge.

`build.yml` runs the same build and game tests on every push, so a release shouldn't be the first place a failure
shows up.

## Minecraft versions

Each supported Minecraft version has its own branch, and releases for it are tagged from that branch:

| Branch | Minecraft | Tags |
|---|---|---|
| `main` | 26.3 (the newest) | `vX.Y.Z+26.3` |

- `main` is always the newest Minecraft version; every other supported version gets a branch `mc/<major.minor>`
  when `main` moves on.
- Each branch has its own `gradle.properties` (Minecraft, NeoForge and Hibiscus Lib versions), its own `CHANGELOG.md`
  and its own code for whatever the Minecraft versions do differently.
- A version number means the same changes on every Minecraft version: 1.1.0 for one Minecraft version has what 1.1.0
  for another has. A version that only ships on some branches simply isn't released on the others.
- Make a change on the branch it's for (usually `main`), then carry it to the others with `git cherry-pick`, fixing
  whatever the other Minecraft version needs. Keep version-specific code small and in one place.
- Only `main`'s full releases are marked "Latest" on GitHub.
- Each branch depends on Hibiscus Lib's builds for the same Minecraft version (`hibiscuslib_version=X.Y.Z+26.3` on
  `main`).

## Hibiscus Lib

Hibiscus Teams is built against the Hibiscus Lib release in `hibiscuslib_version` and requires at least that version
in `neoforge.mods.toml` (`versionRange` of the `hibiscuslib` dependency). When Teams starts using something from a
newer Lib, release Lib first, then raise both together. The release workflow downloads Lib from GitHub Packages with
the `HIBISCUS_PACKAGES_TOKEN` organisation secret (a classic token with `read:packages`), falling back to its own
token; a `401` or `403` from `maven.pkg.github.com` means the secret is missing or expired (see Hibiscus Lib's
`docs/DEPENDING.md`).

Release builds must use the published Lib: don't pass `-Phibiscuslib_dir` or keep a local Lib in `mavenLocal` when
building a jar to ship by hand.

## Cutting a release

Do this on the branch for the Minecraft version you're releasing.

1. Pick the version from the rules at the top of `CHANGELOG.md` (the Unreleased section suggests one).
2. In `CHANGELOG.md`:
   - rename `## [Unreleased]` to `## [X.Y.Z] - YYYY-MM-DD` and delete its "Suggested version" line;
   - add a new, empty `## [Unreleased]` above it;
   - at the bottom, point `[Unreleased]` at `https://github.com/hibiscus-software/hibiscus-teams/compare/vX.Y.Z+MC...HEAD`,
     and add `[X.Y.Z]: https://github.com/hibiscus-software/hibiscus-teams/compare/vPREVIOUS...vX.Y.Z+MC`, where
     `vPREVIOUS` is the previous tag on this branch (for the branch's first release, such as 1.0.0 on `main`,
     `[X.Y.Z]: https://github.com/hibiscus-software/hibiscus-teams/commits/vX.Y.Z+MC`).
3. Set `mod_version=X.Y.Z` in `gradle.properties`.
4. Check it all before tagging:

   ```sh
   bash .github/scripts/release-notes.sh X.Y.Z MC   # the same checks release.yml runs; writes release-notes.md
   ./gradlew build runGameTestServer
   ./gradlew runScreenshotClient                   # look over the GUI at every scale; refresh docs/images if it changed
   ```

   `build/libs` should then have `hibiscusteams-X.Y.Z+MC.jar`.

5. Commit ("Release X.Y.Z for Minecraft MC"), then tag and push the branch and the tag:

   ```sh
   git tag -a vX.Y.Z+MC -m "Hibiscus Teams X.Y.Z for Minecraft MC"
   git push origin <branch> vX.Y.Z+MC
   ```

6. Watch the Release run on the Actions tab. When it's green, the release is on the Releases page with the jar, and
   (once CurseForge has approved it) on CurseForge.

A tag with a pre-release part (`v1.1.0-beta.1+26.3`, with `mod_version=1.1.0-beta.1`) is published as a pre-release
(and a beta file on CurseForge).

## CurseForge

The upload is `.github/scripts/curseforge_upload.py`. It sends the jar and the version's changelog section, the display
name "Hibiscus Teams X.Y.Z+MC", the game versions (the branch's Minecraft version, NeoForge, Java 25, Client and
Server), Hibiscus Lib as a required dependency, and the file type: release, or beta / alpha for a pre-release.

It needs:

- the project's numeric id in the `CURSEFORGE_PROJECT_ID` repository **variable** (Settings → Secrets and variables →
  Actions → Variables). Until it's set the `curseforge` job is skipped, so releases work before the CurseForge project
  exists;
- Hibiscus Lib's CurseForge project slug in the `CURSEFORGE_REQUIRED` repository **variable**, so the file lists it as
  a required dependency (CurseForge refuses the upload if the slug doesn't exist; while the variable is empty, no
  dependency is listed);
- a CurseForge upload API token in the `CURSEFORGE_TOKEN` repository **secret**. Make the token at
  authors.curseforge.com (account settings, API tokens), then run
  `gh secret set CURSEFORGE_TOKEN -R hibiscus-software/hibiscus-teams` and paste it in. Don't commit it or put it
  anywhere else.

To check the metadata without uploading, run the script with `--dry-run` and the token in `CURSEFORGE_TOKEN` (the
arguments are in release.yml and at the top of the script).

## If the release run fails

If only the `curseforge` job failed (a bad token, a wrong slug, CurseForge down, an unknown game version), the GitHub
Release is already out: fix the cause and use **Re-run failed jobs** on the run.

If the run failed before **Publish GitHub Release**, the tag is pushed but nothing is published. Fix the problem,
commit, then move the tag:

```sh
git push origin :refs/tags/vX.Y.Z+MC
git tag -f -a vX.Y.Z+MC -m "Hibiscus Teams X.Y.Z for Minecraft MC"
git push origin vX.Y.Z+MC
```

If it failed after the GitHub Release was made, delete the release (and its tag) on GitHub and push the tag again, or
publish the fix as a new version.
