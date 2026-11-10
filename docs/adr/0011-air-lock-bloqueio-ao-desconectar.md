# ADR 0011 — Air Lock (CMD 0x41): bloquear ao desconectar
Status: aceito · Teste real feito (S37, S40): efeito de d=1 `[CONFIRMADO]` (bloqueia em 2–3 s) · Decisão do proprietário: 2026-11-09 (sessão S34, "implementar e testar") · Pesquisa: `docs/research/parts/S34-bloqueio-ao-desconectar.md` · Mesmo padrão dos ajustes de configuração dos ADRs 0009 e 0010

## Decisão
1. A preferência antes chamada "Bloquear usando PIN" (`Preferences.lockWithPin`) passa a significar o **Air Lock** do original: título "Bloquear ao desconectar", selo "Em teste". Marcada = o patinete deve bloquear sozinho quando o app desconecta e desbloqueia ao reconectar; desmarcada = não bloqueia ao desconectar. A chave persistida `lockWithPin` não muda (sem migração de dados).
2. `TxCommand.AirLock(on)`: `APP_CURRENT_USER_CAR` CMD 0x41, TIPO 02, LEN 1, dado 01 (marcada) / 00 (desmarcada): `1A A1 41 02 01 <d> CRC 1F F1`. API pública só com `Boolean`; CRC pela função existente.
3. `ScooterTransport.setAirLock(on): AirLockResult` (`Refused` / `Accepted` / `Rejected` / `Unconfirmed`). Travas: TX habilitado, enlace Conectado com ACK recente de CONNECT/heartbeat, Meter1 fresco, roda parada e velocidade 0, nenhuma recepção pendente, um comando por vez. Diferente de tração/cruise, **pode** ser enviado com o patinete bloqueado (o original o envia logo ao conectar). Sem campo de telemetria que confirme o efeito: o melhor resultado é `Accepted` (ACK 0x05 = recebido). A UI diz "Comando aceito pelo patinete. Efeito ainda em validação.", nunca "concluído" (regra 5). ACK 0x89 = `Rejected`, sem reenvio.
4. Envio: (a) ao alternar a preferência com o patinete pronto (conectado com telemetria); (b) uma vez por sessão ao ficar pronto depois do CONNECT/ACK, enviando o valor atual, SOMENTE se o dono já tocou na opção (`airLockTouched`). Sem a repetição do original (a cada 2 s até 14x): 1 escrita e, sem ACK em `ackTimeoutMs` (2 s), 1 reenvio; depois `Unconfirmed`. Recusas locais (telemetria velha/recepção pendente/ocupado, nada escrito) são reavaliadas até 3 vezes com 2 s entre elas. Em movimento: recusa e aviso, nunca envia.
5. Aviso no Preferências (padrão do aviso do cofre): recusado, sem confirmação, em movimento, não enviado.

## Evidências
- `[ESTÁTICO]` texto do original (`values-en/strings.xml:19-22`): ligado, o veículo bloqueia quando o app desconecta por Bluetooth e desbloqueia ao reconectar. Código: `DeviceCarControlFrag.java:2123-2133` (`sendHasCurrentUserCar`), `q3/a.java:99-103`.
- `[CONFIRMADO]` d=0: `1aa141020100b4481ff1` em 12 capturas do original (`s13pin/ix7tap-pin.txt:13-15`, `s2d/ix7tap-roteiro2.dedup.txt:5-7`) com ACK `1aa141 05`. Fixado em `TxFrameTest`.
- `[HIPÓTESE]` d=1: nunca capturado; o CRC é calculado (teste confere a estrutura e a função). O efeito no firmware (bloquear ao perder o link) segue hipótese.
- `[DESCONHECIDO]` se o valor persiste no patinete ou só vale com o laço de reenvio do original.

## Valor padrão e migração
- Padrão `false` (desmarcada) e a chave `lockWithPin` inalterada. Nova flag persistida `airLockTouched` (padrão `false`), que vira `true` na 1ª alternância do switch pelo dono.
- **Envio automático ao conectar só ocorre se `airLockTouched == true`.** Quem nunca mexeu na opção (inclusive quem já tinha `lockWithPin = true` no texto antigo "Em breve") NÃO recebe 0x41 até alternar o switch. Alternar continua enviando na hora (patinete conectado e pronto) e marca a flag. Motivo: o patinete do dono já bloqueia ao desconectar sem o app novo ter enviado 0x41 (observação `[CONFIRMADO]`, causa `[DESCONHECIDO]`); não alteramos esse estado sem um gesto do dono.
- A decisão do proprietário de 2026-11-09 ("implementar e testar") supera o critério "`[CONFIRMADO]` antes de implementar" da pesquisa S34 §(c)5. Por isso o envio automático só vale depois que o dono tocar na opção, e o teste real segue obrigatório.

## Riscos e conflitos
- **Desbloqueio automático na reconexão** (texto do original, d=1): contraria a regra 6 do CLAUDE.md se ocorrer sem o app pedir PIN. Aqui é função do firmware com o Bluetooth já pareado do dono (equipamento próprio); o app não contorna antifurto nem autenticação, mas **o risco é registrado** e o teste deve verificar se desbloqueia sozinho.
- Se d=1 não funcionar como suposto, é comportamento do firmware: o app não configura tempo nem condição.
- O envio não depende do original estar fechado; o original, se aberto, reenviaria o seu valor (usar um de cada vez).
- Patinete em movimento na conexão: o ajuste não é enviado (aviso); alternar a preferência depois de parar reenvia.

## Teste real (obrigatório antes de `[CONFIRMADO]`)
Só com "pode" do proprietário no chat, patinete parado no cavalete, feito pelo coordenador, conforme `S34-bloqueio-ao-desconectar.md` §(c): enviar d=0 e d=1 com ACK, Desconectar e observar 5 min se bloqueia, reconectar só por CCCD e ver se desbloqueia sozinho; abortar com 0x89 ou qualquer comportamento inesperado. Até lá, d=1 e o efeito permanecem `[HIPÓTESE]`.

## Dívida do protótipo (regra 2) — quitada na S35
`design/home.figcraft` atualizado na Sessão 35 com a linha "Bloquear ao desconectar" e selo "Em teste"; teto de `ScreensScreenshotTest` para "Configuracoes" revertido de 0.0622 para 0.0459 (MAD observado 0.0501 claro, 0.0453 escuro, ambos abaixo do teto de 0.0609).

## Adendo S37 (2026-11-10): teste real
Teste real com "pode" (`docs/research/parts/S37-airlock-real.md`): 0x41 d=1 e d=0 recebem ACK (63 ms/25 ms) `[CONFIRMADO]`. Após desconexão pelo chip o patinete bloqueou com d=1 (≤ ~2 min) e também com d=0 (≤ ~24 min, horário exato desconhecido). O firmware bloqueia sozinho depois de um tempo sem link, então o efeito do d=1 não está provado; só um possível encurtamento do prazo `[HIPÓTESE]`. A UI deve manter "Em teste" e texto honesto. Não alterar CMD/bytes.

## Adendo S40 (2026-11-10): prazo medido
Medição cronometrada (`docs/research/parts/S37-airlock-real.md`, seção S40): com d=1 (ACK) o patinete bloqueou 2 s e 3 s após a desconexão pelo chip `[CONFIRMADO]`; com d=0 (ACK) não bloqueou em 3 min nem em 2 min (envio/ACK no log só na primeira; ausência de bloqueio por cronômetro/relato do proprietário). Corrige o adendo S37: o efeito do d=1 existe e é, como interpretação `[HIPÓTESE]` do mecanismo, o encurtamento do prazo de minutos (bloqueio por inatividade do firmware) para segundos. A linha da UI **permanece**; rebaixar/retirar não se justifica. CMD/bytes inalterados. Selo "Em teste" e texto do aviso: decisão do proprietário (pendente). Não testado: desbloqueio automático na reconexão e desconexão por perda de link.

## Adendo S41 (2026-11-10): decisão do proprietário e atualização da UI
Decisão do proprietário em 2026-11-10 com base no efeito `[CONFIRMADO]` da S40 (d=1 bloqueia 2–3 s após desconectar; d=0 não):
1. **Selo "Em teste":** removido da linha "Bloquear ao desconectar" no protótipo FigCraft (`design/screens.py`, `design/home.figcraft`) e no Compose (`SettingsScreen.kt`).
2. **Subtítulo da preferência atualizado:** "Marcado: o patinete bloqueia sozinho poucos segundos depois que o app desconectar. Desmarcado: não bloqueia ao desconectar." Texto honesto com a evidência; não afirma desbloqueio automático ao reconectar.
3. **Texto de comando aceito (`airLockNotice` Accepted):** alterado de "Comando aceito pelo patinete. Efeito ainda em validação." para "Comando aceito pelo patinete. Ele deve bloquear poucos segundos depois que o app desconectar." (regra 5: nunca "concluído"; é "aceito").
4. **O que continua não testado:**
   - Desbloqueio automático ao reconectar `[HIPÓTESE]` (descrito no app original, mas não validado no patinete real).
   - Desconexão por perda de sinal BLE (link loss / out of range) `[HIPÓTESE]` (testes na S40 foram por desconexão explícita pelo chip).
5. **Rótulos:** CMD 0x41 e efeito de bloqueio em 2–3 s após desconexão `[CONFIRMADO]`; hipóteses de reconexão e link loss mantidas como `[HIPÓTESE]`.
