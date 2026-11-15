Dead Space (2008) DLC Loader 1.0.1
==================================
By teeceebee (tcb-repos). Free software under the GNU General Public License v3 or later
(LICENSE.txt): use and share it freely; changed versions must keep the credit and license
and share their source.

Brings the Xbox 360 and PS3 downloadable content for Dead Space (2008) to the PC version.
It converts your console DLC files for the PC game and installs a small loader DLL so the
game loads them. The packs work as on console: the suits keep their defense bonuses, the
weapons keep their own stats, and bench upgrades to a stock weapon also apply to its DLC
versions. The DLC text comes in every language the console release had and follows the
game's language automatically.

No game files are included. You convert your own DLC downloads.


What you need
-------------
- Dead Space for PC, version 1.0.0.222 (the GOG release; the EA app / Origin exe is the same
  build). The loader DLL checks the exe when the game starts and stays off on any other
  version. The Steam exe is a different build and is not supported.
- The game in a folder whose path is 108 characters or less (for example C:\Games\Dead Space).
  This is a limit of the game itself: some of its own files are 150 characters deep, and once
  the full path passes Windows' 260-character limit the game crashes when it loads a level,
  with or without DLC. The DLC Loader warns you if the folder is too deep.
- Java 17 or newer (JAVA_HOME set, or java on PATH).
- Your DLC, in any of these forms (or the .zip files they came in):
    - Xbox 360: the DLC package files (STFS "LIVE"/"PIRS" packages).
    - PS3: the .pkg files. The European release is preferred: it has all 11 text languages,
      while the USA release has 5.


Packs
-----
Big Gun, Heavy Damage, Hot Rod, Military, Pedestrian, Speed Kills,
Astronaut, Elite, Obsidian, Scorpion and Tank (suits and weapons).
Bundles are handled: a pack that is also inside a bundle you added is converted once (for
example the Scorpion Suit and Scorpion Weapon Pack inside the Scorpion Pack).
Where packs from different releases would use the same store or slot numbers (for example the
Elite and Obsidian packs), the later one gets free numbers so both work.


How to use
----------
1. Run "DLC Loader.bat".
2. Pick your Dead Space folder (the one with Dead Space.exe).
3. Add your DLC files (Add files / Add folder, or drag them onto the list).
4. If you have both console versions of a pack, choose which one to use (Xbox 360 or PS3).
   They look the same; the Xbox 360 textures and PS3 textures are identical.
5. Click Convert.

The console sound banks are read with ffmpeg. A small ffmpeg build is included
(tools\ffmpeg, see its README.txt), so there's nothing to download.

The packs go to <game>\dlc\<pack>\ and the loader DLL is installed as <game>\dinput8.dll.
If another mod already uses dinput8.dll, the loader DLL is installed as <game>\version.dll
instead and both mods load. Only if version.dll belongs to another mod too are you asked to
replace dinput8.dll (the old file is kept as dinput8.dll.bak).

The loader works alongside DeadSpaceFixes (which uses xinput1_3.dll; tested with 1.8.0).

VR: the DLC also works with DSVR, the Dead Space VR mod (tested with DSVR 1.3). DSVR uses
dinput8.dll, so install DSVR first, then run the DLC Loader on the same game folder: it
installs the loader DLL as version.dll.

Note: DeadSpaceFixes and DSVR don't work together, so use one or the other. The DLC Loader
works with either.

The first run reads your game's own files once to build an index (tools\cache\); later runs
reuse it. Nothing in the game folder is changed apart from dlc\, the loader DLL (dinput8.dll
or version.dll) and dlcloader.log.


dlcloader.ini (optional)
------------------------
The loader works without it. Settings, in <game>\dlcloader.ini:
  [dlc]      Enabled=0    keep the loader but load no DLC
  [profile]  Dir=name     keep saves and settings in <game>\name\ instead of the usual folders
                          (not with DSVR: it writes the game's normal settings file)
  [log]      Files=1      also log the game's file access to dlcloader.log (for troubleshooting)
The loader always writes dlcloader.log in the game folder: which packs it loaded and any problems.


Removing it
-----------
Delete <game>\dlc\, the loader DLL (<game>\dinput8.dll, or <game>\version.dll if it was
installed under that name), dlcloader.log and dlcloader.ini if you made one (and
rename dinput8.dll.bak back to dinput8.dll if there is one). Saves that used DLC items may
show them missing afterwards.


Folders
-------
tools\            the DLC Loader program (DlcLoader.jar)
tools\schema\     file-format tables it uses (no game data)
tools\ffmpeg\     ffmpeg 9.0.2, small LGPL build used to read the console audio (license and source inside)
loader\           the loader DLL that gets installed in the game (as dinput8.dll or version.dll)
source\converter\ the program's Java source (javac --release 17, then jar with Main-Class DlcLoaderGui)
source\loader\    the loader DLL's source (C++, builds with Visual Studio 2022: build.bat release)
source\ffmpeg\    the script that built tools\ffmpeg\ffmpeg.exe from the FFmpeg 9.0.2 release
LICENSE.txt       the GNU General Public License v3 (this program)
THIRD-PARTY.txt   licenses for MinHook (in the loader DLL) and FFmpeg (tools\ffmpeg)
