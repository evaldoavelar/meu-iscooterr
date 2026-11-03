# ADR 0002 — Dependência de nuvem do app original
Status: **proposto** (aguarda confirmação experimental) · Data: 2026-11-03

## Contexto
`[ESTÁTICO]` O comando de lock é BLE+PIN de 4 dígitos, sem token de servidor no quadro (ver `docs/research/parts/T3-T7-ble-lock.md`, `T9-nuvem-sessao.md`). A UI original exige login e posse segundo o servidor; o patinete recebe `CURRENT_USER_CAR` (0x41) ligado a um recurso de proximidade. Se o firmware exige 0x41=1 ou outra prova de posse é `[DESCONHECIDO]`.

## Decisão proposta
1. App novo **sem dependência de nuvem nem do app original**: pareamento único, ID/MAC e PIN do proprietário guardados localmente (Android Keystore/EncryptedSharedPreferences).
2. Não replicar API do servidor, não falsificar posse, não contornar antifurto (REGRA 6).
3. Se a Sprint 2 mostrar que o lock exige prova de posse da nuvem: parar, registrar limitação e propor alternativa legítima (uso da própria conta, com sua autorização) antes de qualquer código.

## Critério de confirmação
Captura HCI com patinete parado: lock/unlock manual funciona com 0x41=0 e sem sessão de conta? Heartbeat é obrigatório? Resultado vira `[CONFIRMADO]` ou revoga esta ADR.
