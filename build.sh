#!/usr/bin/env bash
# Build wrapper for the Android app: runs the project's Gradle wrapper (pinned to 8.14.6) with a JDK and
# Android SDK found for THIS machine. Usage: ./build.sh assembleDebug | test | ... (any Gradle task)
#   JAVA_HOME / ANDROID_HOME in the environment win; otherwise: the JDK `java` points into, then the
#   platform's usual SDK location (VPS ~/android-sdk, Windows %LOCALAPPDATA%/Android/Sdk, macOS ~/Library/Android/sdk).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

to_native() { if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi; }

if [ -z "${JAVA_HOME:-}" ]; then
  if command -v java >/dev/null 2>&1; then
    j="$(readlink -f "$(command -v java)" 2>/dev/null || command -v java)"
    JAVA_HOME="$(to_native "$(dirname "$(dirname "$j")")")"
  elif [ -d "$HOME/scoop/apps/temurin21-jdk/current" ]; then   # the Windows laptop's JDK
    JAVA_HOME="$(to_native "$HOME/scoop/apps/temurin21-jdk/current")"
  else
    echo "no JDK found: set JAVA_HOME (the project needs Java 17+)" >&2; exit 1
  fi
  export JAVA_HOME
fi

if [ -z "${ANDROID_HOME:-}" ]; then
  for d in "${ANDROID_SDK_ROOT:-}" "$HOME/android-sdk" "${LOCALAPPDATA:+$LOCALAPPDATA/Android/Sdk}" "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
    if [ -n "$d" ] && [ -d "$d/platforms" ]; then ANDROID_HOME="$(to_native "$d")"; break; fi
  done
  [ -n "${ANDROID_HOME:-}" ] || { echo "no Android SDK found: set ANDROID_HOME" >&2; exit 1; }
  export ANDROID_HOME
fi

cd "$ROOT/app-android"
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) exec ./gradlew.bat "$@" ;;
  *)                    exec ./gradlew "$@" ;;
esac
