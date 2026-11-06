# SkyrimSE-DualScreen

The Steam copy of **The Elder Scrolls V: Skyrim Special Edition** you already own, on the **AYN Thor**, as one app:

- **Top screen:** Skyrim, plus a launcher and an in-app Nexus Mods browser.
- **Bottom screen:** a companion styled like the game's own menus:
  - character, quests (with tracking), items and magic (left or right hand);
  - a live map with fast travel, a minimap, and the game's compass with its own icons;
  - a favourites quick bar.
  - The game keeps running while you use the menus. You can turn on pausing in ⚙ if you want menus to work like the game's own.
- **Mods:** sign in to Nexus Mods, then browse, download and install inside the app. Requirements are offered and the load order is sorted for you.

<p align="center">
  <img src="docs/screenshots/character.png" width="49%" alt="Skyrim on the top screen, the character page on the bottom screen">
  <img src="docs/screenshots/map_world.png" width="49%" alt="The world map on the bottom screen">
</p>
<p align="center">
  <img src="docs/screenshots/quests.png" width="32%" alt="Quests on the bottom screen">
  <img src="docs/screenshots/items.png" width="32%" alt="Items on the bottom screen">
  <img src="docs/screenshots/magic.png" width="32%" alt="Magic on the bottom screen">
</p>

Nothing from Skyrim is included. The game files, the map and the icons all come from your own copy.

## What you need

- An **AYN Thor**.
- **Skyrim Special Edition** on your **Steam** account (version 1.7.104, the current Steam build).
- Optional: a **Nexus Mods** account (free is fine) if you want mods.

## Install

1. On your PC, find `Steam/steamapps/common/Skyrim Special Edition` and copy the whole folder to the Thor, for example to `Internal storage/SkyrimSE-DualScreen/Skyrim Special Edition`.
2. Download the latest `SkyrimSE-DualScreen-x.y.z.apk` from **Releases** and install it.
3. Open the app and follow the three steps:
   1. allow file access;
   2. choose the `Skyrim Special Edition` folder;
   3. sign in to Steam.
4. Press **Play**. The bottom-screen companion works straight away; nothing else to install.

The first time, the app downloads its game engine (needs Wi-Fi and about 3 GB free) in the background while you do the steps above. The very first Play then sets up Wine for Skyrim, which takes a minute or two; later launches reach the menu in under a minute, and the loading screen shows each step. Expect a little stutter for the first few minutes of play while the shader cache fills.

After you've signed in to Steam once, you can also play offline. The app checks for new releases itself (Launcher › Updates).

## Tips

- **Power** in the launcher: **Max** (60 FPS, the default), **Balanced** (45 FPS, noticeably cooler) or **Cool** (30 FPS, coolest and longest battery).
- **Graphics › Thor** (default) is Skyrim's Low preset tuned for the Thor:
  - **on:** blood and impact decals, no rain under roofs, snow sparkles, lens flare, and smoother fade-in of distant objects and people;
  - **off:** the effects that cost the most for the least, namely ambient occlusion, depth of field, god-rays, reflections, big shadow maps and TAA.
- **Performance stats:** turn on in the launcher or the bottom screen's ⚙ to see FPS, CPU/GPU load and temperatures on the game screen. Values are white and turn red as they get worse.
- **Overheat protection** (Launcher › Advanced, on by default): if the Thor stays above 95°C, Skyrim saves ("Overheat Save") and closes so it can cool down.
- **Frame generation:** copy `Lossless.dll` from your own Lossless Scaling (Steam, on your PC) to the Thor, then add it in Launcher › Performance › Lossless Scaling DLL and set Frame Gen to 2X. Expect about 90 FPS on screen from about 45 real frames, with a little extra input delay.
- **Sleep:** press the power button any time. Skyrim freezes (no CPU use, no heat) and carries on when you wake the Thor.
- **Game HUD:** hide the game's compass or health bars from the bottom screen's ⚙ if you'd rather use the companion.
- **Mods:** Skyrim's own menu only lists Creations. Your mods are in the launcher's Mod Manager, and in ⚙ › "Mods loaded by the game".

## What's inside

- **Windows runtime** from [GameNative](https://github.com/utkarshdalal/GameNative): Wine/Proton, FEX/Box64, DXVK and Turnip.
- **Native Steam:** no Windows Steam runs, and saves sync with Steam Cloud.
- **`SkyrimAYN.dll`:** shares live game state with the bottom screen ([source](native/)). Our own `dinput8.dll` loads it into Skyrim, so it doesn't need the Script Extender. If you install SKSE for mods (the mod manager offers it when a mod needs it), SKSE loads it instead.
- **Mod manager:** Nexus Mods API, FOMOD installers, requirements, safe uninstall with backups, and automatic load order.

## Building

`./gradlew :app:assembleModernRelease-signed` needs your own keystore in `app/keystores/keystore.properties`. The plugin and loader are built from `native/` (see `native/README.md`) and copied into `app/src/main/assets/skyrimayn/skse/`.

## Licence and credits

- This app is a fork of GameNative and, like GameNative, licensed under **GPL-3.0**. See `LICENSE` and `THIRD_PARTY_NOTICES`. The original GameNative readme is in `docs/GAMENATIVE_README.md`.
- **SKSE64** is © the SKSE team. It isn't included or needed. If you want it for mods, the app gets it from its official Nexus page. The plugin is built against SKSE's published source, which is why its source is public.
- **Skyrim** is © Bethesda Softworks. This project is not affiliated with or endorsed by Bethesda, ZeniMax, Valve, Nexus Mods or AYN.
