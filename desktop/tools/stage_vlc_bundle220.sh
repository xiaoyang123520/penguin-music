#!/usr/bin/env bash
# Stage the Windows libVLC bundle for the MV player — 2.2.0 (L4) edition.
#
# Usage: stage_vlc_bundle220.sh <extracted vlc-3.0.21 dir> <output dir>
#
# Supersedes stage_vlc_bundle.sh (2.1.1 trim). The 2.1.1 trim staged only
# the MP4-over-HTTP chain (~46MB): HLS/DASH adaptive demux, MKV and the
# standalone AV1/VP9/ASS decoders were excluded, so MV variants in those
# containers/codecs failed with a generic error. 2.2.0 restores:
#   - access: the full official set (38) — bluray/dvd/vcd/ftp/sftp/smb/
#     nfs/rtsp/rtp etc. come back; they cost little and MV URLs in the
#     wild occasionally arrive over non-http schemes.
#   - demux: the full official set (46) — notably libmkv_plugin and
#     libadaptive_plugin (HLS/DASH).
#   - codec: everything except pure encoders (x264/x26410b/x265/twolame/
#     qsv) and dead/irrelevant modules (fluidsynth MIDI synth,
#     schroedinger Dirac, zvbi teletext, sdl_image, crystalhd/dmo legacy
#     hardware helpers). libdav1d/libaom (AV1), libvpx (VP8/VP9) and
#     liblibass (ASS/SSA subtitles) are back.
# Playback-chain categories are unchanged from the 2.1.1 script.
# VLC is LGPL/GPL free software; COPYING.txt is staged alongside.
set -euo pipefail
SRC="${1:?source vlc dir required}"
OUT="${2:?output dir required}"
rm -rf "$OUT"
mkdir -p "$OUT/plugins"

cp "$SRC/libvlc.dll" "$SRC/libvlccore.dll" "$SRC/COPYING.txt" "$OUT/"

copy_cat() { # copy_cat <category> <file...>
  local cat="$1"; shift
  mkdir -p "$OUT/plugins/$cat"
  for f in "$@"; do
    cp "$SRC/plugins/$cat/$f" "$OUT/plugins/$cat/"
  done
}
copy_all_except() { # copy_all_except <category> <excluded basename...>
  local cat="$1"; shift
  mkdir -p "$OUT/plugins/$cat"
  for f in "$SRC/plugins/$cat/"*; do
    local base; base="$(basename "$f")"
    local skip=0
    for x in "$@"; do if [ "$base" = "$x" ]; then skip=1; fi; done
    if [ "$skip" = 0 ]; then cp "$f" "$OUT/plugins/$cat/"; fi
  done
}

# --- access: full official set (2.2.0 L4) ---
copy_all_except access

# --- demux: full official set incl. mkv + adaptive/HLS/DASH (2.2.0 L4) ---
copy_all_except demux

# --- codec: all decoders; pure encoders and dead modules stay out ---
copy_all_except codec \
  libx264_plugin.dll libx26410b_plugin.dll libx265_plugin.dll \
  libtwolame_plugin.dll libqsv_plugin.dll \
  libfluidsynth_plugin.dll libschroedinger_plugin.dll libzvbi_plugin.dll \
  libsdl_image_plugin.dll libcrystalhd_plugin.dll libdmo_plugin.dll

# --- playback chain categories (same as 2.1.1) ---
copy_all_except audio_filter libspatialaudio_plugin.dll
copy_all_except audio_mixer
copy_all_except audio_output
copy_all_except packetizer
copy_all_except stream_filter
copy_all_except video_chroma
copy_all_except video_filter
copy_all_except video_splitter
copy_all_except d3d11
copy_all_except d3d9
copy_all_except keystore
copy_all_except logger
copy_all_except video_output libcaca_plugin.dll libflaschen_plugin.dll

# --- misc: TLS for https + xml ---
copy_cat misc libgnutls_plugin.dll libxml_plugin.dll

echo "staged: $(du -sh "$OUT" | cut -f1) in $OUT ($(find "$OUT" -type f | wc -l) files)"
