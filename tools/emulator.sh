#!/usr/bin/env bash
# Local Android emulator for testing Book Reader without a physical device.
#
# Why this exists: a physical phone is a poor automation target because of its
# lockscreen. `screencap` on a locked device silently returns the always-on display,
# and `wm dismiss-keyguard` cannot bypass a secure lock, so any visual verification
# needs a human to unlock it. The emulator has no keyguard, so screencap and
# `input tap` work unconditionally.
#
# Usage:
#   tools/emulator.sh up            # install cmdline-tools if needed, create AVD, boot
#   tools/emulator.sh up --window   # same, but with a visible window you can interact with
#   tools/emulator.sh install       # install the built APK and inject test fixtures
#   tools/emulator.sh shot out.png  # screencap to a file
#   tools/emulator.sh shell '<cmd>' # run a shell command on the device
#   tools/emulator.sh down          # stop the emulator
#   tools/emulator.sh status
set -euo pipefail

SDK_WIN="${ANDROID_HOME:-C:/Users/Felipe/AppData/Local/Android/Sdk}"
SDK_BASH="$(cygpath -u "$SDK_WIN" 2>/dev/null || echo "$SDK_WIN")"
ADB="$SDK_WIN/platform-tools/adb.exe"
EMULATOR="$SDK_WIN/emulator/emulator.exe"
SDKMGR="$SDK_WIN/cmdline-tools/latest/bin/sdkmanager.bat"
AVDMGR="$SDK_WIN/cmdline-tools/latest/bin/avdmanager.bat"

AVD_NAME="${AVD_NAME:-bookreader_test}"
PKG="com.fpedroso.bookreader"
# This image was already present locally, which avoids a ~1.5 GB download.
SYS_IMAGE="system-images;android-36;google_apis_playstore;x86_64"
DEVICE_PROFILE="pixel_6"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# sdkmanager/avdmanager are .bat files, so bash's PATH is not enough - they consult
# JAVA_HOME directly. jdk via scoop on this machine.
if [ -z "${JAVA_HOME:-}" ] && [ -d "$HOME/scoop/apps/temurin21-jdk/current" ]; then
  export JAVA_HOME="$(cygpath -w "$HOME/scoop/apps/temurin21-jdk/current")"
fi

log() { printf '  %s\n' "$*"; }

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

  # Fixtures are generated, not committed, so only inject what exists.
  local staged=0
  "$ADB" shell mkdir -p /data/local/tmp/br
  for f in hsk1.epub hsk1.pinyin.json 24571.epub; do
    if [ -f "$REPO_ROOT/content/$f" ]; then
      "$ADB" push "$(cygpath -w "$REPO_ROOT/content/$f")" "/data/local/tmp/br/$f" >/dev/null
      staged=1
    fi
  done
  if [ "$staged" = "0" ]; then
    log "no fixtures in content/ - run tools/gen_hsk1.py and tools/annotate.py"
    return
  fi

  # Launch once so the app's data dir exists before run-as writes into it.
  "$ADB" shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1 || true
  sleep 6
  "$ADB" shell run-as "$PKG" mkdir -p files/books/hsk1test files/books/destest
  [ -f "$REPO_ROOT/content/hsk1.epub" ] &&
    "$ADB" shell run-as "$PKG" cp /data/local/tmp/br/hsk1.epub files/books/hsk1test/book.epub
  [ -f "$REPO_ROOT/content/hsk1.pinyin.json" ] &&
    "$ADB" shell run-as "$PKG" cp /data/local/tmp/br/hsk1.pinyin.json files/books/hsk1test/pinyin.json
  [ -f "$REPO_ROOT/content/24571.epub" ] &&
    "$ADB" shell run-as "$PKG" cp /data/local/tmp/br/24571.epub files/books/destest/book.epub
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
  *)       sed -n '2,16p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' ;;
esac
