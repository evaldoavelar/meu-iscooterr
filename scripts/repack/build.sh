#!/usr/bin/env bash
# Rebuild the original iScooter Club APK with the passive IX7TAP logger (Sprint 2 capture).
# Prereqs: research/captures/repack/base decoded with `apktool d -r` and the 3 smali hooks applied
# (see scripts/repack/README.md). Outputs signed APKs in research/captures/repack/out/.
# Nothing is installed by this script.
set -euo pipefail
cd "$(dirname "$0")/../.."
export PATH=/usr/lib/jvm/java-17-openjdk/bin:$PATH
BT=tools/build-tools/android-15
R=research/captures/repack
SRC=research/captures/device-apk
TAP=scripts/repack/ix7tap
OUT=$R/out
mkdir -p "$OUT" "$R/dex"

# 1. Tap class -> dex (stubs only on the compile classpath, never packaged)
rm -rf "$TAP/out" && mkdir -p "$TAP/out/stubs" "$TAP/out/tap"
javac --release 8 -nowarn -d "$TAP/out/stubs" $(find "$TAP/stubs" -name '*.java')
javac --release 8 -nowarn -cp "$TAP/out/stubs" -d "$TAP/out/tap" "$TAP/src/ix7/Tap.java"
"$BT/d8" --release --min-api 24 --output "$R/dex" "$TAP/out/tap/ix7/Tap.class" 2>/dev/null

# 2. Rebuild base from patched smali (resources untouched: decoded with -r)
grep -q 'Lix7/Tap;->tx' "$R/base/smali_classes2/p0/a.smali"
grep -q 'Lix7/Tap;->rx' "$R/base/smali_classes2/com/clj/fastble/bluetooth/BleBluetooth\$a.smali"
grep -q 'Lix7/Tap;->wr' "$R/base/smali_classes2/com/clj/fastble/bluetooth/BleBluetooth\$a.smali"
java -jar tools/apktool.jar b -f -o "$OUT/base-unsigned.apk" "$R/base"

# 3. Add the Tap dex as the next classesN.dex
python3 -I - "$OUT/base-unsigned.apk" "$R/dex/classes.dex" <<'EOF'
import sys, zipfile
apk, dex = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk) as z:
    names = z.namelist()
n = 2
while f"classes{n}.dex" in names:
    n += 1
assert "classes.dex" in names and f"classes{n}.dex" not in names
with zipfile.ZipFile(apk, "a", zipfile.ZIP_DEFLATED) as z:
    z.write(dex, f"classes{n}.dex")
print(f"added classes{n}.dex")
EOF

# 4. Signing key (local, git-ignored). Password generated once, kept next to the key.
KS=$R/ix7tap.keystore
PASSF=$R/ix7tap.pass
if [ ! -f "$KS" ]; then
  python3 -I -c "import secrets;print(secrets.token_hex(16))" > "$PASSF"
  chmod 600 "$PASSF"
  keytool -genkeypair -keystore "$KS" -storepass:file "$PASSF" -keypass:file "$PASSF" \
    -alias ix7tap -keyalg RSA -keysize 3072 -validity 3650 -dname "CN=ix7tap local capture" -noprompt
fi

# 5. zipalign + sign base and both splits with the same key
sign() {
  local in=$1 out=$2
  "$BT/zipalign" -f -p 4 "$in" "$OUT/tmp-aligned.apk"
  "$BT/apksigner" sign --ks "$KS" --ks-pass "file:$PASSF" --ks-key-alias ix7tap \
    --out "$out" "$OUT/tmp-aligned.apk"
  rm -f "$OUT/tmp-aligned.apk"
  "$BT/apksigner" verify "$out"
}
sign "$OUT/base-unsigned.apk" "$OUT/base.apk"
for s in split_config.arm64_v8a split_config.xxhdpi; do
  python3 -I - "$SRC/$s.apk" "$OUT/$s-unsigned.apk" <<'EOF'
import sys, zipfile
src, dst = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(src) as zi, zipfile.ZipFile(dst, "w") as zo:
    for i in zi.infolist():
        if i.filename.startswith("META-INF/") and i.filename.rsplit(".", 1)[-1] in ("SF", "RSA", "DSA", "EC", "MF"):
            continue
        zo.writestr(i, zi.read(i.filename))
EOF
  sign "$OUT/$s-unsigned.apk" "$OUT/$s.apk"
done
rm -f "$OUT"/*-unsigned.apk "$OUT"/*.idsig
sha256sum "$OUT"/*.apk
echo "BUILD OK (nothing installed)"
