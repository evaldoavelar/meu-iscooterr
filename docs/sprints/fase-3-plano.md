# Fase 3 — Frontend com transporte simulado (Sprints 4–5)

Entrada: protótipo validado pelo proprietário (sessão 5, mesclado em `main`; `design/`). Esta fase **não fala com o patinete**: tudo roda sobre `ScooterTransport` simulado (regra: UI nunca bloqueia em operação BT; "concluído" só após confirmação simulada equivalente à telemetria).

## Escopo
Compose + Material 3, Coroutines/StateFlow, seguindo `design/ANDROID-MAPA.md` (home com menu ⋮ → Telemetria · Preferências · Histórico de viagens; sem sheets de tração/limite).

## Sprint 4 — Esqueleto e Home
1. Projeto Gradle (`app/core-domain`, `app/core-protocol` só interfaces, `app/data`, `app/ui`); `ScooterTransport` + `FakeScooterTransport` (latência ACK ~50 ms, estado confirmado ~420 ms, falhas injetáveis `[CONFIRMADO]` em S2).
2. Tokens: `ColorScheme` + `ExtendedColors` a partir de `design/lib.py` `COLORS` (claro/escuro).
3. `HomeScreen` com todos os estados (desconectado, conectando, bloqueado/desbloqueado, enviando por controle, confirmado, falha, em movimento) e menu ⋮.
Aceitação: testes de unidade do reducer de estado (sem "confirmado" antes da telemetria); screenshots Compose comparados aos PNGs de `design/out/`.

## Sprint 5 — Demais telas
Telemetria (`SpeedGauge` Canvas com neon externo e ícone do patinete), Histórico + detalhe (mapa OSM com atribuição; **decidir** online × cache), Preferências (DataStore), Primeiro uso, Recuperação. Dados de exemplo só no fake.

## Decisões abertas (a levar ao proprietário)
- Mapa OSM: tiles online com cache vs. offline; política de uso dos tiles.
- Nome "Meu iScooter" é marca de terceiros no nome — rever antes de distribuir.
- Controles em movimento: `[HIPÓTESE]` (comportamento real só na Fase 4, parado e com aprovação).
- Mapeamento nome↔valor dos 4 modos (CMD 0x35, 0–3) `[HIPÓTESE]`.

## Fora do escopo
BLE real (Fase 4), comando ao patinete, push/publicação.
