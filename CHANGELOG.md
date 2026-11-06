# Changelog

## 1.0.1

- **Offline play now works.** With no internet at Play, Skyrim starts through a DRM-free path (Steam's protection removed from a copy of `SkyrimSE.exe`, the original kept beside it) and needs no connection. Online launches switch back to real Steam by themselves. Saves made offline upload to Steam Cloud on the next online launch.
- The app now checks for updates every hour instead of once a day.

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
