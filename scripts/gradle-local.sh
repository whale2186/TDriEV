#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export GRADLE_USER_HOME="${DRIVE_GRADLE_HOME:-$ROOT/.gradle-user-home}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
TEMP_HOME="${TMPDIR:-$ROOT/.tmp}"
mkdir -p "$TEMP_HOME"
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Djava.io.tmpdir=$TEMP_HOME"
cd "$ROOT"
exec ./gradlew "$@"
