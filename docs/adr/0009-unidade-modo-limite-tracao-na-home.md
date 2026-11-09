# ADR 0009 — Unidade, modo, limite e tração na Home (S31)
Status: aceito (camada domínio+UI) · Implementação `[ESTÁTICO]` até o teste real com "pode" no chat · Complementa o ADR 0008

## Contexto
Protocolo e transporte já existem (commit d1f7d90; mapeamento em `docs/research/parts/S31-tracao-limite-modo-unidade.md`). Faltava a Home: mesmo padrão do farol real (ADR 0008).

## Decisão
1. **Roteamento.** `HomeState.sendsViaTransportApi(cmd)`: com o BLE real (`genericCommandsAvailable = false`) modo, limite e tração vão por `setDriveMode/setSpeedLimit/setDrive`; a unidade (novo `ScooterCommand.SetSpeedUnit`, `Control.Unit`, único acréscimo ao `core-protocol`) vai SEMPRE por `setUnit`. O `HomeController` traduz `ScooterSettingResult` em `Ack` (`SettingAckCodes`): só `Confirmed` vira `Accepted`. Com o Fake (comandos genéricos) modo/limite/tração seguem `send`, como antes.
2. **Concluído só pelo transporte.** Esses comandos nunca são confirmados pela telemetria acumulada e a pendência não expira por Tick; falha em `Rejected`/`Unconfirmed`/recusa/perda de conexão, sem repetição automática. Recusas (nada enviado) somem em 6 s.
3. **Habilitação** (uma só porta, `realGate`, igual à do farol): conectado, desbloqueado, roda parada e velocidade 0 conhecida, Meter1 fresco, nenhum Lock/Unlock e nenhum outro comando real pendente. Exclusão mútua nos dois sentidos (farol, Lock, Unlock e os quatro).
4. **Regras de valor.** Modo: só os da máscara Meter2 b20 (bit i = modo i); sem máscara, sem opções. Limite: só com unidade = km/h (mph ou desconhecida desabilita), faixa 20..50 com 1 motor e 15..60 com 2 (`HomeRules.limitRange`); tração desconhecida desabilita. A barra da Home vai até 60 (decisão do proprietário), passo de 5 no stepper; acima do máximo informado fica desabilitado, com a dica "Acima de 50 km/h só com 2 motores". Nomes dos modos (Caminhada/Eco/Equilibrado/Esportivo) vêm do original (`[ESTÁTICO]`).
5. **Tração** (maior risco): diálogo Sim/Não antes de enviar, com aviso de mais potência, teto de 60 km/h e limite que pode mudar sozinho (`[CONFIRMADO]` na captura). Só com o transporte real; o Fake envia direto.
6. **Unidade exibida.** Novo cartão "Unidade de velocidade" (km/h ↔ mph), estado vindo do Meter1 b15 bit3. Com o patinete em mph, velocidade e odômetro da Home mostram o valor cru com "mph"/"mi" e a preferência de unidades do app não se aplica; o limite (b12) idem, desabilitado. Fora da Home nada foi alterado.

## Riscos
- `[HIPÓTESE]` campos de velocidade/odômetro em mph/mi quando b15 bit3 = 1: só b12 (50 → 51) foi observado, e ele não bate com a conversão (50 km/h = 31 mph); a escala real é `[DESCONHECIDO]`. Telemetria, viagens (TripTracker) e histórico continuam assumindo km/h e ficam errados enquanto o patinete estiver em mph.
- Trocar tração altera o limite sozinho; a Home só mostra o novo valor quando o Meter1 chega. Trocar a unidade não é confirmado em outros campos além de b15.
- Se o firmware aceitar os comandos bloqueado ou em movimento (`[DESCONHECIDO]`), a restrição é só do app e do transporte.
- A pergunta Sim/Não e o texto da dica vivem só na UI: nenhuma proteção equivalente no transporte além das travas já existentes.
- Pendência sem expiração: se o transporte nunca devolver resultado, só a perda de conexão libera os controles (mesmo risco do farol).


## Atualização S32 (teste real)
Unidade 0x3c, modo 0x35, limite 0x36 (teto 60 com 2 motores) e tração 0x3b passaram a `[CONFIRMADO]` em 2026-11-09 (`docs/research/parts/S32-comandos-real.md`). Mudanças de UI: sem diálogo na tração; unidade só nas Preferências; modos Pedestre/Eco/Esporte/Race (nomes do painel; 2 = Esporte e 3 = Race por ordem `[HIPÓTESE]`).
