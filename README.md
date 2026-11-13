# Outbound Map Companion

An Android app for an AYN Thor (or any Android 13+ device) that shows a live map, your van and your
inventory for the game **Outbound**, running in the **Eden** emulator on the same handheld. It is meant for
the Thor's second screen. It only reads the game's save folder; it never changes anything.

No licence granted yet; personal use among friends.

## What the app does
Top bar: a **Map | Bag | Guide** switch, a status chip and a **...** menu.

- **Live map**: the region map of your current save with a "You + Van" marker (cyan with a ring = live position,
  pink = taken from the save, orange = van). Pinch, double-tap or the round +/- buttons to zoom (up to 8x), drag to pan, and a
  crosshair button to keep yourself centred ("follow"). Zoom, pan and follow are remembered per region.
- **Status chip**: green "Live" (position changed in the last 5 s), amber "Paused - last seen ..." (the game stopped writing), or
  grey "Saved N min ago" (no live position; "You" comes from the save).
- **Van**: drawn where the save says, or, with the live-position cheat, where you last drove it.
- **Destination pin**: the place you track in the game is shown as a gold diamond with its name, an arrow at the screen edge when it
  is off-screen, and a "Tracking: name - distance" chip under the bar (tap it to frame you and the destination).
- **Map icons**: campfires, signal towers (with a tick if used), supply caches (brighter when they hold items) and points of interest.
  Tap an icon for an info chip. Each kind can be switched on or off in Settings.
- **Bag**: Backpack, Tools and Van storage as lists with quantities.
- **Guide tab**: notes per region and per place (what it is, what to do, what you need, rewards, tips, a confidence dot and the source
  sites). "Show on map" jumps to the place on the map.
- **Settings**: marker size, labels, van marker, tracking chip, zoom-button side, keep screen on, start in follow mode, and the map-icon switches.
  Menu also has Refresh, Change folder, Calibrate map and Debug info.

## What is deliberately NOT in this repository
- The **save key** (the game encrypts its saves; you derive the key from your own copy of the game).
- The game's **artwork and maps** (you export the four map images from your own copy).
- Any **game files** (dumps, executables, metadata, asset bundles, keys).

Everything has to come from a game and a console you own. The tools in `tools/` show how to get them.

## Requirements
- A legally obtained copy of Outbound (base game dump, and the v1.2.0 update if you want the live-position cheat) and your own `prod.keys`.
- An AYN Thor (or other Android 13+ device) with the Eden emulator and Outbound installed; USB debugging enabled.
- A computer with macOS or Linux (on Windows use WSL, or build with Android Studio and set the same variables), with:
  JDK 17, the Android SDK (platform `android-37.0`, build-tools `36.0.0`, `ANDROID_HOME` set), `adb`, and Python 3
  (3.14+ or `pip install zstandard`).
- Internet access on the computer for the first Gradle build (downloads dependencies).

## Setup, step by step
All commands run from the repository folder. Use `work/` (git-ignored) or a folder outside the repo for game files and tool output.

1. **Dump your own game.** Use your own console to get the Outbound base game and its `prod.keys` (how is not covered here).
   Keep the base (v0) package as an `.nsp`/`.nsz`.
2. **Install the Python tools.** `python3 -m pip install -r tools/requirements.txt`
3. **Extract the files you need from the base package.** List first, then extract:

       python3 tools/extract_romfs.py list    prod.keys game-base.nsz global-metadata
       python3 tools/extract_romfs.py list    prod.keys game-base.nsz bundle
       python3 tools/extract_romfs.py extract prod.keys game-base.nsz work/romfs global-metadata.dat default_assets_all_

   The folder structure of the package is kept under `work/romfs/`. The tools refuse to overwrite existing output unless you pass `--force`. The tools read `prod.keys` but never print it.
4. **Derive the save key** (from the base game's `Data/Managed/Metadata/global-metadata.dat`):

       python3 tools/derive_key.py work/romfs/<path>/global-metadata.dat secrets/outbound.key

   It checks that the file is the right version, writes 32 bytes with mode 600 and never prints the key. `secrets/` is git-ignored.
   Optional check: add `--verify <one of your save files>` to confirm the key decrypts it (needs `cryptography`; prints only a pass/fail line).
5. **Export the map images:**

       python3 tools/export_maps.py work/romfs/<path>/default_assets_all_<hash>.bundle app/src/main/assets/maps

   This must create `map-pacific-coast.png`, `map-starting-level.png`, `map-canyons.png`, `map-mountains.png`.
   (If a bundle does not contain them the script says so; try the other `default_assets_all_*.bundle`.)
6. **Build:**

       export ANDROID_HOME=/path/to/android-sdk     # if not already set
       scripts/build.sh

   It checks JDK 17, `ANDROID_HOME`, the key file (`OUTBOUND_KEY_FILE`, default `./secrets/outbound.key`) and the map images, then runs
   `./gradlew :core:test :app:assembleDebug`. The APK is `app/build/outputs/apk/debug/app-debug.apk`.
7. **Install and launch:** connect the handheld by USB and run `scripts/install.sh`. It installs with `adb install -r`
   (set `ANDROID_SERIAL` if several devices are connected), looks for the secondary display and starts the app there. If it cannot find
   it, set `OUTBOUND_DISPLAY_ID=<id>` (see `adb shell dumpsys display`); otherwise it launches normally.
8. **Grant the folder.** On first start tap "Grant access to Eden's folder". In the picker open `nand > user > save > 0000000000000000`,
   then **your own profile folder** (a long hex name; its name differs per user), then `0100ED9024EB8000` > `persistentDataPath`, and
   confirm. Only a read permission is taken. Granting `Savegames` also works but hides live position. Never pick the emulator's top folder or
   anything above the game's title folder (`0100ED9024EB8000`): the app now refuses those (they would expose other games and your console keys).
   The save must exist, so play and save once first.
9. **Optional: live position.** See `eden/README.md` (needs the v1.2.0 update, build id `9C6124686BE4AFFA`).

### Pin it in the Cocoon launcher
Find "Outbound Map" in the launcher's app list and add it to the launcher's second-screen / favourites area.
Menu names differ between launcher versions and were not checked against a current Cocoon release; if your launcher cannot
start it on the second screen, use `scripts/install.sh` to launch it there.

## Troubleshooting
- **The folder picker does not open at the right place or cannot find your folder.** The picker is only asked to start in
  `nand/user/save/0000000000000000`. Navigate from there to your profile folder > `0100ED9024EB8000` > `persistentDataPath`.
  Tap "..." > Change folder to pick again.
- **"Savegames folder not found inside the granted folder".** You picked the wrong folder or the game has never saved; pick `persistentDataPath` after saving once.
- **Build fails naming OUTBOUND_KEY_FILE.** The key file is missing or not exactly 32 bytes (step 4).
- **No live position.** Check: the cheat file is in the right Eden folder and ticked in Add-ons; `livepos.bin` exists in `persistentDataPath`;
  the folder grant is `persistentDataPath` or the title folder (not `Savegames`); your game build id is `9C6124686BE4AFFA`; the game was restarted after enabling the cheat.
  Menu > Debug info shows the state of the live file (found / not reachable / not found / stale / read failed).
- **Wrong build id.** The cheat only fits build `9C6124686BE4AFFA`. On another build it may do nothing or crash the game; turn it off.
- **Map marker is in the wrong place or rotated.** Menu > Calibrate map > "Next variant" cycles the 8 orientation variants (swap/flip of x and z). Stop when
  "You" sits at the right spot; Done saves the choice per region. Default orientation and the 1500 x 1500 world-unit map size are not verified for every region.
- **Debug info** (menu) lists region, feature counts, tracked id, file, timestamps, position source, live-file state, folder and warnings.

## Layout
- `core/`: pure Kotlin/JVM logic and JUnit tests: save decoder (Base64 + AES-256-CBC), save model, live-position and van rules,
  map calibration, POI / feature / guide tables, resources `pois.json`, `features.json`, `guides.json`.
- `app/`: one Activity with plain Android Views (`MainActivity`, `MapView`, `GuidePanel`, `SafSaveSource`, ...). The key is injected at build time from `OUTBOUND_KEY_FILE`.
- `scripts/`: `build.sh`, `install.sh`.
- `tools/`: Python helpers to get the key, map images and data from your own game files (`derive_key.py`, `extract_romfs.py`, `extract_main.py`,
  `export_maps.py`, `poi_positions.py`, `map_features.py`, `gen_live.py`).
- `eden/`: the optional live-position cheat and how to install it.

## Environment variables
- `OUTBOUND_KEY_FILE` (build, required for every Gradle run): path to the 32-byte key; default in `scripts/build.sh` is `./secrets/outbound.key`.
- `ANDROID_HOME` (build): Android SDK. `ANDROID_SERIAL`, `OUTBOUND_DISPLAY_ID`, `ADB` (install script).
- `OUTBOUND_SAMPLE_JSON`, `OUTBOUND_SAMPLE_SAVE` (tests, optional): your own decrypted save JSON and encrypted save. The tests that need them are skipped when unset.
- `OUTBOUND_REQUIRE_SAMPLES=1` (tests, opt-in): a missing sample variable fails the test instead of skipping it.

## Versions (pinned)
Gradle 9.7.0 (wrapper), Android Gradle Plugin 9.3.1 (needs JDK 17, build-tools 36.0.0, max API 37), Kotlin 2.4.21, kotlinx-serialization-json 1.13.0,
JUnit 4.13.2; compileSdk / targetSdk 37, minSdk 33.

## Tests
`scripts/build.sh :core:test` (or `./gradlew :core:test` with `OUTBOUND_KEY_FILE` set). Tests that need a real save are skipped unless the sample variables are set. For the optional strict tests, put your own sample saves under `samples/` (git-ignored) and point `OUTBOUND_SAMPLE_JSON` / `OUTBOUND_SAMPLE_SAVE` at them.

## Live position details (for the curious)
With the cheat the game writes `livepos.bin` (16 bytes, little-endian: float32 x, y, z, then int32 player state; 1 = driving, 6 = passenger, 0 = on foot, all-zero x/y/z = no data).
With a grant on `persistentDataPath` (or the title folder) the app reads it read-only once per second while it is in the foreground. If a change was seen within the last
5 s the "You" marker is drawn at the live position and the chip says "Live"; when it goes quiet "You" stays at the last live position (dimmed) with the amber chip.
Van rule: while the live sample is fresh and in a vehicle the van is at the live position; the last in-vehicle position is remembered (also across app restarts)
and kept while it is newer than the save and in the same region; a newer save or a region change clears it. Without the file the app behaves as if it did not exist.

## Data in `core/src/main/resources`
- `pois.json`: 51 points of interest `{id, region, name, x, y, z}`; `features.json`: 190 campfires, towers and supply caches `{id, kind, region, x, y, z}`.
  Both were extracted from the BASE game build's scene files (see `tools/poi_positions.py`, `tools/map_features.py`). Positions on newer builds are not verified.
  Fishing spots are not included.
- `guides.json`: 43 place notes, per-region overviews and 18 general tips. The notes were written in our own words from public guides
  (an Epic Games Store article, a TrueAchievements walkthrough, KeenGamer, wikily.gg and others); the source hosts are listed per entry
  and shown in the app (host names only, not links). They may be wrong or outdated for your game version. 11 of the 51 POIs have no guide entry.

## Limitations
- Coordinate data is from the base game build; a newer build may differ.
- Guide notes may be wrong or outdated.
- Map orientation (calibration variant) and the map coverage of 1500 x 1500 world units per region are not verified for every region.
- Without the cheat (or when it is stale) the position updates only when the game saves; a region change is seen only at the next save.
- Polling (save every 15 s, live file every 1 s) runs only while the app is in the foreground.
- Item ids are shown as prettified raw ids; the player's backpack is shown raw only when non-empty.
- Fixed to the Eden emulator's folder layout (`dev.eden.eden_emulator.user`); tools and the cheat are for the specific game builds named above.
- The tools in `tools/` have only been exercised by their author on their own files. `derive_key.py --verify <save>` checks the derived key against one of your saves.

## Privacy and safety
- The app has **no INTERNET permission** and no storage permissions; it only reads the folder you grant, read-only. It does not write, create or delete anything.
- **The built APK contains your save key.** Do not give the APK to anyone and do not upload it. Share the source, not the APK.
- **The APK is a debuggable debug build and embeds the save key.** Never share or upload it. If you do not need USB debugging after installing, turn it off.
- The app refuses folder grants above the game's title folder (profile folder, `save`, `user`, `nand`, the emulator root), and drops such a grant left by an older install.
- Never commit `secrets/`, `prod.keys`, game dumps or the exported map images (all are in `.gitignore`, as are `work/` and `samples/`).
- Uninstall: `adb uninstall dev.local.outboundcompanion` (this also removes the folder grant).
