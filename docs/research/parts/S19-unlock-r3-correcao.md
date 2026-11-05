# S19 — Unlock 4.4: correção r3 (autoria)

Data: 2026-11-05. Base: `sprint-19/unlock-code` em `6e22347`, árvore inicialmente limpa. Revisão lida em `/home/evaldo/Documents/ix7/.omc/reviews/S19-unlock-r3.md` (ausente no worktree). Escopo: P2 e P3; decisão em `docs/adr/0004-confirmacao-unlock-por-recepcao.md`. Sem revisão/aprovação própria, `git add`, commit ou merge. Sem BLE real, adb, rede ou PIN real; os testes novos usam apenas 1357 sintético.

## P2 — carimbo e fronteira

[ESTÁTICO] `GattReception` é o ponto comum de atribuição de instante/sequência e de captura de `GattBoundary`, sob um único lock. `AndroidGattClient` (callbacks API 33+ e antiga) e `FakeGattClient` delegam a ele. O transporte captura a fronteira inicial com `markBoundary()` e a fronteira de sequência posterior à escrita pela mesma operação. Referências: `GattReception.kt`, `GattClient.kt`, `AndroidGattClient.kt`, `FakeGattClient.kt`, `BleScooterTransport.unlock/isCurrentUnlockResponse`.

[ESTÁTICO] A regressão `aPinRejectionPausedBetweenClockAndSequenceCannotRejectAttemptB` injeta um relógio que pausa somente a thread do callback antigo, após ler o tempo virtual e antes de atribuir sequência. Após A expirar, B começa no mesmo ms; o teste só libera o callback quando B terminou a captura parcial (código antigo) ou está bloqueado no lock pertencente ao callback (código corrigido, observado via `ThreadMXBean`). O scheduler executa o transporte sem chamadas simultâneas a `runCurrent`. A recusa antiga não pode encerrar B; uma nova recusa deve encerrá-la. Latches/esperas têm limite de 5 s para detectar travamento. Referência: `BleScooterTransportUnlockTest`.

## P3 — janela inclusiva

[ESTÁTICO] O esperado vem do contrato de ≤ 3 s, explicitado no pedido r3/P3. O teste `confirmationWindowIncludes3000MsButExcludes3001Ms` injeta Meter1 desbloqueado após a conclusão da escrita, separadamente em 2999, 3000 e 3001 ms; exige `Unlocked`, `Unlocked` e `Unconfirmed(Timeout)`. A comparação passou a `<=`; a espera ganhou 1 ms para expirar no primeiro ms fora da janela. Referências: `BleScooterTransport.unlock/isCurrentUnlockResponse`, `BleScooterTransportUnlockTest`.

## Execuções realizadas

[ESTÁTICO] Compilador Kotlin 2.4.21 e JUnit 4.13.2 locais, sem download. Scripts `/tmp/unlock-r3-{junit,offline-junit,android-compile}.py` derivados dos scripts r2 já disponíveis, alterando apenas os caminhos temporários de saída. Cada teste novo foi executado e falhou por asserção antes da implementação correspondente.

| Comando | Resultado observado | Log em `/tmp/` |
| --- | --- | --- |
| `python3 /tmp/unlock-r3-junit.py br.dev.evaldo.meuscooter.data.BleScooterTransportUnlockTest` (P2 vermelho) | EXIT 1; 30 testes, uma falha: B herdou a recusa de A | `unlock-r3-p2-red.log` |
| `python3 /tmp/unlock-r3-junit.py br.dev.evaldo.meuscooter.data.BleScooterTransportUnlockTest br.dev.evaldo.meuscooter.data.FakeGattClientTest br.dev.evaldo.meuscooter.data.BleScooterTransportTxTest br.dev.evaldo.meuscooter.data.BleScooterTransportTest` | EXIT 0; 95 testes | `unlock-r3-p2-green.log` |
| `python3 /tmp/unlock-r3-junit.py br.dev.evaldo.meuscooter.data.BleScooterTransportUnlockTest` (P3 vermelho) | EXIT 1; 31 testes, uma falha: 3000 ms retornou timeout | `unlock-r3-p3-red.log` |
| Mesmo comando, após corrigir P3 | EXIT 0; 31 testes, incluindo 2999/3000/3001 ms | `unlock-r3-p3-green.log` |
| `python3 /tmp/unlock-r3-offline-junit.py` | EXIT 0; 414 testes, seis HTTP/loopback explicitamente excluídos | `unlock-r3-junit-final.log` |
| `python3 /tmp/unlock-r3-android-compile.py` | EXIT 0; compilação direta com SDK `android-37.0/android.jar`, aviso de API obsoleta | `unlock-r3-android-compile.log` |
| `cd app && source /home/evaldo/Documents/ix7/scripts/android-env.sh && flock /tmp/gradle.lock gradle :core-protocol:test :data:test :core-domain:test :ui:testDebugUnitTest` (Bash, antes e depois das correções) | EXIT 1 em ambas, antes dos testes: falha ao carregar `libnative-platform.so` | `unlock-r3-gradle.log`, `unlock-r3-gradle-final.log` |
| `grep -rn IX7BLE app/` | Vazio, EXIT 1 | Saída da execução |
| `git diff --check` | EXIT 0 | Saída da execução |

[ESTÁTICO] A suíte ampliada compila produção/testes de `core-protocol`, `data`, `core-domain` e os mappers puros da UI. Os cinco testes de disco de `TileStorageTest` rodam; os seis HTTP/loopback são filtrados pelo runner offline, sem abertura de sockets. A compilação direta Android abrange apenas `AndroidGattClient.kt`, usando as classes JVM produzidas nesta rodada; não compila `MainActivity` nem substitui um build Gradle Android.

[DESCONHECIDO] Não validados nesta rodada: Compose/Robolectric, build Gradle Android, gate JaCoCo ≥80%, `clean check`, seis testes HTTP e atuação física. Revisão independente permanece pendente. Nenhuma alteração a `CLAUDE.md`, `.omc`, gradle wrapper ou goldens.
