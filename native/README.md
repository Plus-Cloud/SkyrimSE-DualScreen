# Native parts (Windows side)

What runs inside Skyrim. Both are prebuilt into `app/src/main/assets/skyrimayn/skse/`:

- `plugin/`: `SkyrimAYN.dll`. It shares live game state with the bottom screen and runs its commands, such as equip, track, save and HUD. The link format is in `shared-memory.md`. It runs either way:
  - **with SKSE:** as an ordinary SKSE plugin, using SKSE's task interface;
  - **without SKSE:** our `dinput8.dll` loads it, and it hooks the same two game calls SKSE hooks (`BSTaskPool::ProcessTasks` and `UIManager::ProcessCommands`, at Skyrim 1.7.104's addresses).
- `proxy/`: `dinput8.dll`. Skyrim imports `DINPUT8.dll`, and Wine uses the copy in the game folder first (`WINEDLLOVERRIDES=dinput8=n,b`). The proxy loads `Data/SKSE/Plugins/SkyrimAYN.dll` unless SKSE is running, and forwards every DirectInput call to the real DLL.

## Building (Windows, Visual Studio 2022 Build Tools, x64)

The plugin compiles against **SKSE64 2.3.1's source** (its game definitions for Skyrim 1.7.104). SKSE's licence requires plugins that include its source to publish their own source, which is this folder. You'll need:

1. [ianpatt/skse64](https://github.com/ianpatt/skse64), tag `v2.3.1`;
2. [ianpatt/common](https://github.com/ianpatt/common), built and installed somewhere.

Then build:

```bat
cmake -G Ninja -B build -S plugin -DCMAKE_BUILD_TYPE=Release -DSKSE_SRC=<skse64 checkout> -DCMAKE_PREFIX_PATH=<common install>
cmake --build build --target SkyrimAYN
proxy\build.bat
```

Copy `SkyrimAYN.dll` to `app/src/main/assets/skyrimayn/skse/Data/SKSE/Plugins/`, and `dinput8.dll` to `app/src/main/assets/skyrimayn/skse/`. Bump `PLUGIN_VERSION` in `SkyrimEngine.kt` when the plugin changes.

No SKSE files are included or redistributed.
