# Live-position cheat for Eden (optional)

Without this, the app only knows where you are when the game saves. With it, the map follows you
about twice a second.

## READ THIS FIRST
- It is ONLY for game build id `9C6124686BE4AFFA` (Outbound v1.2.0 update, `v524288`).
- On any other build it may crash the game or do nothing. Do not use it on another version.
- It is a memory patch for your own copy of the game in the Eden emulator. It does not change saves.

## What it does
The cheat hooks the game's player update and, about twice a second, writes 16 bytes to
`persistentDataPath/livepos.bin` (in the game's save folder): the player's x, y, z (three
float32) and the player state (int32; 1 = driving the van, 6 = passenger, 0 = on foot). Nothing else in the
game is changed. The Companion app reads that file (read-only) once per second.

## Install
1. Copy `cheats/9C6124686BE4AFFA.txt` to this place in Eden's user folder (the folder that also
   contains `nand/`):

   `load/0100ED9024EB8000/Live position - map feed/cheats/9C6124686BE4AFFA.txt`

   Create the folders that do not exist. The folder name `Live position - map feed` is what shows up
   in Eden's list.
2. Create the file the cheat writes to. It must exist before the first write: make a 16-byte file of
   zeros named `livepos.bin` (on a computer: `head -c 16 /dev/zero > livepos.bin`) and put it in the
   game's `persistentDataPath` folder:

   `nand/user/save/0000000000000000/<your profile folder>/0100ED9024EB8000/persistentDataPath/livepos.bin`

   That is the same folder that contains `Savegames`. The profile folder name is different for every
   user; use the one that already contains `0100ED9024EB8000`.
3. In Eden, open Outbound's properties > Add-ons and tick `Live position - map feed`.
4. Start the game (restart it if it was running). Play for a few seconds, then in the Companion app
   check that the status chip shows "Live".
5. In the Companion app the folder grant must be `persistentDataPath`, not `Savegames`, otherwise the app
   cannot see `livepos.bin`. Never pick the emulator's top folder or anything above the game's title
   folder (`0100ED9024EB8000`); the app now refuses those.

## Generate it yourself
`tools/gen_live.py` regenerates this cheat from your own extracted `main` image (see
`tools/extract_main.py`) and refuses to run if the image is not build `9C6124686BE4AFFA`.

    python3 tools/extract_main.py prod.keys game-update.nsz work/main.bin
    python3 tools/gen_live.py work/main.bin --out work/cheat.txt

Existing output is only overwritten with `--force`.
