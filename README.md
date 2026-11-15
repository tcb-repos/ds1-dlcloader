# Dead Space DLC Loader

Bring the Xbox 360 and PlayStation 3 downloadable content for **Dead Space (2008)** to the PC version.

The PC release never got the console DLC. The DLC Loader converts your own console DLC files into the PC format and adds a small loader DLL that hands them to the game's DLC system. The suits and weapons then show up at the Store and work as they did on console.

**No game files are included.** You convert DLC you own.

By **teeceebee (tcb-repos)**. Free software under the GNU GPL v3 or later.

---

## What works

- **All 11 console packs** (see the table below), from either the Xbox 360 or the PS3 release.
- **Suits** with their defense bonuses.
- **Weapons** with their own stats (damage, fire rate, clip size and so on), not just new skins.
- **Bench upgrades:** upgrades bought for a stock weapon also apply to its DLC versions, and the DLC weapon's own bonuses stack on top, as on console.
- **Store:** every item appears at the Store, with its icon, name and description.
- **Languages:** the DLC text comes in all 11 languages the console release had, and follows the game's language automatically.
- **Sound:** each weapon keeps its own sounds. Where a DLC sound is the same recording as one in the PC game, the PC's original-quality version is used.
- **Both consoles' versions together:** the Elite pack (Xbox 360) and the Obsidian pack (PS3) shared store and slot numbers on console. The DLC Loader moves one of them to free numbers so both can be installed at once.

## The original DLC

The packs were released on 13 November 2008 on Xbox Live and the European PlayStation Store, and on 20 November 2008 on the PlayStation Store in other regions.

| Pack | Contents | Xbox 360 | PS3 |
|---|---|:---:|:---:|
| Astronaut Pack | Astronaut Suit + Plasma Cutter, Pulse Rifle, Ripper | ✅ | ✅ |
| Big Gun Pack | Steam-punk Force Gun | ✅ | ✅ |
| Heavy Damage Pack | Plasma Cutter, Contact Beam, Pulse Rifle, Ripper (more damage) | ✅ | ✅ |
| Hot Rod Pack | Flamethrower, Pulse Rifle | ✅ | ✅ |
| Military Pack | All 7 weapons | ✅ | ✅ |
| Pedestrian Pack | Contact Beam, Plasma Cutter, Ripper | ✅ | ✅ |
| Speed Kills Pack | Force Gun, Line Gun, Plasma Cutter (faster) | ✅ | ✅ |
| Tank Pack | Level 5 suit + Flamethrower, Force Gun, Line Gun (Unitology markings) | ✅ | ✅ |
| Scorpion Pack | Level 5 suit + Plasma Cutter, Line Gun, Force Gun (red) | ✅ | ✅ |
| Elite Pack | Level 5 suit + Line Gun, Plasma Cutter, Pulse Rifle (Xbox 360 colors) | ✅ | ❌ |
| Obsidian Pack | Level 5 suit + Line Gun, Plasma Cutter, Pulse Rifle (jet black) | ❌ | ✅ |

The Scorpion, Elite and Obsidian packs were also sold as a separate **Suit** and a separate **Weapon Pack**. The Scorpion Suit was also a pre-order bonus. If you add both a full pack and its parts, the DLC Loader converts the full pack once and skips the parts.

**Which console's files to use:** the Xbox 360 and PS3 versions look the same, and their textures are identical. For the PS3, use the European release if you can: it has all 11 text languages, while the USA release has 5.

## Requirements

- **Dead Space for PC, version 1.0.0.222.** This is the GOG release. The EA app / Origin exe is the same build. The loader DLL checks the exe when the game starts and stays off on any other version. **The Steam exe is a different build and isn't supported yet.**
- **A short game folder path, 108 characters or less** (for example `C:\Games\Dead Space`). This is a limit of the game itself: some of its own files sit 150 characters deep, and once a full path passes Windows' 260-character limit, the game crashes while loading a level, with or without DLC. The DLC Loader warns you if the folder is too deep.
- **Java 17 or newer** ([Adoptium Temurin](https://adoptium.net/) is free).
- **Your DLC files**, in any of these forms, or the .zip files they came in:
  - **Xbox 360:** the DLC package files (STFS "LIVE"/"PIRS" packages).
  - **PS3:** the `.pkg` files.

ffmpeg, used to read the console audio, is included, so there's nothing else to download.

## How to use

1. Download the latest release and unzip it anywhere.
2. Run **`DLC Loader.bat`**.
3. Pick your Dead Space folder (the one with `Dead Space.exe`).
4. Add your DLC files (Add files / Add folder, or drag them onto the list).
5. If you have both console versions of a pack, choose which one to use.
6. Click **Convert**.

The packs go to `<game>\dlc\<pack>\`, and the loader DLL is installed as `<game>\dinput8.dll` (or as `version.dll`, see Compatibility). Start the game normally; the DLC items are at the Store.

The first run reads the game's own files once to build an index, which later runs reuse.

### Compatibility

- **[DeadSpaceFixes](https://github.com/seamusduncmcgrath/DeadSpace2008Fixes)** works alongside the DLC Loader (it uses `xinput1_3.dll`). Tested with 1.8.0.
- **DSVR (Dead Space VR mod)** works with the DLC Loader: the DLC suits and weapons work in VR (tested with DSVR 1.3). DSVR uses `dinput8.dll` itself, so the DLC Loader installs its loader DLL as `version.dll` instead. Install DSVR first, then run the DLC Loader on the same game folder.
- **Note:** DeadSpaceFixes and DSVR don't work together, so use one or the other. The DLC Loader works with either.
- **Another `dinput8.dll` mod:** the same applies. The loader DLL goes in as `version.dll`, and both mods load. Only if `version.dll` is taken by another mod as well does the DLC Loader ask to replace `dinput8.dll`, keeping the old file as `dinput8.dll.bak`.

### Removing it

Delete `<game>\dlc\`, the loader DLL (`<game>\dinput8.dll`, or `<game>\version.dll` if it was installed under that name) and `<game>\dlcloader.log`, plus `dlcloader.ini` if you made one. Rename `dinput8.dll.bak` back to `dinput8.dll` if there is one. Saves that used DLC items may show them missing afterwards.

## Troubleshooting

- **The game crashes when a level loads:** check the game folder's path length (see Requirements). This happens without the DLC too.
- **No DLC at the Store:** check `<game>\dlcloader.log`. It lists the packs the loader found, and says if the exe version isn't supported.
- **`dlcloader.ini`** (optional, in the game folder):
  - `[dlc] Enabled=0` keeps the loader DLL but loads no DLC.
  - `[profile] Dir=name` keeps saves and settings in `<game>\name\`. Don't use it with DSVR, which writes the game's normal settings file.
  - `[log] Files=1` logs more detail to `dlcloader.log`.

## How it works

- **The converter** (Java) reads the console packages (Xbox 360 STFS and PS3 `.pkg`) and converts every resource in the console `.str` streams to the PC format:
  - meshes, materials and textures
  - object data (SimGroups) and model descriptions
  - sound banks: Xbox 360 XMA and PS3 EALayer3 audio, decoded and re-encoded as the PC's EA-XAS
  - text tables

  It also renumbers store and slot IDs when packs clash. Format details come from comparing the console files with the PC game's own data.
- **The loader DLL** (C++, loaded by the game as `dinput8.dll` or `version.dll`, and passing those calls on to Windows) feeds each pack's `.store` file to the PC exe's built-in DLC parser. The game's own DLC stream manager then loads the pack's streams. It also adds a safety check to the exe's weapon-removal code, for when a DLC weapon is sold or removed from the inventory.

## Building from source

- **Converter:** Java 17+. `javac --release 17 -d out source/converter/*.java`, then `jar --create --file DlcLoader.jar --main-class DlcLoaderGui -C out .`
- **Loader DLL:** Visual Studio 2022 (MSVC x86). Run `source\loader\build.bat release`.
- **ffmpeg:** `source/ffmpeg/build-ffmpeg.sh` in an MSYS2 UCRT64 shell, using the FFmpeg 9.0.2 source (attached to each release).

## License and credits

- **DLC Loader:** Copyright (C) 2026 teeceebee (tcb-repos). Licensed under the [GNU General Public License v3 or later](LICENSE.txt). You may use, share and change it; if you share a changed version, keep the credit and license, and share its source under the same license.
- **[MinHook](https://github.com/TsudaKageyu/minhook)** (in the loader DLL): BSD 2-Clause, Copyright (C) 2009-2017 Tsuda Kageyu.
- **[FFmpeg](https://ffmpeg.org)** (`tools\ffmpeg\ffmpeg.exe`): a minimal LGPL v2.1+ build of FFmpeg 9.0.2. Its license and source details are in `tools\ffmpeg\`, and the matching source tarball is attached to each release.
- **DeadSpaceFixes** by seamusduncmcgrath is a separate project. It's mentioned here only for compatibility.

Dead Space is a trademark of Electronic Arts Inc. This project is not affiliated with or endorsed by Electronic Arts. The DLC content belongs to its owners; the DLC Loader only converts copies you own.
