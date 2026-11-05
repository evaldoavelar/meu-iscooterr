# S19 — Correção de autoria dos quatro achados da revisão r1

Data: 2026-11-05. Base: `ea04bc5`, branch `sprint-19/unlock-code`. Revisão lida em `/home/evaldo/Documents/ix7/.omc/reviews/S19-unlock-r1.md` (não existe neste worktree). Contrato: pedido do proprietário e `docs/sprints/fase-4-plano.md` §7.4. Decisão: ADR 0004.

## Evidência de TDD executado

[ESTÁTICO] Cada regressão abaixo foi executada antes da respectiva implementação, com falha de asserção observada. O compilador Kotlin 2.4.21 e o JUnit 4.13.2 locais foram invocados diretamente porque o Gradle não inicializou. Coroutines core/test JVM 1.11.0, alvo JVM 17; nenhum download. Executor temporário: `/tmp/unlock-r1-junit.py`; fontes de produção de `core-protocol`, `core-domain`, `data` e `HomeUiMapper`/`HomeUiState`, mais os testes selecionados. Referências: fontes dos testes e logs `/tmp/unlock-r1-*-junit-{red,green}.log`.

| Achado | Teste independente do código de produção | Vermelho observado | Verde posterior |
| --- | --- | --- | --- |
| P1-1 | `BleScooterTransportUnlockTest.anUnlockedMeter1BufferedBeforeTheWriteCannotConfirmIt` | 24 testes, 1 falha: esperado `Unconfirmed(Timeout)`, obtido `Unlocked` | 36 testes, 0 falhas (classe de unlock + `FakeGattClientTest`) |
| P1-2 | `callerCancellationDuringWriteOrConfirmationClosesBeforeRethrowingAndRefusesAnotherUnlock` | 25 testes, 1 falha: GATT aberto ao relançar cancelamento durante escrita | 25 testes, 0 falhas; escrita pendurada e espera de confirmação verificadas |
| P2-3 | `theFrameGivenToGattIsWipedAfterSuccessFailureAndCancellation` | 26 testes, 1 falha: referência do frame ainda preenchida após sucesso | 44 testes, 0 falhas (`BleScooterTransportUnlockTest`, `TxFrameTest`, `PinTest`) |
| P2-4 | `HomeUiMapperPinLinkLostTest.linkLostDuringUnlockKeepsTheUnknownLockWarningAfterDisconnection` | 1 teste, 1 falha: esperado `Error`, obtido `Disconnected` | 43 testes, 0 falhas (regressão + `HomeUiMapperTest`) |

[ESTÁTICO] P1-1 retém o quadro desbloqueado até depois de concluir a escrita, mantendo o Meter1 bloqueado como pré-condição; sem novo Meter1 desbloqueado, a tentativa expira. O teste existente `unlockedOnlyWhenMeter1ShowsTheLockBitCleared` verifica a recepção posterior que confirma. Referências: `BleScooterTransportUnlockTest`, `GattNotification`, `BleScooterTransport.unlock/onNotification`.

[ESTÁTICO] P1-2 exige fechamento antes de relançar `CancellationException`, estado `Disconnected` e falha `WriteTimedOut`, recusa da segunda tentativa e nenhuma escrita posterior. Referência: teste da tabela e `BleScooterTransport.unlock` (`NonCancellable`).

[ESTÁTICO] P2-3 captura a referência do frame e verifica o literal sintético `1aa13202050001030507523d1ff1` antes do write/wipe. Verifica zeros após sucesso, erro e cancelamento. Uma tentativa intermediária de montagem direta expôs um método Kotlin `internal` com parâmetro `ByteArray` na reflexão Java: `TxFrameTest.unlockOnlyTakesAPinAndNoPublicApiAcceptsRawBytesOrAMode` falhou. Corrigida a implementação, sem relaxar esse teste: um payload temporário apagado em `finally`, sem concatenações. Cópia Android apagada em `AndroidGattClient.write` e frame em `BleScooterTransport.unlock`; `Pin.wipe()` do controller preservado. Referências: `Pin.unlockPayload`, `TxFrameBuilder.build`, testes da tabela.

[ESTÁTICO] P2-4 desconecta efetivamente `FakeScooterTransport`, recebe `Disconnected` no `HomeController` e só então mapeia o resultado `Unconfirmed(LinkLost)`. O texto exigido é “Sem confirmação — estado de bloqueio desconhecido”; o botão oferece procurar o patinete, sem afirmar o bloqueio. Referências: `HomeUiMapperPinLinkLostTest`, `HomeUiMapper.connection`.

## Verificação ampliada e limites reais

[ESTÁTICO] `python3 /tmp/unlock-r1-junit.py` compilou as fontes acima e executou todos os testes dos três módulos JVM e as duas classes de mapper: **415 executados, 409 passaram, 6 falharam**, EXIT 1. Todas as seis falhas são `java.net.SocketException: Operation not permitted` na abertura dos servidores HTTP de loopback de `TileStorageTest`, antes de qualquer requisição; não foram alterados esses testes nem o código de rede. Log: `/tmp/unlock-r1-junit-final.log`.

[ESTÁTICO] O comando pedido foi executado em foreground, com Bash:

```sh
cd app && source /home/evaldo/Documents/ix7/scripts/android-env.sh && flock /tmp/gradle.lock gradle :core-protocol:test :data:test :core-domain:test :ui:testDebugUnitTest
```

[ESTÁTICO] Resultado: EXIT 1 antes de executar testes — `Could not initialize native services` / `Failed to load native library 'libnative-platform.so' for Linux amd64`. Log: `/tmp/unlock-r1-gradle-final.log`. Uma tentativa anterior copiou caches já existentes para `/tmp/unlock-r1-gradle-home` e usou `GRADLE_USER_HOME=/tmp/unlock-r1-gradle-home` com `gradle --offline :data:test --tests '*anUnlockedMeter1BufferedBeforeTheWriteCannotConfirmIt'`; falhou antes dos testes em `FileLockContentionHandler` / `Could not determine a usable wildcard IP for this machine`. Não se conta nenhuma dessas falhas de infraestrutura como vermelho TDD.

[ESTÁTICO] `AndroidGattClient.kt` compilado diretamente com Kotlin 2.4.21, classes JVM desta rodada e `/home/evaldo/Documents/ix7/tools/android-sdk/platforms/android-37.0/android.jar`: EXIT 0 (somente aviso de `connectGatt` obsoleto). A primeira tentativa apontava para o diretório inexistente `android-37` e falhou por ausência do SDK nesse caminho; corrigido o caminho, a compilação passou. Log final: `/tmp/unlock-r1-android-compile.log`. Isto não equivale a build Android/Gradle nem ensaio BLE.

[ESTÁTICO] `grep -rn IX7BLE app/` retornou vazio (EXIT 1). `git diff --check` retornou EXIT 0. Não foram executados `clean check`, testes Compose/Robolectric desta rodada nem gate JaCoCo; cobertura ≥80% não foi medida nesta execução. As quatro correções aguardam revisão independente; não há aprovação pelo autor.

[DESCONHECIDO] Resultado no equipamento: sem ensaio BLE/adb, sem acesso externo de rede, sem PIN real. PIN de regressão: sintético 1357. A limitação anterior de velocidade desconhecida no decoder permanece fora destes quatro achados (handoff original). Goldens/baselines, wrapper, `CLAUDE.md` e `.omc` não foram alterados.

[ESTÁTICO] O commit autorizado foi tentado com `git add` dos 14 arquivos deste incremento seguido de `git commit -m 'Corrige confirmação, cancelamento e limpeza do desbloqueio com PIN'`. O `git add` falhou (EXIT 128) ao criar `/home/evaldo/Documents/ix7/.git/worktrees/unlock/index.lock`: `Read-only file system`; o commit não chegou a executar. Sem hash novo, base `ea04bc5`; alterações preservadas no worktree. Pendentes commit, validação Gradle/Compose/JaCoCo e revisão independente.
