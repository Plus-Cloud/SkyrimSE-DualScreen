# Releasing SkyrimSE-DualScreen

The in-app updater reads `https://api.github.com/repos/<owner>/<repo>/releases/latest`. That means:

- the release must be a normal one, not a draft or pre-release;
- the tag is `vX.Y.Z`;
- exactly one `.apk` is attached;
- the release body is shown in the app as the "what's new" text.

## One-time setup

1. ~~Set `Updater.REPO`~~: done, it's `plus-cloud/SkyrimSE-DualScreen`.
2. **Back up `app/keystores/`**, which holds the release key and its password, somewhere off this PC. Every future update must be signed with the same key; if it's lost, users have to uninstall and reinstall, losing app data but not their Skyrim folder.
3. Decide the app ID before the first public release. It must stay exactly 14 characters (closed engine libraries hard-code the length). Current: `com.skyrim.ayn`. Alternative: `com.skyrimdual`.
4. Create the GitHub repo `plus-cloud/SkyrimSE-DualScreen` and push this repo. The plugin and loader source are in `native/`.

## Every release

1. Bump `versionCode` (always up) and `versionName` in `app/build.gradle.kts`, and `PLUGIN_VERSION` in `SkyrimEngine.kt` if the plugin changed.
2. Update `CHANGELOG.md`.
3. Build: `./gradlew :app:assembleModernRelease-signed`. Rename the APK to `SkyrimSE-DualScreen-X.Y.Z.apk`.
4. Smoke test on a Thor:
   - fresh install;
   - first-run steps;
   - Steam sign-in;
   - the companion works without SKSE (`SkyrimAYN.log` says "loading without SKSE"), and again after installing SKSE from the mod manager;
   - Play, then load a save;
   - check the compass, map, items, magic (left/right) and quests (track);
   - install one mod from Nexus;
   - quit to the launcher; Steam should come back on its own.
5. Tag `vX.Y.Z`, then create the GitHub release with the changelog section as the body and attach the APK.
6. From an older installed build, open Launcher › Updates and confirm it offers and installs the new version.

## What must never be in a release

- **SKSE files.** Its licence forbids rehosting. The app doesn't need it; players who want it for mods get it from its Nexus page through the app.
- **Any Skyrim/Bethesda files:** maps, icons, sounds or text. The app builds the map and icons from the player's own game at runtime.
- **The keystore** or `keystore.properties`. Both are Git-ignored.
