# Uso: source scripts/android-env.sh  (ambiente local em tools/, ignorado pelo git)
_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")/.." && pwd)"
export ANDROID_HOME="$_ROOT/tools/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export GRADLE_USER_HOME="$_ROOT/tools/gradle-home"
export PATH="$_ROOT/tools/gradle/gradle-9.8.2/bin:$ANDROID_HOME/platform-tools:$PATH"
