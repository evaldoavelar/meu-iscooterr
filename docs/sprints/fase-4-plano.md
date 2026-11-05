# Fase 4 — Comunicação BLE real: plano de teste (APROVADO com ajustes em 2026-11-04; 4.2 só com o proprietário presente e confirmando; 4.4 bloqueada)
Escrito na sessão 12 (2026-11-04). **Nenhum comando BLE será enviado ao patinete até o proprietário aprovar este plano e estar presente, com o patinete montado no cavalete.** Equipamento próprio apenas (CLAUDE.md, regras 5 e 6).

## 1. O que já está confirmado (evidência)
| Fato | Rótulo | Fonte |
|---|---|---|
| Características GATT `0xFF61` (RX, notificações do patinete) e `0xFF62` (WR, escrita do app) | `[CONFIRMADO]` | log IX7TAP `research/captures/s2d/ix7tap-roteiro2.dedup.txt` (`RX … uuid=ff61`, `WR … uuid=ff62 status=0`) |
| Serviço `0xFF60` | `[ESTÁTICO]` | `docs/research/parts/T3-T7-ble-lock.md`; o log de captura não registra o serviço |
| Quadro `1A A1 · CMD · TIPO · LEN · DADOS · CRC16 · 1F F1`; TX com CRC-16/Modbus (byte baixo primeiro) | `[CONFIRMADO]` | `S2-captura-resultados.md` (CRC `lohi` em todos os `WR`) |
| Telemetria Meter1 (0x20) e Meter2 (0x21), tipo `03`, ~300 ms cada; bateria em dado[3], limite em dado[7] | `[CONFIRMADO]` | idem; exemplo: `1aa1 20 03 12 …` |
| CRC das **respostas** não confere com nenhum algoritmo testado | `[DESCONHECIDO]` | idem — o app novo **não deve exigir** CRC válido em RX (como o original) |
| LOCK `0x32`, modo 2 = bloquear, modo 0 = desbloquear, sem PIN neste patinete | `[CONFIRMADO]` para o estado de captura de 2026-11-03 | idem — **reaberto abaixo** |
| Desligamento automático ~10 min após o último comando | `[HIPÓTESE forte]` | idem |
| Telemetria só chega depois do `CONNECT 0x30`/heartbeat do app original? | `[DESCONHECIDO]` | **a ser descoberto na etapa 4.2 sem enviar nada** |

**PIN (reaberto):** o proprietário informa que o app original tem bloqueio por PIN. A S2 viu `lock_has_pwd = 0` e bloqueio sem PIN, mas isso passa a ser `[HIPÓTESE]` até o log do teste dele (item 6 do prompt da sessão 12). Se houver PIN, o app novo **não** o contorna nem o define: só opera com o PIN que o proprietário já usa (ADR 0002) e nunca envia o modo 1 (define PIN). Depois do teste, rotacionar o PIN (memória `pin-leak-hardening`).

## 2. Regras de segurança (valem para todas as etapas)
1. Patinete **parado, no cavalete, com a roda traseira/dianteira livre**, proprietário presente e com a mão no botão de abortar. Nunca em movimento, nunca com alguém em cima.
2. **Sem comando experimental.** Só os comandos já `[CONFIRMADO]` na S2, e só na etapa 4.4, depois de aprovação separada por escrito (um por vez).
3. **Abortar com um toque:** botão fixo "Parar/Desconectar" em toda tela de teste; ele cancela escrita pendente, desinscreve notificações e fecha o GATT. Fisicamente: desligar o patinete.
4. Só o coordenador/proprietário tocam o patinete. Nenhum agente roda `adb` com BLE real sem o proprietário avisar.
5. O app original deve estar **fechado** (uma única conexão GATT) e o Bluetooth do celular dedicado ao teste.
6. Logs de captura ficam em `research/captures/` (privados, ignorados pelo git); nada de PIN em texto claro nos logs versionados.
7. Interface `ScooterTransport` inalterada; a implementação real entra como `BleScooterTransport` ao lado do `FakeScooterTransport`, selecionada por uma chave de depuração desligada por padrão.

## 3. Etapas
| Etapa | O que acontece | Envia algo ao patinete? | Critério de sucesso | Abortar se |
|---|---|---|---|---|
| 4.0 Pré-requisitos (sem patinete) | `BleScooterTransport` com **GATT falso** em testes JVM; parser de quadros RX com os quadros reais de `s2d` como vetores (valores esperados do log, não do código); permissões `BLUETOOTH_SCAN/CONNECT` reais no manifest; timeout/limites de `connect()` | Não | testes verdes, cobertura ≥ 80 % | — |
| 4.1 Só varredura | Procurar anúncios BLE; mostrar nome/RSSI do patinete; **sem conectar** | Não | o patinete aparece e é reconhecido pelo nome | aparece mais de um candidato: proprietário escolhe |
| 4.2 Conectar e ouvir | Conexão GATT, descoberta de serviços, confirmar `FF60/FF61/FF62`. **Inscrever em notificações grava o descritor CCCD** (escrita BLE de configuração, não comando): **precisa do ok explícito do proprietário na hora**. Registrar o que chegar por 60 s | Só a inscrição CCCD | quadros `03` Meter1/Meter2 decodificados batem com o painel do patinete (bateria %) | nenhum quadro em 15 s (então `[DESCONHECIDO]` vira dado: **parar e perguntar**, não enviar `0x30`/heartbeat por conta própria) |
| 4.3 Telemetria na Home/Telemetria | Telemetria real alimenta as telas (bateria, velocidade 0, limite); campos sem leitura confirmada (V, I, temperaturas, odômetros) ficam `--` | Não | 10 min de escuta estável; desconexão e reconexão tratadas pela Recuperação | desligamento automático de ~10 min: tratar como desconexão esperada |
| 4.4 Primeiro comando (aprovação separada) | Somente `LOCK 0x32` modo 2 ou 0, **um** por vez, com o patinete parado; "concluído" só após confirmação por telemetria (regra 5) | **Sim — só com nova aprovação** | ACK tipo `05` e `lock_status` mudando em Meter1 | ACK ausente por >2 s, estado divergente, qualquer comportamento inesperado |

Etapas 4.0 e 4.1 não tocam o patinete e podem ser feitas sem o proprietário. 4.2 em diante exigem o proprietário presente.

## 4. O que NÃO está no escopo desta fase
Farol, modos, tração, cruise, partida do zero e limite de velocidade reais (comandos `0x33…0x39` existem na S2 como `[CONFIRMADO]`, mas cada um só entra com plano e aprovação próprios); qualquer comando de PIN; qualquer ação com o patinete em movimento; atualização de firmware; contornar antifurto/autenticação.

## 5. Decisões que dependem do proprietário
1. Aprovar este plano (ou pedir mudanças) e marcar um horário com o patinete no cavalete.
2. Autorizar a gravação do CCCD (etapa 4.2) como "escrita de configuração".
3. Resultado do teste do PIN no app original (reabre o `[HIPÓTESE]` acima).
4. Se a etapa 4.2 não mostrar telemetria sem `CONNECT 0x30`: decidir se esse comando entra como o primeiro envio da etapa 4.4.

## 6. Ajustes aprovados pelo proprietário (2026-11-04)
- 4.1: descoberta sem conectar e sem escrita. 4.2: só com o proprietário presente, confirmação explícita no momento. 4.3 após sucesso da 4.2. 4.4 bloqueada até nova autorização específica.
- Timeouts separados: **1ª notificação 15 s**; **janela de observação 60 s**. Serviço `FF60` a confirmar experimentalmente na 4.2 (hoje `[ESTÁTICO]`).
- Sem telemetria após o CCCD: parar e documentar; **não** enviar `CONNECT 0x30` nem heartbeat sem autorização adicional.
- Instrumentar tempos: descoberta, conexão, descoberta de serviços, inscrição, 1ª telemetria.
- ACK ≠ confirmação (estado por telemetria); cancelamento, timeouts e GATT encerrado com segurança; RX validado em estrutura, tamanho e estado apesar de o CRC de resposta ser `[DESCONHECIDO]`. Fake e BLE real separados.
- Mapa OSM com trajetos simulados entra já (independente do BLE). PIN: pendência experimental; nunca definir/remover/adivinhar PIN.

## 7. Revisão após a 4.2 — plano das etapas 4.3 e 4.4 (sessão 18, 2026-11-05) — **PROPOSTO, NADA EXECUTADO**
Substitui as linhas 4.3/4.4 da tabela da §3, que supunham patinete sem PIN e telemetria "só escuta". Nenhum byte será enviado ao patinete antes da **aprovação escrita do proprietário** (regra 6; itens da §7.6). Evidência de base: `docs/research/parts/S17-4-2-tentativa1.md`; bytes de `research/captures/s4-3/ix7tap.txt`.

### 7.1 Fatos que sustentam o plano
| Fato | Rótulo |
|---|---|
| 4.2 ok: GATT + MTU 255 + CCCD de FF61, Meter1/Meter2 a ~300 ms, sem enviar nada | `[CONFIRMADO]` |
| O original envia `CONNECT 0x30` = `1aa1300201016eb41ff1` (ACK tipo 05 em ~110 ms; enviado 2×) e heartbeat `0x10` = `1aa110010071951ff1` a cada ~1,0 s (219 em 224 s; ACK em cada um) | `[CONFIRMADO]` |
| O patinete tem PIN: Meter1 `dado[1]` = `0x30` (bit5 bloqueado + bit4 `lock_has_pwd`); `LOCK 0x32` TIPO 02 LEN 5 (modo + 4 dígitos) → ACK em 49 ms → `dado[1]` = `0x00` ~660 ms depois | `[CONFIRMADO]` |
| O bloqueio após a 4.2 veio de ausência de heartbeat/CONNECT ou do fechamento do app | `[HIPÓTESE]` (não testada; não afirmar a causa) |
| Resposta a PIN errado = tipo `0x89`; efeito de erros repetidos (bloqueio temporário?) | `[HIPÓTESE]` / `[DESCONHECIDO]` |
| Se `CONNECT`/heartbeat são exigidos para o patinete aceitar LOCK/UNLOCK | `[DESCONHECIDO]` |

### 7.2 Etapa 4.3a — código sem patinete (pode começar após aprovação do plano; não toca o patinete)
- `core-protocol`: construtores `connectFrame()` e `heartbeatFrame()` com **testes cujos valores esperados são os bytes literais acima** (não calculados pelo código); `parseAck` (tipo 05) tolerando CRC de resposta inválido (já `[DESCONHECIDO]`).
- **Lista de permissão por tipo:** `TxCommand` fechado (`Connect`, `Heartbeat`, e, só na 4.4, `Unlock`). `ScooterTransport.send()` continua recusando qualquer outra coisa; não existe API genérica "enviar bytes".
- `BleScooterTransport`: `HeartbeatLoop` (corrotina, período 1 s, injetável/`TestScope` com relógio virtual); inicia **somente** após `Connect` com ACK; para em desconexão, em "Parar/Desconectar" e se 3 heartbeats seguidos ficarem sem ACK (≥ 3 s) → trata como enlace perdido (Recuperação). Heartbeat **não** é comando da UI (sem pendência, sem "concluído").
- Chave de depuração separada e **desligada por padrão**: `--ez ble_tx true` (distinta de `ble_real`), só no build debug; sem ela, o transporte real continua só escrevendo CCCD como hoje.
- Reação ao bloqueio/desconexão: ao perder o enlace a Home mantém o último estado marcado como **desconhecido/velho** (regra 5; `meter1Stale`), nunca "bloqueado" por dedução; ao reconectar, o estado vem **só** do Meter1 (`dado[1]` bit5). Teste com `FakeGattClient`: enlace cai → UI não afirma bloqueio; Meter1 `0x30` → "Bloqueado (senha)".
- Remover qualquer log temporário `IX7BLE` (já fora da `main`; checar com `grep -r IX7BLE app/` no gate).
- Processo: TDD (vermelho→verde), worker ≠ revisor, Sonnet independente + Codex Sol medium (mudança de BLE), `clean check` EXIT 0 antes do merge local.

### 7.3 Etapa 4.3b — teste real de CONNECT + heartbeat (patinete **ligado, parado, no cavalete**, proprietário presente, app original **fechado/desinstalado**)
Escrita real nova, só `CONNECT 0x30` (1×, aguarda ACK) e `0x10` a cada 1 s. Nada de LOCK/UNLOCK/WR de configuração.
1. Salvar logcat/log próprio logo ao terminar (`adb logcat -d -v threadtime > research/captures/s4-3b/…`); o log próprio registra os bytes TX/RX (PIN nunca, pois não há PIN aqui).
2. Partir **desbloqueado se possível** (proprietário desbloqueia no original antes, depois fecha/desinstala-o). Se estiver bloqueado, aceitar e anotar.
3. Conectar, enviar `Connect`, manter heartbeat por **5 min**; critério: ACK em cada heartbeat, Meter1/2 estáveis, bit de bloqueio constante.
4. **Teste da hipótese do bloqueio** (um fator por vez, depois de 3): fechar o app (heartbeat para) e registrar `dado[1]` até o enlace cair + 60 s após religar a telemetria só com CCCD (sem Connect/heartbeat). Observar se o cadeado aparece. Resultado vira `[CONFIRMADO]` ou a hipótese é descartada; **não** concluir causa de um único ensaio.
5. Abortar: botão "Parar/Desconectar", ou desligar o patinete; qualquer ACK ausente > 3 s, quadro inesperado, movimento da roda.
Risco aceito a confirmar: o ensaio do passo 4 pode **bloquear** o patinete → o proprietário desbloqueia no original (PIN dele) depois.

### 7.4 Etapa 4.4 — desbloqueio com o PIN do proprietário (**aprovação específica, separada**, um comando por vez, parado no cavalete, proprietário presente, nunca em movimento)
- Entrada: somente `Unlock` = `LOCK 0x32` TIPO 02 LEN 5, **modo 0 + 4 dígitos**, igual ao observado. PIN digitado pelo proprietário no momento; guardado só em Keystore/EncryptedSharedPreferences se ele autorizar; **nunca** em log, tela de teste, git ou prompt (o log próprio mascara os 4 bytes).
- **Proibidos no código e nos testes (assert de lista fechada):** modo 1 (definir/alterar PIN), modo 3 (limpar), qualquer outro valor de modo, `0x33…0x39` e demais comandos. Bloquear (modo 2) fica **fora** da 4.4 (com PIN ativo o efeito é `[DESCONHECIDO]`); decisão futura própria.
- Pré-condições automáticas: enlace com Connect+heartbeat vivos (4.3b ok); Meter1 recente (idade < 2 s); velocidade = 0 em Meter2/Meter1 (se desconhecida → recusa); `dado[1]` bit5 = 1.
- **Uma única tentativa** por ação do usuário; sem retry automático (PIN errado pode acionar bloqueio temporário `[DESCONHECIDO]`). Resposta tipo `0x89` → mostrar "PIN incorreto", parar, não reenviar.
- Regra 5: UI "Enviado/aguardando" ao enviar; "Desbloqueado" **só** quando Meter1 `dado[1]` bit5 = 0 chega (esperado ≈ 0,7 s; prazo 3 s, depois "sem confirmação" + estado desconhecido). ACK tipo 05 sozinho **não** vale.
- Critério de sucesso: ACK ≤ 200 ms + bit5 → 0 em ≤ 3 s, 1 ensaio. Abortar: ACK ausente > 2 s, estado divergente, qualquer comportamento inesperado → Parar/Desconectar.

### 7.5 Fora de escopo (continua)
Definir/alterar/limpar PIN, contornar antifurto/autenticação, comandos de modo/farol/limite, movimento, firmware, nuvem.

### 7.6 Aprovações por escrito pedidas ao proprietário (marque cada uma; sem resposta = bloqueado)
1. **4.3a** (código, sem patinete): autorizo? sim/não
2. **4.3b**: autorizo enviar `CONNECT 0x30` + heartbeat `0x10` (1 s), patinete parado no cavalete, na minha presença? sim/não — e aceito o ensaio de bloqueio do passo 4? sim/não
3. **4.4**: autorização separada, **depois** do resultado da 4.3b (não é concedida por este plano).
4. App original no celular: **restaurar o original** (desinstalar o reempacotado `com.iscooter.car` e reinstalar o APK de `research/original-apk/`) ou manter o reempacotado (logger IX7TAP) por enquanto?
5. Rotacionar o PIN (1234 apareceu em print) e o token do Codex: quando?

### 7.7 Aprovações do proprietário (2026-11-05, sessão 18, resposta no chat)
1. 4.3a: **sim**. 2. 4.3b (`CONNECT 0x30` + heartbeat `0x10`, incl. ensaio de bloqueio): **sim**. 3. 4.4: **autorizado** — execução continua condicionada a 4.3b ok, patinete parado no cavalete, proprietário presente, um comando por vez (§7.4). 4. Manter o app reempacotado por enquanto. 5. PIN e token do Codex já rotacionados (o PIN antigo/1234 não vale mais; o PIN atual só o proprietário digita). O patinete desligou sozinho: o proprietário religa quando eu avisar.
