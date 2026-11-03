#!/usr/bin/env bash
# T1: decompila base.apk (um processo pesado por vez). Log com EXIT.
set -u; ROOT=/home/evaldo/Documents/ix7; LOG=$ROOT/.omc/logs/T1-decompile.log
export PATH=/usr/lib/jvm/java-17-openjdk/bin:$PATH JADX_OPTS="-Xmx3g"
cd "$ROOT" && {
 echo "== apktool"; "$ROOT/tools/apktool" d -f -o research/decompiled/apktool research/original-apk/base.apk
 echo "== jadx"; jadx -j 2 --show-bad-code -d research/decompiled/jadx research/original-apk/base.apk
 echo "EXIT $?"; } > "$LOG" 2>&1
