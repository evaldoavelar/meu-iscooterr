#!/usr/bin/env bash
set -euo pipefail

COMMON_DIR="$(git rev-parse --git-common-dir 2>/dev/null || true)"
if [ -z "$COMMON_DIR" ]; then
    echo "Erro: não foi possível determinar git-common-dir." >&2
    exit 1
fi

HOOKS_DIR="$COMMON_DIR/hooks"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
HOOK_NAMES=("post-commit" "post-merge")
MARKER="# iscooter-progress-hook"

if [ "${1:-}" = "--uninstall" ]; then
    for name in "${HOOK_NAMES[@]}"; do
        target="$HOOKS_DIR/$name"
        if [ -f "$target" ]; then
            if grep -Fq "$MARKER" "$target"; then
                rm -f "$target"
                echo "Hook $name removido com sucesso."
            else
                echo "Aviso: $target não foi criado por nós. Não será removido." >&2
            fi
        fi
    done
    exit 0
fi

mkdir -p "$HOOKS_DIR"

for name in "${HOOK_NAMES[@]}"; do
    source_file="$SCRIPT_DIR/$name"
    target="$HOOKS_DIR/$name"

    if [ -f "$target" ]; then
        if ! grep -Fq "$MARKER" "$target"; then
            echo "Aviso: hook existente $target não contém o marcador '$MARKER'. Não será sobrescrito." >&2
            continue
        fi
    fi

    cp "$source_file" "$target"
    chmod +x "$target"
    echo "Hook $name instalado com sucesso em $target."
done

exit 0
