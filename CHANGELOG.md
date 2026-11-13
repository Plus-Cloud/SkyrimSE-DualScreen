# Changelog

## 1.0.5

- **Fixed the bottom screen staying on "Load a save to begin" while playing** (#13). On a fresh install, the app's `SkyrimAYN` folder (where the game tells the companion what's happening) could end up in the app's own storage, where your mods live, instead of in your Skyrim folder, so the companion never saw the game. 1.0.5 moves it back the first time it opens; nothing to do.
- **Report a Problem is now in the in-game ⚙ too**, so you can send logs while the problem is happening.
- **Moved your Skyrim folder?** The app now notices and asks where it is, instead of failing when you press Play. Your mods and settings come with it. The same goes for a folder on an SD card that isn't in.

## 1.0.4

- **Fixed black screens when loading a save or starting a New Game with some mod lists** (#11). Your Skyrim folder treats `Meshes` and `meshes` as the same folder, but the app's storage, where 1.0.3 moved mods, doesn't, so mods that spelled a folder differently could end up split in two and Skyrim only saw half of the files. Animation and behaviour mods were hit hardest. Mods now always join the folder that's already there, and the first launch of 1.0.4 merges any folders that were split. Nothing to reinstall.
- **Fixed the app closing when you tapped the Effects line** on the companion's Character page (#12). The full list of active effects now opens over the companion.

## 1.0.3

- **Both screens are now one app.** The companion is part of the game's own window instead of a second app, so Back, Home and launchers treat Skyrim as a single game: **Cocoon now pauses and resumes it properly** on dual screen, and nothing on the bottom screen ever takes the controller from the game.
- **Much faster modded boots:** mods now live inside the app's own storage instead of your Skyrim folder, because Android's shared storage is slow for the thousands of small files mods add. A 28-mod setup boots in about 45 s instead of over 3 minutes.
  - **Your existing mods are moved over automatically** the first time you open 1.0.3, before you can press Play (a "Moving your mods into the app…" progress line shows; it takes seconds). Nothing to do, nothing re-downloaded.
  - Your Skyrim folder goes back to the plain game files. Mod downloads stay in `SkyrimAYN/downloads`, so if the app is ever uninstalled, reinstalling it rebuilds your mods from them.
- **Keyboard on the bottom screen:** naming your character and Nexus search type on the bottom screen, so the keyboard doesn't cover the game or the mod browser.
- **Quicksave and autosave fixed** (from the game menu and the companion).
- **Inventory:** drop items (DROP, or DROP ALL for a stack) and sort by name, weight or value from the bottom screen. Worn and quest items can't be dropped, as in the game.
- **Languages:** the app and companion now come in English, Chinese (Simplified and Traditional), Japanese, Korean, Spanish, Portuguese (Brazil), French, German, Italian, Russian and Polish. Pick yours on the first setup screen or in the companion's settings.
- **Depth-of-field blur removed:** no more blurry background.
- **FPS limit:** quick picks that run smoothly on your screen (on the Thor: 20, 24, 30, 40, 60, 120 or Unlimited), plus a Custom option with a slider.
- **Save and Quit** goes straight back to the launcher (no more 10-20 s black screen).
- **Dim with game light** (⚙, on by default): the bottom screen darkens in dark places so it doesn't glare in dungeons or at night.
- **Back:** Back on the launcher, or a quick double Back in game, sends both screens to the background together. A single Back in game opens the companion's settings.
- **Report a Problem** (launcher and companion ⚙) saves a log bundle to Download and opens a pre-filled GitHub issue to attach it to. A crash notice appears if Skyrim closes unexpectedly.
- Quitting from Skyrim's own menu now closes the game cleanly instead of freezing.
- Hidden compass and health bars no longer flash back on screen when you crouch or stand.
- The top screen no longer flips upside-down after closing the Thor's lid.
- The map works inside walled cities (Whiterun, Solitude, Windhelm, Riften, Markarth).
- File pickers in the companion (mod files, mod lists, drivers) open on the bottom screen, where you tapped.
- Fixed a rare crash when quitting a game while Steam was reconnecting (the app closed instead of returning to the launcher).
- Read-only saves are fixed automatically at launch.
- Recommended mod list is now 30 mods: Immersive Citizens - AI Overhaul and Enhanced Blood Textures were added.
- Gyro aim removed.

## 1.0.2

- **Balanced is now 40 FPS** (was 45). The Thor's screen runs at 60 or 120 Hz, so 45 FPS could never be shown evenly and juddered slightly; 40 divides 120 exactly. Players on Balanced move to 40 automatically.
- **FPS slider** (20-60) in the launcher and in game (⚙), for any frame rate you like. Values that divide your screen's refresh rate evenly are marked SMOOTH, with one-tap picks.
- **Updating from inside the app no longer crashes.** 1.0.0 and 1.0.1 were missing Android's install permission, so tapping Update closed the app. From 1.0.2 on, updates install from the banner.

## 1.0.1

- **Offline play now works.** With no internet at Play, Skyrim starts through a DRM-free path (Steam's protection removed from a copy of `SkyrimSE.exe`, the original kept beside it) and needs no connection. Online launches switch back to real Steam by themselves. Saves made offline upload to Steam Cloud on the next online launch.
- The app now checks for updates every hour instead of once a day.
- **Steam achievements:** a list in the launcher and in the companion's ⚙ (unlocked first, with dates; hidden ones stay secret; cached so it shows offline), an "Achievement unlocked" popup on the game screen and a notice on the bottom screen, with settings for the popup and its sound.

## 1.0.0 — first public release

**The app**
- Launcher on the top screen:
  - Play, with your latest save shown underneath.
  - Settings: Power (Cool, Balanced or Max), resolution, graphics, CPU translator and graphics driver.
- First-run guide: file access, your Skyrim folder, then Steam sign-in. Each step explains why it's needed.
- The game engine downloads and installs in the background while you set up, with a quiet progress line, so the first Play doesn't sit on a loading screen.
- The folder step checks your Skyrim is 1.7.104 (the current Steam version) and says how to update it if not; the companion never touches other versions.
- Steam agreement and all engine messages use the app's own look.
- The bottom-screen companion needs nothing extra: no Script Extender, no mods. (If you install SKSE for mods, the companion uses it.)
- Steam:
  - sign in once, and saves sync with Steam Cloud;
  - it reconnects by itself after a game session or when Wi-Fi returns;
  - you can play offline after your first sign-in.
- Loading screen with progress stages and tips that stays up until Skyrim is actually on screen; the Bethesda intro video is skipped.
- 60 FPS by default (Power › Max), with Balanced (45) and Cool (30) for longer, cooler sessions.
- The Low preset is tuned for the Thor: ambient occlusion and depth of field off (measured: about +3 FPS and 1.5 W less).
- Graphics › Thor (default): Low plus decals, precipitation occlusion, snow sparkles, lens flare and smoother LOD fades, all measured to cost nothing noticeable.
- Performance stats overlay (FPS, CPU/GPU load, temperatures, power), from the launcher or in game. Values are white and turn red as they get worse.
- Overheat protection (Launcher › Advanced): above 95°C for 20 s, Skyrim saves ("Overheat Save") and closes, and the launcher says why.
- Sleep freezes the game completely and it resumes by itself when you wake the Thor.
- Updates itself from GitHub releases.

**Bottom-screen companion**
- Character:
  - name, level, race and health/magicka/stamina bars;
  - left hand, shout and right hand, effects, gold and carry weight, all 18 skills;
  - a live minimap.
- Compass that mirrors the game's own every frame: the same markers, icons, fades and sizes, moving as smoothly as the game's.
- Quests: objectives, TRACK (like the journal), and SHOW ON MAP.
- Items and Magic laid out like the game's menus:
  - item cards;
  - equip spells and one-handed weapons to the left or right hand;
  - use potions and food.
- Map:
  - drawn from your game's terrain, ground textures and roads, with the game's icons;
  - quest markers and fast travel.
- Favourites quick bar along the bottom.
- The game keeps running while you use the menus (default). Pausing, like the game's own menus, can be turned on in ⚙.
- Options to hide the game's compass and health bars.

**Mods**
- Sign in to Nexus Mods inside the app; your API key is picked up automatically.
- Browse, download and install. Requirements are offered first.
- FOMOD installers, automatic load order, and safe uninstall with save backups.

**Known limits**
- Frame Generation (Lossless Scaling, with your own Lossless.dll) runs in the app's renderer: 2X shows about 90 FPS from 45 real frames, with a little extra input delay. If Skyrim ever fails to start with it, the app switches it off by itself.
- For Skyrim SE 1.7.104 on Steam only.
