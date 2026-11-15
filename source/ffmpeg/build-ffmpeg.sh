#!/bin/bash
# Builds the small ffmpeg.exe the DLC Loader ships: only what the converter uses.
#   - Xbox 360 sound banks: WAV-wrapped XMA2  -> demuxer wav, decoder xma2
#   - PS3 sound banks: rebuilt MP3 frames      -> demuxer mp3, parser mpegaudio, decoder mp3float/mp3
#   - output: raw 16-bit PCM file              -> encoder pcm_s16le, muxer pcm_s16le (-f s16le), protocol file
# LGPL v2.1+ build (no --enable-gpl / --enable-version3 / nonfree), static, no external libraries.
#
# Run in an MSYS2 UCRT64 shell with: pacman -S --needed make mingw-w64-ucrt-x86_64-gcc
#   bash build-ffmpeg.sh <ffmpeg-X.Y.Z.tar.xz> <output dir>
set -euo pipefail
TARBALL="$1"; OUT="$2"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
tar -xJf "$TARBALL" -C "$WORK"
cd "$WORK"/ffmpeg-*/
./configure \
  --target-os=mingw32 --arch=x86_64 \
  --disable-everything --disable-autodetect --disable-network --disable-doc --disable-debug \
  --disable-ffprobe --disable-ffplay --enable-ffmpeg --enable-small --disable-x86asm \
  --disable-avdevice --disable-swscale \
  --enable-avfilter --enable-swresample \
  --enable-protocol=file \
  --enable-demuxer=wav,mp3 --enable-parser=mpegaudio \
  --enable-decoder=xma2,mp3,mp3float \
  --enable-encoder=pcm_s16le --enable-muxer=pcm_s16le \
  --enable-filter=abuffer,abuffersink,aformat,aresample,anull,afifo \
  --extra-ldflags=-static --pkg-config-flags=--static
make -j"$(nproc)" ffmpeg.exe
mkdir -p "$OUT"
cp ffmpeg.exe "$OUT/ffmpeg.exe"
strip "$OUT/ffmpeg.exe"
cp COPYING.LGPLv2.1 "$OUT/COPYING.LGPLv2.1.txt"
./ffmpeg.exe -hide_banner -version > "$OUT/version.txt" 2>&1 || true
echo "built $OUT/ffmpeg.exe"
