#!/usr/bin/env bash
# Gera o keystore de assinatura e cadastra os 3 secrets do release.yml no GitHub.
# Rode em um terminal de verdade (precisa digitar a senha). Nada é gravado em log.
set -euo pipefail

cd "$(dirname "$0")/../.."
KS="$PWD/meuscooter-release.jks"
ALIAS="meuscooter"

command -v keytool >/dev/null || { echo "keytool não encontrado (instale o JDK)"; exit 1; }
command -v gh >/dev/null || { echo "gh não encontrado"; exit 1; }
gh auth status >/dev/null 2>&1 || { echo "gh não autenticado (gh auth login)"; exit 1; }

if [ -e "$KS" ]; then
  bak="$KS.antigo-$(date +%Y%m%d-%H%M%S)"
  mv "$KS" "$bak"
  echo "Keystore antigo guardado em: $bak"
fi

read -r -s -p "Nova senha do keystore (mín. 8 caracteres): " P1; echo
read -r -s -p "Repita a senha: " P2; echo
[ "$P1" = "$P2" ] || { echo "As senhas não conferem."; exit 1; }
[ "${#P1}" -ge 8 ] || { echo "Senha curta demais."; exit 1; }

keytool -genkeypair -v -storetype PKCS12 -keystore "$KS" -alias "$ALIAS" \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=Meu iScooter, O=Projeto pessoal, C=BR" \
  -storepass "$P1" -keypass "$P1" >/dev/null 2>&1

# confere que a senha abre o arquivo ANTES de enviar ao GitHub
keytool -list -keystore "$KS" -storepass "$P1" >/dev/null 2>&1 \
  || { echo "Falha ao verificar o keystore recém-criado."; exit 1; }
echo "Keystore criado e verificado: $KS"

printf '%s' "$P1" | gh secret set RELEASE_KEYSTORE_PASSWORD
printf '%s' "$ALIAS" | gh secret set RELEASE_KEY_ALIAS
base64 -w0 "$KS" | gh secret set RELEASE_KEYSTORE_B64
unset P1 P2

echo
gh secret list
chmod 600 "$KS"
echo
echo "Pronto. FAÇA BACKUP de $KS e da senha (gerenciador de senhas)."
echo "Sem eles não dá para atualizar o app já instalado com uma versão assinada."
echo "Para gerar o APK assinado: gh workflow run release.yml --ref main"
