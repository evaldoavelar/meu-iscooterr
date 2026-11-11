# CI (GitHub Actions)

Workflows em `.github/workflows/`. Só rodam se o repositório for publicado no GitHub (regra 7: push exige autorização).

## `ci.yml` (push e pull_request para `main`)
1. Falha se `git ls-files` rastrear `research/captures/`, `research/decompiled/`, `tools/` ou `*.apk` (exceções: `.gitkeep` e `tools/apktool`).
2. JDK 17 (Temurin), Android SDK (platform 37.0 e 35, build-tools 36.0.0), cache Gradle.
3. `./gradlew clean check` em `app/` (lint, testes JVM, ScreenshotTests JVM).
4. `:android-app:assembleDebug`.
5. Artifacts: `apk-debug` e `relatorios` (lint/testes; sempre enviados, inclusive em falha).

## `release.yml` (manual: workflow_dispatch ou tag `v*`)
- `:android-app:assembleRelease` sem assinatura embutida no projeto.
- Assina só se existirem os secrets `RELEASE_KEYSTORE_B64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`; senão emite aviso e entrega APK não assinado.
- Apenas artifact `apk-release`; não cria GitHub Release.

## Como ver
Aba Actions do repositório; artifacts no fim da execução. Local equivalente: `source scripts/android-env.sh` e gradle offline.

## Limites
- Sem teste BLE nem instrumentado/emulador no CI; BLE real só com o patinete (coordenador).
- Versões de actions fixadas por tag maior (checkout@v4, setup-java@v4, setup-android@v3, setup-gradle@v4, upload-artifact@v4); não por SHA.
- Versões do SDK/JDK refletem o projeto hoje (compileSdk 37, JDK 17); atualizar junto.
- 1º run real (2026-11-11) falhou no setup-android (id `platforms;android-37` inexistente; corrigido para `android-37.0` + `android-35`); a correção aguarda reexecução.
