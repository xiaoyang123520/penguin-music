#!/usr/bin/env bash
# Desktop build pipeline (no Gradle: the sandbox kills Gradle daemons, and
# Java cannot use the egress proxy — dependencies were fetched with curl by
# tools/resolve.py into desktop/libs/).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
D="$ROOT/desktop"
export JAVA_HOME="$ROOT/tools-dl/jdk17"
KC="$ROOT/tools-dl/kotlinc-2.1.20/bin/kotlinc"
LIBS_CP="$(ls "$D"/libs/*.jar | tr '\n' ':')"
mkdir -p "$D/build/gen" "$D/build/classes"

step() { echo "=== $* ==="; }

step "gen strings"
python3 "$D/tools/gen_strings.py" "$ROOT/app/src/main/res/values/strings.xml" "$D/build/gen"

step "gen proto (protoc -> build/gen/com; required before compile proto)"
# The generated proto sources are NOT committed; regenerate them from the
# .proto files in the repo. protoc 29.1 was used for the shipped build
# (matches protobuf-kotlin-lite 4.29.1). Put a protoc binary at
# tools-dl/protoc/bin/protoc or have one on PATH.
PROTOC="$(command -v protoc || true)"
if [ -z "$PROTOC" ] && [ -x "$ROOT/tools-dl/protoc/bin/protoc" ]; then
  PROTOC="$ROOT/tools-dl/protoc/bin/protoc"
fi
if [ -z "$PROTOC" ]; then
  echo "ERROR: protoc not found (PATH or tools-dl/protoc/bin/protoc); cannot generate proto sources" >&2
  exit 1
fi
"$PROTOC" -I "$ROOT/app/src/main/proto" \
  --java_out=lite:"$D/build/gen" --kotlin_out=lite:"$D/build/gen" \
  "$ROOT/app/src/main/proto/favorite_song_ids.proto" \
  "$ROOT/app/src/main/proto/search_history.proto"
if [ ! -f "$D/build/gen/com/jussicodes/music/FavoriteSongIds.java" ]; then
  echo "ERROR: proto generation produced no output under build/gen/com" >&2
  exit 1
fi

step "compile proto (kotlinc for .kt, javac for .java — kotlinc emits no Java classes)"
PROTO_DIR="$D/build/proto-classes"
rm -rf "$PROTO_DIR" && mkdir -p "$PROTO_DIR"
"$KC" $(find "$D/build/gen/com" -name '*.kt') $(find "$D/build/gen/com" -name '*.java') \
  -cp "$LIBS_CP" -d "$PROTO_DIR" -jvm-target 17 -J-Xmx700m -nowarn
"$JAVA_HOME/bin/javac" -cp "$PROTO_DIR:$LIBS_CP" -d "$PROTO_DIR" \
  $(find "$D/build/gen/com" -name '*.java')
"$JAVA_HOME/bin/jar" cf "$D/build/proto.jar" -C "$PROTO_DIR" .

step "compile ncmapi"
"$KC" $(find "$ROOT/ncmapi/src/main/java" -name '*.kt') \
  "$D/src/main/kotlin/android/util/Log.kt" \
  -cp "$LIBS_CP" \
  -Xplugin="$ROOT/tools-dl/kotlinc-2.1.20/lib/kotlinx-serialization-compiler-plugin.jar" \
  -d "$D/build/ncmapi.jar" -jvm-target 17 -J-Xmx900m -nowarn

step "copy app sources + desktopify rewrites"
# The app sources stay pristine in app/; compilation uses a copied tree with
# a handful of mechanical, documented rewrites (see PORTING.md):
#  1. PlayerTransform: sharedElement(state = ...) -> sharedContentState
#     (JetBrains animation 1.8.0 still uses the older parameter name;
#      AndroidX 1.8 renamed it to `state`).
#  2. Coil ImageRequest.Builder(androidContext) -> PlatformContext.INSTANCE
#     (on JVM, Coil's PlatformContext is not android.content.Context).
#  3. PlayerComments comment input: add onFocusChanged registering
#     DesktopUiState.inputFocused (#5 space-key guard; app sources stay
#     pristine, the rewrite applies to the copied tree only).
SRC_APP="$D/build/src-app"
rm -rf "$SRC_APP" && mkdir -p "$SRC_APP"
cp -r "$ROOT/app/src/main/java/." "$SRC_APP/"
python3 - "$SRC_APP" <<'PYEOF'
import re, sys, pathlib
root = pathlib.Path(sys.argv[1])
# 1) sharedElement(state = ...) -> sharedContentState (all files)
for p in root.rglob("*.kt"):
    s = p.read_text()
    s2 = re.sub(r"(?m)^([ \t]*)state = rememberSharedContentState\(",
                r"\1sharedContentState = rememberSharedContentState(", s)
    if s2 != s:
        p.write_text(s2)
        print("sharedElement rewrite:", p.relative_to(root))
# 2) Coil builder context + skia bitmap adapter + allowHardware removal
for rel in ["com/jussicodes/music/ui/components/Item.kt",
            "com/jussicodes/music/ui/theme/ArtworkThemeSeed.kt"]:
    p = root / rel
    s = p.read_text()
    s = s.replace("ImageRequest.Builder(LocalContext.current)",
                  "ImageRequest.Builder(coil3.PlatformContext.INSTANCE)")
    s = s.replace("ImageRequest.Builder(context)",
                  "ImageRequest.Builder(coil3.PlatformContext.INSTANCE)")
    p.write_text(s)
seed = root / "com/jussicodes/music/ui/theme/ArtworkThemeSeed.kt"
s = seed.read_text()
s = "\n".join(line for line in s.split("\n") if "allowHardware" not in line)
s = s.replace(".let(::extractArtworkSeed)",
              ".let { extractArtworkSeed(it.toAndroidBitmap()) }")
seed.write_text(s)
# 3) PlayerComments comment input focus guard (#5): typing a space in the
#    comment box must not toggle play/pause on desktop. Inject onFocusChanged
#    registering DesktopUiState.inputFocused into the CommentInputBar text
#    field of the copied tree. Idempotent; warns (does not silently pass)
#    when the anchor is not found.
pc_rel = "com/jussicodes/music/ui/components/PlayerComments.kt"
pc = root / pc_rel
s = pc.read_text()
if "DesktopUiState.inputFocused" in s:
    print("PlayerComments focus rewrite: already applied, skipping")
else:
    fn_idx = s.find("fun CommentInputBar")
    anchor = "modifier = Modifier.weight(1f),"
    idx = s.find(anchor, fn_idx if fn_idx >= 0 else 0)
    if idx < 0:
        print("WARNING: PlayerComments focus rewrite anchor not found in "
              + pc_rel + "; comment input space-guard NOT applied",
              file=sys.stderr)
    else:
        s = (s[:idx]
             + "modifier = Modifier.weight(1f)\n"
             + "                .onFocusChanged { DesktopUiState.inputFocused.value = it.isFocused },"
             + s[idx + len(anchor):])
        if "import androidx.compose.ui.focus.onFocusChanged" not in s:
            s = s.replace(
                "import androidx.compose.ui.Modifier",
                "import androidx.compose.ui.Modifier\n"
                "import androidx.compose.ui.focus.onFocusChanged",
                1,
            )
        if "import com.jussicodes.music.desktop.ui.DesktopUiState" not in s:
            s = s.replace(
                "import com.jussicodes.music.LocalPlayerState",
                "import com.jussicodes.music.LocalPlayerState\n"
                "import com.jussicodes.music.desktop.ui.DesktopUiState",
                1,
            )
        pc.write_text(s)
        print("PlayerComments focus rewrite:", pc_rel)
print("desktopify rewrites applied")
PYEOF

step "compile app + desktop"
EXCL='MainActivity\.kt|JetMeloApp\.kt|/di/|PlaybackService\.kt|PlayerController\.kt|playback/audio/|DesktopLyricService\.kt|DesktopLyricManager\.kt|UpdateDownloadService\.kt|AppUpdateManager\.kt|DataStoreUtils\.kt|ImageSaveUtils\.kt|CacheManager\.kt|FavoriteSongIdsSerializer\.kt|SearchHistorySerializer\.kt|ui/screen/LoginScreen\.kt|LxPluginEngine\.kt|AppVisibilityTracker\.kt|ui/screen/MainScreen\.kt|ui/screen/PlaylistScreen\.kt|ui/screen/AlbumScreen\.kt|ui/screen/ArtistScreen\.kt|ui/screen/SearchScreen\.kt|ui/screen/SettingsScreen\.kt|ui/navigation/NavGraph\.kt|SongListUtil\.kt'
APP_SRCS=$(find "$SRC_APP" -name '*.kt' | grep -vE "$EXCL")
DESKTOP_SRCS=$(find "$D/src/main/kotlin" -name '*.kt' ! -name 'SmokeMain.kt')
GEN_SRCS=$(find "$D/build/gen" -maxdepth 1 -name 'R.kt' -o -maxdepth 1 -name 'Strings.kt')
"$KC" $APP_SRCS $DESKTOP_SRCS $GEN_SRCS \
  -cp "$D/build/ncmapi.jar:$D/build/proto.jar:$LIBS_CP:$D/libs-compile/jcefmaven-132.3.1.jar" \
  -Xplugin="$ROOT/tools-dl/kotlinc-2.1.20/lib/compose-compiler-plugin.jar" \
  -Xplugin="$ROOT/tools-dl/kotlinc-2.1.20/lib/kotlinx-serialization-compiler-plugin.jar" \
  -d "$D/build/app.jar" -jvm-target 17 -J-Xmx1500m 2>&1 | tee "$D/build/compile-app.log" | grep -E "error:" | head -80 || true
if [ -f "$D/build/app.jar" ]; then echo "APP_JAR_OK $(du -h "$D/build/app.jar" | cut -f1)"; fi
