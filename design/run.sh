#!/usr/bin/env bash
# Gera design/*.figcraft e PNGs. Binário: $FIGCRAFT_BIN ou tools do repo principal.
set -euo pipefail
D="$(cd "$(dirname "$0")" && pwd)"; cd "$D"
ROOT="$(git rev-parse --git-common-dir)/.."
C="${FIGCRAFT_BIN:-$ROOT/tools/figcraft/0.15.0/figcraft-0.15.0-linux-x86_64/bin/figcraft-cli}"
python3 build.py
"$C" run scripts/home.json --out home.figcraft >/dev/null
rm -rf out; mkdir -p out/escuro out/claro
"$C" export --out out/escuro/ --page "Home Escuro" --scale 2 --no-system-fonts home.figcraft >/dev/null
"$C" export --out out/claro/ --page "Home Claro" --scale 2 --no-system-fonts home.figcraft >/dev/null
# S48: copia somente os novos quadros para mockups/, com o tema no nome.
mkdir -p mockups
S48_FRAMES=(
  mapa-zoom-v1-ajustado
  mapa-zoom-v1-aproximado
  mapa-zoom-v1-recentrar
  mapa-zoom-v1-aproximado-fonte2
  altimetria-v1-preenchida
  altimetria-v1-sem-leitura
  altimetria-v1-preenchida-fonte2
)
for theme in escuro claro; do
  for frame in "${S48_FRAMES[@]}"; do
    cp "out/$theme/$frame.png" "mockups/$frame-$theme.png"
  done
done
ls out/escuro out/claro | wc -l
