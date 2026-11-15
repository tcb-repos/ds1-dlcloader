ffmpeg 9.0.2 - small build for the Dead Space DLC Loader
========================================================

ffmpeg.exe here is FFmpeg (https://ffmpeg.org), built for the DLC Loader with only the
parts it needs: reading Xbox 360 XMA2 and PS3 MP3 audio and writing raw PCM.

License: GNU Lesser General Public License version 2.1 or later (COPYING.LGPLv2.1.txt).
No GPL or nonfree parts are included.

Source code: the unmodified FFmpeg 9.0.2 release,
  https://ffmpeg.org/releases/ffmpeg-9.0.2.tar.xz
  SHA-256 8c3850283eb25fa026482078a04051e0be17347b09ef81a0849bec15a96e002e
  (signed with the FFmpeg release key, fingerprint FCF9 86EA 15E6 E293 A564 4F10 B432 2F04 D676 58D8)
How it was built: source\ffmpeg\build-ffmpeg.sh (MSYS2 UCRT64, gcc 16.2.0, static).

The DLC Loader runs ffmpeg.exe as a separate program. You can replace it with any other
ffmpeg.exe that has the xma2 and mp3 decoders.
