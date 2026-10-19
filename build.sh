#!/usr/bin/env bash
# Build wrapper: sets JAVA_HOME (bash PATH does not reach .bat files) and pins Gradle.
export JAVA_HOME="$(cygpath -w "$HOME/scoop/apps/temurin21-jdk/current")"
export GRADLE_HOME="/c/Users/Felipe/EpubReader/build-tools/gradle-8.14.6"
export ANDROID_HOME="$(cygpath -w "$LOCALAPPDATA/Android/Sdk")"
cd /c/Users/Felipe/EpubReader/app-android || exit 1
exec "$GRADLE_HOME/bin/gradle.bat" "$@"
