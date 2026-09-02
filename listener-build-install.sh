#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ADB="/Users/karankapur/Library/Android/sdk/platform-tools/adb"

if [[ -x "${SCRIPT_DIR}/gradlew" ]]; then
  PROJECT_DIR="${SCRIPT_DIR}"
elif [[ -x "${SCRIPT_DIR}/listener/gradlew" ]]; then
  PROJECT_DIR="${SCRIPT_DIR}/listener"
else
  echo "Could not find the listener Gradle project from ${SCRIPT_DIR}" >&2
  exit 
fi

cd "${PROJECT_DIR}"

# ./gradlew test
# ./gradlew lintDebug
./gradlew assembleDebug
"${ADB}" install -r app/build/outputs/apk/debug/app-debug.apk
