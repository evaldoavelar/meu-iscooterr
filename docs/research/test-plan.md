# T12 — Plano de testes experimentais (Sprint 2) — v2
Aprovado pelo proprietário em 2026-11-03 **com as condições abaixo**. Qualquer desvio exige nova aprovação.

## Condições de aprovação (vinculantes)
1. **Sem PIN incorreto deliberado** (H8 reduzida a observação passiva).
2. **Sem comandos próprios.** Somente captura passiva do app **original** operado pelo proprietário, patinete **parado no cavalete**. Nenhum envio por nós (adb, scripts, GATT) ao patinete.
3. H7 dividida em **H7A** (lock com proximidade desativada) e **H7B** (funcionamento sem internet, **mantendo a sessão autenticada**). Sem logout, sem redefinir vínculo, sem desparear.
4. Registrar tempos: descoberta (scan), conexão, autenticação, comando, ACK e confirmação do estado real.
5. **Capturas brutas nunca são versionadas** (ficam em `research/captures/`, ignorado). Relatórios só nascem de saída **sanitizada** (PIN, MAC completo, SN, tokens, uid mascarados **antes** de gerar o relatório). Não colar bytes brutos de PIN em nenhum documento.
6. Revisão cruzada por Codex dos relatórios; o líder verifica cada achado no código/captura.
7. Não tocar em processos de outros projetos.

## Preparação
1. Opções do desenvolvedor: “Registro de snoop HCI Bluetooth” ligado; religar o Bluetooth. `adb logcat -c`.
2. Patinete parado no cavalete, sem pessoa montada. Parar imediatamente se mover.
3. Após o roteiro: `adb bugreport` (ou pull do btsnoop) → `research/captures/` (ignorado) → sanitizador `scripts/analysis/btsnoop_sanitize.py` (uso: `python3 scripts/analysis/btsnoop_sanitize.py <arquivo>`; teste em `tests/analysis/test_btsnoop_sanitize.py`) → relatório em `docs/research/parts/S2-captura-resultados.md`.

## Hipóteses
| ID | Hipótese | Roteiro (proprietário, app original) | Critério |
|----|----------|--------------------------------------|----------|
| H1 | GATT FF60 / notify FF61 / write FF62 | Conectar | Descoberta de serviços mostra os UUIDs |
| H2 | Quadro `1A A1 … CRC16 … 1F F1`, CRC Modbus | Qualquer ação | CRC recalculado confere; ordem de bytes |
| H3 | Heartbeat 0x10 mantém a sessão (não fixo em 1 s) | Conectar, ficar 30 s ocioso | Quadros 0x10 e respostas Meter1 |
| H4 | LOCK 0x32: modo 1 bloquear / 0 desbloquear | Bloquear e desbloquear | Estrutura de DADOS (PIN mascarado) |
| H5 | Estado real: Meter1 byte[6] bit5 (1=bloqueado) | Alternar lock | Bit muda após o comando |
| H6 | CONNECT 0x30(1) na sessão / 0x30(0) ao sair | Abrir/fechar | Presença e ordem |
| H7A | Lock/unlock funciona com 0x41=0 (proximidade **desativada** nas configurações do app) | Desativar recurso de proximidade; bloquear/desbloquear | Comando aceito, ACK 0x05, bit5 muda |
| H7B | Funciona sem internet **com sessão mantida** | Desligar Wi-Fi/dados do telefone (sem logout); bloquear/desbloquear | Idem; registrar se o app bloqueia a ação por falta de rede |
| H8 | ACK 0x05/CMD 0x32; erro 0x89 (apenas se ocorrer naturalmente) | Observação passiva | Quadros observados; atraso ACK→bit5 |
| H9 | Identificação no anúncio (nome/UUID/manufacturer) e estabilidade do endereço | Scan com app fechado/aberto; religar patinete **só se você quiser** | Campos do anúncio (sanitizados); endereço muda ou não |
| H10 | Bateria % e velocidade no Meter1 | Observar | Coerente com o painel |
| **H11** | **Operar lock/unlock sem pedir PIN repetidamente** | ver abaixo | ver abaixo |

**Nota H7B:** H7B testa dependência de rede do app, não independência de sessão/login; independência de login só poderia ser testada em app novo ou com nova aprovação (sem logout).

## H11 — uso sem digitação recorrente de PIN
Meta do proprietário: experiência mais rápida; sem PIN manual repetido e sem nuvem **se não for exigência técnica legítima**. Sem contornar antifurto/posse.

**Evidência estática [ESTÁTICO]** (código; não é prova de comportamento):
- E1. `lockHasPwd` (bit4 de Meter1 byte[6]) indica se o patinete tem PIN configurado. Com `lockHasPwd==0` e desbloqueado, a UI oferece modo 1 (“lock”) e modo 2 (“bloqueio temporário”). Modo 1 abre o diálogo de PIN (`DeviceCarControlFrag.java:3091`, `:3609→showLockPwdDiag(1)`), envia `l(1,PIN)` (`:2705`) e, após ACK, **grava o PIN digitado** (`:3318-3326`): ou seja, bloquear no modo 1 **define um PIN no patinete**. Modo 2 é enviado como `l(2,"")`, sem PIN (`:3103`). Modo 3 limpa o PIN. Com `lockStatus==1` e `lockHasPwd==0`, o desbloqueio é `l(0,"")` (`:3622`).
  - **Regra vinculante:** nesta sprint e nos testes do app novo NÃO se envia modo 1 com PIN novo nem modo 3; qualquer comando próprio só em sprint futura com nova aprovação.
- E2. Com `lockHasPwd==1`, o quadro LOCK carrega 4 dígitos (`q3/a.java:136-147`).
- E3. O app original **guarda o PIN após ACK de sucesso** (`:3318-3326`, `LOCK_PWD_LPW` e chave MAC+uid) e **reusa-o sem perguntar** para desbloquear (`:1407-1441`, log “直接解锁”) quando logado e dono. Em erro de PIN (tipo 0x89) só `LOCK_PWD_LPW` é limpo (`:3363`); a cópia por MAC+uid gravada em `:3324` permanece e é lida em `:868` (getLockPwd), logo a invalidação do cache é incompleta. Logo, **o cliente serializa o PIN no quadro** (`q3/a.java:136-147`, prova apenas do lado do cliente; o PIN trafega no quadro, sem cifra visível). `[HIPÓTESE]`: aceitação do PIN pelo firmware e ausência de desafio/nonce — a confirmar em captura.
- E4. O PIN é espelhado no servidor (`devices/setPassword`), recurso de conveniência do app original; `[HIPÓTESE]` não é requisito do firmware. Envio do PIN ao servidor: `DeviceCarControlFrag.java:3845` e `netw/HttpsRequests.java:319`.
- E5. Armazenamento do PIN no app original: `utilss/PreferenceSpUtil.java:239,327` (SharedPreferences direto), provável texto claro.

**Hipóteses de decisão (a confirmar):**
- H11-a: após ACK de sucesso, o patinete aceita lock/unlock subsequentes com o mesmo PIN sem novo prompt do usuário, em sessão curta e longa. **Confirmação exige** mudança do bit5 de Meter1 (estado real) **e** ACK 0x05; reenvio do PIN guardado sozinho **não** confirma. Repetir lock/unlock após ≥ 10 min ocioso (sessão longa).
- H11-b: com `lockHasPwd==0` nenhuma digitação é necessária (estado do seu patinete: **verificar** pelo bit4 capturado, sem alterá-lo).
- H11-c: não há exigência de sessão/nuvem para H11-a/b (une com H7A/H7B).

**Roteiro H11 (passivo):** (1) observar `lockHasPwd` do seu patinete na telemetria; (2) executar lock → unlock no app original duas vezes seguidas; registrar se o app pede PIN na 2ª vez e se o quadro 0x32 traz dígitos (mascarados no relatório); (3) repetir com internet desligada (H7B).

**Decisão de implementação (provisória, sujeita aos resultados):**
- Padrão: **cadastro único** do PIN do proprietário, armazenado cifrado (Android Keystore, chave não exportável, uso opcionalmente atrelado a biometria/desbloqueio do aparelho), usado automaticamente nas operações; toque único. (O app original armazena em texto claro — E5; o app novo não replica isso.)
- Se `lockHasPwd==0`: operar sem PIN apenas com desbloqueio `l(0,"")` e, para bloquear sem PIN, o modo 2 (a confirmar em captura); **nunca modo 1 sem o proprietário decidir criar PIN.**
- **Não** desativar/alterar o PIN do patinete (modo 3/“limpar senha”) nem tentar adivinhar PIN. Remover PIN do patinete é decisão do proprietário, fora desta sprint (exigiria envio de comando).
- Sem login/nuvem, a menos que H7A/H7B/H11-c indiquem exigência técnica legítima (então registrar a limitação e propor alternativa legítima antes de codar).

## Medição de tempos (condição 4)
A partir do btsnoop (timestamps HCI) e logcat: `t_scan` (início do scan do app — primeiro evento LE Set Scan Enable — até a seleção do dispositivo), `t_conn` (CREATE_CONNECTION → conexão estabelecida), `t_auth` (CONNECT 0x30 enviado pelo cliente → primeira resposta do patinete a ele; o CONNECT é enviado pelo cliente, não há desafio de autenticação identificado — `[DESCONHECIDO]`), `t_cmd` (toque no app/escrita 0x32), `t_ack` (0x32 → resposta 0x05), `t_state` (0x32 → Meter1 com bit5 alterado). Mínimo 5 repetições nesta sprint (as 30 tentativas por cenário da spec entram na Fase 6, com o app novo). Reportar mediana e máx.

## Segurança
Interromper se houver movimento, aviso no painel ou comportamento inesperado. Sem laços de comando. Registrar versão do app 1.0.20 e do firmware (se visível).

## Entregáveis
`docs/research/parts/S2-captura-resultados.md` (somente dados sanitizados, gerados com `scripts/analysis/btsnoop_sanitize.py`; teste em `tests/analysis/test_btsnoop_sanitize.py`), atualização de rótulos H1–H11 e do ADR 0002 e das decisões de implementação.
