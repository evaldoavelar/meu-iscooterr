# ADR 0003 — Descartar a extração `GattCallbackRouter` (S15-C); manter `AndroidGattClient`

Status: aceita (sessão 16, 2026-11-04). Regra do roteiro: "reprovar 2× de novo ⇒ descartar a extração".

## Contexto
S15-C extraía a lógica de callbacks do `AndroidGattClient` para uma classe testável em JVM (`GattCallbackRouter`, branch `task/s15c`). Revisões independentes do Codex Sol (`medium`):
- r1 (3 achados) e r2 (P1: corrida callback × `close()`): corrigidos.
- r3 `[ESTÁTICO]` (REPROVADO @ 427372b): limpeza do `catch` de `connect` antigo derrubava a sessão nova; teste de estresse mascarado. Corrigido em ed5c09f com TDD (vermelho observado).
- r4 `[ESTÁTICO]` (REPROVADO @ ed5c09f): retorno tardio de `start()` com `pending !== op` (e caminhos `SecurityException`/`null`) ainda limpam gate/estado/MTU da sessão nova; estresse ainda aceita sessão remanescente.

## Decisão
Não mesclar. O `AndroidGattClient` atual (em `main`, já revisado e usado nas etapas 4.0/4.1) permanece. O padrão se repete: cada rodada encontra um novo caminho em que uma tentativa antiga limpa o estado de uma nova, porque o router não tem noção de **geração/posse** da tentativa. Corrigir caso a caso não converge.

## Consequências
- Nenhuma mudança de comportamento do BLE; cobertura de `AndroidGattClient` segue indireta (testes de `CallbackGate`, `MtuNegotiator`, `BleScooterTransport` com `FakeGattClient`).
- Branch `task/s15c` @ ed5c09f guarda o trabalho e `docs/tdd/s15c.md` (inclui o achado real: queda de enlace sem operação pendente deve virar `Disconnected` — o `AndroidGattClient` atual já trata (`onConnectionStateChange`, ramo else, `[ESTÁTICO]`)).
- Se retomar: projetar com token de geração por tentativa (toda limpeza `if (generation == current)`), um único ponto de transição sob lock, e teste de estresse que afirma o estado antes de qualquer `close()` extra.

## Emenda (sessão 19, 2026-11-05) — `CallbackGate` removido; posse por sessão resolvida na 4.3a
O texto acima (sessão 16) é mantido como histórico. Desde então:
- `CallbackGate` (citado em "Consequências") **não existe mais**: foi removido com o seu teste na etapa 4.3a (`docs/tasks/HANDOFF-BLE-43a.md`, correção "P2 (Codex r3)").
- O problema que motivou esta ADR — tentativa antiga limpando o estado de uma nova por falta de noção de geração/posse — foi resolvido **estruturalmente** pela classe pura `GattSessions`/`GattSession` (`:data`, 4.3a), seguindo exatamente o "Se retomar" acima: cada `connectGatt` recebe uma `GattSession` nova que é dona da operação pendente, do `MtuNegotiator`, do `WriteSlot` e do `BluetoothGatt`; posse e conclusão acontecem num único ponto de transição sob lock (`GattSessions.transition`), com as conclusões executadas fora do lock; `open()` devolve a sessão aposentada para que cada GATT seja liberado exatamente uma vez. Testes: `GattSessionsTest`, `WriteSlotTest` (vermelho observado antes de cada correção, Codex r3/r4).
- A decisão desta ADR continua válida: o `GattCallbackRouter` da S15-C (`task/s15c`) segue descartado; o `AndroidGattClient` virou um tradutor fino de callbacks sobre `GattSessions` e continua fora da JVM `[ESTÁTICO]`. A cobertura indireta passou a ser `GattSessionsTest`, `WriteSlotTest`, `MtuNegotiatorTest` e os testes do `BleScooterTransport` com `FakeGattClient` (inclui o desbloqueio da 4.4, que reaproveita essa camada sem alterá-la).
