# CI (GitHub Actions)

Workflows em `.github/workflows/`. Só rodam se o repositório for publicado no GitHub (regra 7: push exige autorização).

## `ci.yml` (push e pull_request para `main`)
1. Falha se `git ls-files` rastrear `research/captures/`, `research/decompiled/`, `tools/` ou `*.apk` (exceções: `.gitkeep` e `tools/apktool`).
2. JDK 17 (Temurin), Android SDK (platform 37.0 e 35, build-tools 36.0.0), cache Gradle.
3. `./gradlew clean check` em `app/` (lint, testes JVM, ScreenshotTests JVM).
4. `:android-app:assembleDebug`.
5. Artifacts: `apk-debug` e `relatorios` (lint/testes; sempre enviados, inclusive em falha).

## `release.yml` (push na `main` ou manual)
- **Fluxo:** trabalho na `develop`; pull request `develop` → `main`; ao mesclar, o push na `main` dispara o `release.yml`, que lê `versionName` de `app/android-app/build.gradle.kts` e publica a **GitHub Release** `v<versionName>` com o APK assinado (`meu-iscooter-<versionName>.apk`) e notas geradas. Se a release dessa versão já existe, não faz nada: para uma nova versão, aumente `versionName` e `versionCode` na `develop`.
- **Manual (`workflow_dispatch`):** ensaio, só gera o APK assinado como artifact `apk-release`; não publica.
- `assembleRelease` + `zipalign` + `apksigner sign` e `apksigner verify`. Exige os secrets `RELEASE_KEYSTORE_B64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS` (gerados por `scripts/release/gerar-keystore.sh`); se faltarem, o job **falha** (não publica APK sem assinatura).
- Permissão `contents: write` só neste workflow (para criar a Release).

## Como ver
Aba Actions do repositório; artifacts no fim da execução. Local equivalente: `source scripts/android-env.sh` e gradle offline.

## Limites
- Sem teste BLE nem instrumentado/emulador no CI; BLE real só com o patinete (coordenador).
- Versões de actions fixadas por tag maior (checkout@v4, setup-java@v4, setup-android@v3, setup-gradle@v4, upload-artifact@v4); não por SHA.
- Versões do SDK/JDK refletem o projeto hoje (compileSdk 37, JDK 17); atualizar junto.
- 1º run real (2026-11-11) falhou no setup-android (id `platforms;android-37` inexistente; corrigido para `android-37.0` + `android-35`); corrigido na S44 (CI verde).
- **Beta rodada 1 (S48, 2026-11-12):** `release.yml` disparado por `workflow_dispatch` @ f66dc2d (run 43215776932): sucesso, artifact `apk-release` = `android-app-release-unsigned.apk` (9,7 MB, NÃO assinado), aviso de secrets ausentes emitido como esperado; nenhum GitHub Release/tag criado. CI do push (run 43214791543) verde. Rodada 2 (assinada) depende de "pode" no chat.
- **Beta rodada 2 (S52, 2026-11-18):** `release.yml` @ b86cc77+ (run 44388848156): sucesso; artifact `apk-release` contém `app-release-signed.apk` (9,9 MB) além do não assinado; `apksigner verify` OK (esquemas v2 e v3, 1 signatário). Keystore PKCS12 gerado pelo proprietário com `scripts/release/gerar-keystore.sh` (alias `meuscooter`, RSA 4096; arquivo `meuscooter-release.jks` ignorado pelo git; **backup do arquivo e da senha é do proprietário**: sem eles não há atualização de um app já instalado assinado com esta chave). Secrets do repositório: `RELEASE_KEYSTORE_B64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`. O aviso do workflow agora lista quais secrets faltam ou estão vazios (b86cc77).
- **Lição (S52):** `gh secret set` pelo prompt `!` do Claude Code não tem TTY e grava secret **vazio** sem erro; rodar em terminal real ou por pipe (`printf '%s' "$X" | gh secret set NOME`). Trocar de APK debug para release assinado exige desinstalar antes (assinaturas diferentes; apaga dados locais: fazer o backup do app antes).
