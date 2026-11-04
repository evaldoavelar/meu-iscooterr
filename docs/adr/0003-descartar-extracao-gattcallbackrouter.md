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
