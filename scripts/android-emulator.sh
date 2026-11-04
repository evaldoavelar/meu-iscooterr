#!/usr/bin/env bash
# Uso: scripts/android-emulator.sh [start|install|stop]  — emulador local (só o Fake; nenhum BLE). AVD "ix7" em tools/android-sdk/avd (ignorado pelo git).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/scripts/android-env.sh"
export ANDROID_AVD_HOME="$ANDROID_HOME/avd"
ADB() { adb -s emulator-5554 "$@"; }
case "${1:-start}" in
  start)
    nohup "$ANDROID_HOME/emulator/emulator" -avd ix7 -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -memory 2048 -no-snapshot >/dev/null 2>&1 &
    adb wait-for-device
    until [ "$(ADB shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do sleep 3; done
    ADB shell wm size 1080x2340; ADB shell wm density 420 ;;
  install) ADB install -r "$ROOT/app/android-app/build/outputs/apk/debug/android-app-debug.apk"
           ADB shell monkey -p br.dev.evaldo.meuscooter -c android.intent.category.LAUNCHER 1 ;;
  stop) ADB emu kill ;;
esac
