# S19 — Decoder conservador de movimento da roda (autoria)

Data: 2026-11-05 · Base: `2ae1476` · Worktree: `.wc/speed`, branch `sprint-19/speed-decoder`.

## Evidência e limites

- [CONFIRMADO] Zero nos bytes[9..11] corresponde a parado na captura passiva. Quadros completos copiados de `research/captures/s19-speed/ix7tap-speed.txt` do repositório principal (arquivo ignorado ausente no worktree): linha 6, parado; 544, acelerador `0x2d`; 561, acelerador `0xc6`; 950, à mão `0x25`; 1063, à mão `0x01`. Literais estão em `TelemetryDecoderTest.s19WheelMotionDoesNotClaimAKmhScale`; CRC RX preservado mesmo com `crc=bad` no logger (a política RX existente não exige correspondência do CRC).
- [HIPÓTESE] `byte[9]/10` em km/h continua forte, não confirmada; nenhum valor não zero entra em `speedKmh`. [DESCONHECIDO] Composição/escala dos bytes[10..11]. Casos sintéticos com cada byte superior isoladamente não zero testam somente a guarda conservadora exigida; não são novas capturas. Referência: `S19-velocidade-captura.md`.
- [ESTÁTICO] Escolha: `Telemetry.wheelMoving: Boolean?`, separado de km/h, com `speedKmh = 0f` somente parado. Estado de movimento prevalece na pré-condição do Unlock e na Home, sem sentinela numérico na UI. Decisão: `docs/adr/0005-movimento-da-roda-sem-escala-kmh.md`.

## TDD observado (um teste novo por ciclo)

Comando-base: `python3 /tmp/speed-junit.py '<classe>#<método>'`; compilador Kotlin 2.4.21/JUnit 4.13.2 e dependências do cache local, sem download. Script adaptado do executor da correção r3 para este worktree; fontes e classes em `/tmp/speed-*`.

| Ciclo | Teste / classe | Vermelho observado | Verde | Logs em `/tmp/` |
|---|---|---|---|---|
| Zero | `TelemetryDecoderTest#s19StationaryFrameHasKnownZeroSpeed` | 1 teste, `expected 0.0 but was null` | 1 teste, 0 falhas | `speed-zero-{red,green}.log` |
| Movimento | `TelemetryDecoderTest#s19WheelMotionDoesNotClaimAKmhScale` | compilação: `wheelMoving` inexistente | 2 testes, 0 falhas (inclui Zero) | `speed-motion-{red,green}.log` |
| Acumulação | `TelemetryDecoderTest#s19MotionReplacesStoppedSpeedAndMeter2PreservesIt` | 1 teste, `expected null but was 0.0` | 1 teste, 0 falhas | `speed-merge-{red,green}.log` |

[ESTÁTICO] Validação adicional já executada: `python3 /tmp/speed-junit.py 'br.dev.evaldo.meuscooter.data.BleScooterTransportUnlockTest#s19StoppedFramePassesSpeedPreconditionWithTheDefaultDecoder' br.dev.evaldo.meuscooter.protocol.TelemetryDecoderTest br.dev.evaldo.meuscooter.protocol.AgedTelemetryTest` → **20 testes, 0 falhas**. Quadro real parado passa pela velocidade e chega a `NotLocked` (o quadro está desbloqueado); fixture bloqueada/parada S4-3 conclui um Unlock sem injeção de velocidade. Referência: `/tmp/speed-stopped-integration.log`.

[ESTÁTICO] Gradle solicitado foi executado em Bash: `cd app && source /home/evaldo/Documents/ix7/scripts/android-env.sh && flock /tmp/gradle.lock gradle :core-protocol:test :data:test :core-domain:test :ui:testDebugUnitTest` → **EXIT 1 antes de iniciar os testes**, falha de inicialização de `libnative-platform.so`. Referência: `/tmp/speed-gradle.log`.

[DESCONHECIDO] Resultado físico: nenhuma interação com patinete, BLE real ou adb. Compose/Robolectric, build Gradle Android, gate JaCoCo ≥80% e `clean check` dependem de ambiente que execute Gradle. Nenhuma aprovação pelo autor.
