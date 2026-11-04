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
