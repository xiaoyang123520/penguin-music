#!/usr/bin/env bash
# Stage a trimmed Windows libVLC bundle for the MV player (2.1.1+).
#
# Usage: stage_vlc_bundle.sh <extracted vlc-3.0.21 dir> <output dir>
#
# Source is the official VideoLAN win64 zip (vlc-3.0.21-win64.zip).
# The full plugin set is 134MB; MV playback only needs the file/http(s)
# access + MP4 demux + avcodec decode + Windows audio/video output chain,
# so we stage a documented subset (~45MB uncompressed). Excluded on purpose:
# GUI (Qt/skins), visualization, stream output / muxers, optical-disc and
# capture access, HLS/DASH adaptive demux, MKV, and pure encoder plugins
# (x264/x265/twolame). avcodec already covers H.264/AAC/VP9 decoding, so
# the standalone vpx/aom/dav1d decoder plugins are redundant here.
# VLC is LGPL/GPL free software; COPYING.txt from the official zip is
# staged alongside as the license text.
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

# --- access: file + http(s) + in-memory only ---
copy_cat access \
  libfilesystem_plugin.dll libhttp_plugin.dll libhttps_plugin.dll \
  libimem_plugin.dll libaccess_imem_plugin.dll libidummy_plugin.dll \
  libattachment_plugin.dll libtcp_plugin.dll libudp_plugin.dll \
  libtimecode_plugin.dll libaccess_concat_plugin.dll

# --- demux: MP4 is the MV format; a few generic containers as safety ---
copy_cat demux \
  libmp4_plugin.dll libplaylist_plugin.dll libes_plugin.dll \
  libh26x_plugin.dll libmpgv_plugin.dll libps_plugin.dll libts_plugin.dll \
  libavi_plugin.dll libasf_plugin.dll libogg_plugin.dll \
  libflacsys_plugin.dll libwav_plugin.dll libsubtitle_plugin.dll \
  libdirectory_demux_plugin.dll libnoseek_plugin.dll libimage_plugin.dll

# --- codec: avcodec is the decode core; keep the small native decoders ---
copy_all_except codec \
  libx264_plugin.dll libx26410b_plugin.dll libx265_plugin.dll \
  libaom_plugin.dll libdav1d_plugin.dll libvpx_plugin.dll \
  libfluidsynth_plugin.dll libschroedinger_plugin.dll libzvbi_plugin.dll \
  libsdl_image_plugin.dll liblibass_plugin.dll libqsv_plugin.dll \
  libtwolame_plugin.dll libcrystalhd_plugin.dll libdmo_plugin.dll

# --- playback chain categories (kept essentially complete) ---
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

# --- misc: TLS for https + xml (used by several modules) ---
copy_cat misc libgnutls_plugin.dll libxml_plugin.dll

echo "staged: $(du -sh "$OUT" | cut -f1) in $OUT ($(find "$OUT" -type f | wc -l) files)"
