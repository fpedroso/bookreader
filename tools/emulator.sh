#!/usr/bin/env bash
# Local Android emulator for testing Book Reader without a physical device.
#
# Why this exists: a physical phone is a poor automation target because of its
# lockscreen. `screencap` on a locked device silently returns the always-on display,
# and `wm dismiss-keyguard` cannot bypass a secure lock, so any visual verification
# needs a human to unlock it. The emulator has no keyguard, so screencap and
# `input tap` work unconditionally.
#
# Machine-specific paths (SDK location, JDK, AVD name, system image) come from tools/emulator.env,
# which is gitignored; tools/emulator.env.example documents every key. Nothing here names a
# particular home directory, so a fresh clone runs on sane defaults.
#
# Usage:
#   tools/emulator.sh up            # install cmdline-tools if needed, create AVD, boot
#   tools/emulator.sh up --window   # same, but with a visible window you can interact with
#   tools/emulator.sh install       # install the built APK and inject content/*.epub fixtures
#   tools/emulator.sh shot out.png  # screencap to a file
#   tools/emulator.sh shell '<cmd>' # run a shell command on the device
#   tools/emulator.sh down          # stop the emulator
#   tools/emulator.sh status
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Configuration lives in a gitignored env file so this script carries no machine-specific paths of
# its own. EMULATOR_ENV points at another file for a one-off run.
#
# Values already exported in the environment beat the file: that is what lets CI, or a single
# `AVD_NAME=other tools/emulator.sh up`, override one value without editing anything. Sourcing
# under `set -a` would let the file clobber what the caller exported, so snapshot the exported
# environment first and put it back on top afterwards. `set -a` is still wanted because these
# values must reach the children - sdkmanager.bat and the emulator read them from their own
# environment.
ENV_FILE="${EMULATOR_ENV:-$REPO_ROOT/tools/emulator.env}"
if [ -f "$ENV_FILE" ]; then
  __caller_env="$(export -p)"
  set -a
  . "$ENV_FILE"
  set +a
  eval "$__caller_env"
  unset __caller_env
fi

# The SDK location, in order of authority: the environment, then the platform's own per-user
# install. Baking someone's home directory in here would make the script wrong on every other
# machine; point tools/emulator.env at it instead if yours lives somewhere unusual.
if [ -z "${ANDROID_HOME:-}" ]; then
  if [ -n "${ANDROID_SDK_ROOT:-}" ]; then
    ANDROID_HOME="$ANDROID_SDK_ROOT"
  elif [ -n "${LOCALAPPDATA:-}" ]; then
    ANDROID_HOME="$(cygpath -m "$LOCALAPPDATA" 2>/dev/null || echo "$LOCALAPPDATA")/Android/Sdk"
  elif [ "$(uname -s)" = "Darwin" ]; then
    ANDROID_HOME="$HOME/Library/Android/sdk"
  else
    ANDROID_HOME="$HOME/Android/Sdk"
  fi
fi

SDK_WIN="${ANDROID_HOME}"
SDK_BASH="$(cygpath -u "$SDK_WIN" 2>/dev/null || echo "$SDK_WIN")"
ADB="$SDK_WIN/platform-tools/adb.exe"
EMULATOR="$SDK_WIN/emulator/emulator.exe"
SDKMGR="$SDK_WIN/cmdline-tools/latest/bin/sdkmanager.bat"
AVDMGR="$SDK_WIN/cmdline-tools/latest/bin/avdmanager.bat"

# sdkmanager/avdmanager are launcher scripts, so bash's PATH is not enough - they consult JAVA_HOME
# directly and fail unhelpfully without it. Derive it from whichever `java` the shell would actually
# run: two dirnames up from the binary's real path is the JDK root, with the trailing bin/ and the
# launcher itself removed. Guessing at a scoop path here is what used to make this file wrong on any
# machine that did not have it.
if [ -z "${JAVA_HOME:-}" ]; then
  __java="$(command -v java 2>/dev/null || true)"
  if [ -n "$__java" ]; then
    __java="$(readlink -f "$__java" 2>/dev/null || echo "$__java")"
    JAVA_HOME="$(cygpath -w "$(dirname "$(dirname "$__java")")" 2>/dev/null || true)"
    if [ -n "$JAVA_HOME" ]; then export JAVA_HOME; fi
  fi
  unset __java
fi

AVD_NAME="${AVD_NAME:-bookreader_test}"
PKG="${PKG:-com.fpedroso.bookreader}"
# This image was already present locally, which avoids a ~1.5 GB download.
SYS_IMAGE="${SYS_IMAGE:-system-images;android-36;google_apis_playstore;x86_64}"
DEVICE_PROFILE="${DEVICE_PROFILE:-pixel_6}"

log() { printf '  %s\n' "$*"; }

# A function rather than `sed -n '2,16p'` on this file: a fixed range silently rots every time the
# header above changes.
usage() {
  cat <<'USAGE'
Local Android emulator for testing Book Reader without a physical device.

  tools/emulator.sh up            install cmdline-tools if needed, create AVD, boot
  tools/emulator.sh up --window   same, but with a visible window you can interact with
  tools/emulator.sh install       install the built APK and inject content/*.epub fixtures
  tools/emulator.sh shot out.png  screencap to a file
  tools/emulator.sh shell '<cmd>' run a shell command on the device
  tools/emulator.sh down          stop the emulator
  tools/emulator.sh status

Configuration: copy tools/emulator.env.example to tools/emulator.env (gitignored).
USAGE
}

# Only the two .bat launchers need a JDK, and only at the moment they run, so this stays quiet for
# commands like `status` that never touch them.
need_java_home() {
  [ -n "${JAVA_HOME:-}" ] && return
  echo "JAVA_HOME is unset and no 'java' is on PATH, so sdkmanager/avdmanager cannot run." >&2
  echo "Set JAVA_HOME in tools/emulator.env (see tools/emulator.env.example)." >&2
  exit 1
}

ensure_cmdline_tools() {
  if [ -x "$SDK_BASH/cmdline-tools/latest/bin/sdkmanager.bat" ]; then
    log "cmdline-tools present"
    return
  fi
  log "cmdline-tools missing - installing (avdmanager/sdkmanager live here)"
  local zip="$REPO_ROOT/build-tools/cmdline-tools.zip"
  mkdir -p "$(dirname "$zip")"
  # The version in the filename changes; ask Google's repository for the current one.
  local url
  url=$(curl -s "https://dl.google.com/android/repository/repository2-3.xml" \
        | grep -oE "commandlinetools-win-[0-9]+_latest\.zip" | sort -u | tail -1)
  [ -n "$url" ] || { echo "could not determine cmdline-tools URL" >&2; exit 1; }
  log "downloading $url"
  curl -sL -o "$zip" "https://dl.google.com/android/repository/$url"
  rm -rf "$REPO_ROOT/build-tools/ct-stage"
  mkdir -p "$REPO_ROOT/build-tools/ct-stage"
  unzip -q -o "$zip" -d "$REPO_ROOT/build-tools/ct-stage"
  mkdir -p "$SDK_BASH/cmdline-tools"
  rm -rf "$SDK_BASH/cmdline-tools/latest"
  mv "$REPO_ROOT/build-tools/ct-stage/cmdline-tools" "$SDK_BASH/cmdline-tools/latest"
  rm -rf "$REPO_ROOT/build-tools/ct-stage" "$zip"
  log "installed"
}

ensure_avd() {
  if [ -f "$HOME/.android/avd/$AVD_NAME.ini" ]; then
    log "AVD $AVD_NAME present"
    return
  fi
  need_java_home
  ensure_cmdline_tools
  log "creating AVD $AVD_NAME from $SYS_IMAGE"
  # avdmanager asks whether to create a custom hardware profile; answer no.
  echo no | "$AVDMGR" create avd -n "$AVD_NAME" -k "$SYS_IMAGE" \
    -d "$DEVICE_PROFILE" --force >/dev/null
  log "created"
}

running() {
  "$ADB" devices 2>/dev/null | grep -q "^emulator-"
}

wait_boot() {
  log "waiting for boot"
  "$ADB" wait-for-device
  local i state
  for i in $(seq 1 40); do
    state=$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r\n')
    if [ "$state" = "1" ]; then
      log "booted: $("$ADB" devices | grep emulator | head -1 | awk '{print $1}')"
      return 0
    fi
    sleep 10
  done
  echo "emulator did not finish booting" >&2
  exit 1
}

cmd_up() {
  ensure_avd
  if running; then
    log "emulator already running"
    wait_boot
    return
  fi
  local window=0
  [ "${1:-}" = "--window" ] && window=1
  # -no-window keeps automation from stealing the desktop; -gpu swiftshader_indirect is
  # the reliable software renderer when there is no window. WHPX supplies real
  # acceleration (emulator -accel-check reports it).
  local args=(-avd "$AVD_NAME" -no-audio -no-boot-anim -no-metrics)
  if [ "$window" = "1" ]; then
    args+=(-gpu auto)
  else
    args+=(-no-window -gpu swiftshader_indirect)
  fi
  log "booting $AVD_NAME (window=$window)"
  ANDROID_SDK_ROOT="$SDK_WIN" ANDROID_HOME="$SDK_WIN" \
    "$EMULATOR" "${args[@]}" >"$REPO_ROOT/build-tools/emulator.log" 2>&1 &
  wait_boot
}

cmd_install() {
  local apk="$REPO_ROOT/app-android/app/build/outputs/apk/debug/app-debug.apk"
  [ -f "$apk" ] || { echo "build first: ./build.sh assembleDebug" >&2; exit 1; }
  log "installing $(du -h "$apk" | cut -f1) APK"
  "$ADB" install -r "$(cygpath -w "$apk")" | tail -1

  # One book per EPUB in content/. The directory is gitignored and the fixtures are put there by
  # hand, so a fresh clone simply has none.
  local files=()
  local f
  for f in "$REPO_ROOT"/content/*.epub; do
    [ -f "$f" ] && files+=("$f")
  done
  if [ "${#files[@]}" = "0" ]; then
    log "no EPUBs in content/ - drop a .epub there and rerun to inject it"
    return
  fi

  # The id has to be a usable directory name, so it is the filename reduced to alphanumerics; the app
  # mints hash-based ids on import and can never collide with this. Titles are filenames because
  # reading real titles out of the EPUB is the app's job, not this script's.
  "$ADB" shell mkdir -p /data/local/tmp/br
  local ids=() titles=()
  for f in "${files[@]}"; do
    local name="${f##*/}"
    local id
    id="$(printf '%s' "${name%.epub}" | tr -cd 'A-Za-z0-9' | cut -c1-24)"
    [ -n "$id" ] || id="book"
    "$ADB" push "$(cygpath -w "$f")" "/data/local/tmp/br/$id.epub" >/dev/null
    ids+=("$id")
    titles+=("$name")
  done
  log "pushed ${#ids[@]} EPUB(s)"

  # Launch once so the app's data dir exists before run-as writes into it.
  "$ADB" shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1 || true
  sleep 6

  # The manifest needs book.epub's absolute path, and run-as already starts in the app's data dir,
  # so ask the device for that instead of hardcoding /data/user/0.
  local files_dir
  files_dir="$("$ADB" shell run-as "$PKG" pwd 2>/dev/null | tr -d '\r' || true)"
  [ -n "$files_dir" ] || files_dir="/data/data/$PKG"

  # The app reads its library from files/library.json rather than scanning the directory, and the SAF
  # picker that normally writes it cannot be driven over adb - writing this manifest by hand is the
  # entire reason this script exists.
  local json="$REPO_ROOT/build-tools/library.json"
  mkdir -p "$(dirname "$json")"
  local i
  # The device calls stay out of the block that writes library.json: that block's stdout IS the
  # manifest, so anything the device happened to print would land inside the file.
  for i in "${!ids[@]}"; do
    "$ADB" shell run-as "$PKG" mkdir -p "files/books/${ids[$i]}" >/dev/null
    "$ADB" shell run-as "$PKG" cp "/data/local/tmp/br/${ids[$i]}.epub" \
      "files/books/${ids[$i]}/book.epub" >/dev/null
  done

  {
    printf '{\n  "books": [\n'
    for i in "${!ids[@]}"; do
      local esc
      esc="$(printf '%s' "${titles[$i]}" | sed 's/\\/\\\\/g; s/"/\\"/g')"
      printf '    {"id": "%s", "title": "%s", "epubFile": "%s/files/books/%s/book.epub", ' \
        "${ids[$i]}" "$esc" "$files_dir" "${ids[$i]}"
      # lang stays "" on purpose: it means "the EPUB declared nothing", and the app resolves the
      # language per block itself. Inventing one here is how a German novel once got Chinese handling.
      printf '"lang": "", "addedAt": 0, "lastBlock": 0}%s\n' \
        "$([ "$i" -lt "$((${#ids[@]} - 1))" ] && echo ,)"
    done
    printf '  ]\n}\n'
  } > "$json"

  if ! "$ADB" shell "run-as $PKG sh -c 'cat > files/library.json'" < "$json"; then
    echo "could not write files/library.json on the device" >&2
    exit 1
  fi
  local written
  written="$("$ADB" shell "run-as $PKG grep -c '\"id\"' files/library.json" 2>/dev/null | tr -d '\r' || true)"
  [ "$written" = "${#ids[@]}" ] ||
    { echo "library.json holds $written entries, expected ${#ids[@]}" >&2; exit 1; }
  log "wrote library.json with ${#ids[@]} book(s)"

  # We wrote the manifest, not the app, so the live process is still holding the library it loaded
  # before that file existed. Stop it so the next launch re-reads it.
  "$ADB" shell am force-stop "$PKG" >/dev/null 2>&1 || true
  log "fixtures injected"
}

cmd_shot() {
  local out="${1:-emulator.png}"
  "$ADB" exec-out screencap -p > "$out"
  log "wrote $out ($(du -h "$out" | cut -f1))"
}

cmd_down() {
  running || { log "not running"; return; }
  "$ADB" emu kill >/dev/null 2>&1 || true
  log "stopped"
}

cmd_status() {
  "$ADB" devices | sed 's/^/  /'
  if running; then
    log "sdk=$("$ADB" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r') \
abi=$("$ADB" shell getprop ro.product.cpu.abi 2>/dev/null | tr -d '\r')"
    log "app installed: $("$ADB" shell pm list packages 2>/dev/null | grep -c "$PKG") match(es)"
  fi
}

case "${1:-}" in
  up)      shift; cmd_up "${1:-}" ;;
  install) shift; cmd_install ;;
  shot)    shift; cmd_shot "${1:-emulator.png}" ;;
  shell)   shift; "$ADB" shell "$@" ;;
  down)    cmd_down ;;
  status)  cmd_status ;;
  *)       usage ;;
esac
