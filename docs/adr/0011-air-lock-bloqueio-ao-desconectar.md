# ADR 0011 — Air Lock (CMD 0x41): bloquear ao desconectar
Status: aceito para implementação e TESTE REAL pendente · Efeito de d=1 `[HIPÓTESE]` · Decisão do proprietário: 2026-11-09 (sessão S34, "implementar e testar") · Pesquisa: `docs/research/parts/S34-bloqueio-ao-desconectar.md` · Mesmo padrão dos ajustes de configuração dos ADRs 0009 e 0010

## Decisão
1. A preferência antes chamada "Bloquear usando PIN" (`Preferences.lockWithPin`) passa a significar o **Air Lock** do original: título "Bloquear ao desconectar", selo "Em teste". Marcada = o patinete bloqueia sozinho quando o app desconecta e desbloqueia ao reconectar; desmarcada = não bloqueia ao desconectar. A chave persistida `lockWithPin` não muda (sem migração de dados).
2. `TxCommand.AirLock(on)`: `APP_CURRENT_USER_CAR` CMD 0x41, TIPO 02, LEN 1, dado 01 (marcada) / 00 (desmarcada): `1A A1 41 02 01 <d> CRC 1F F1`. API pública só com `Boolean`; CRC pela função existente.
3. `ScooterTransport.setAirLock(on): AirLockResult` (`Refused` / `Accepted` / `Rejected` / `Unconfirmed`). Travas: TX habilitado, enlace Conectado com ACK recente de CONNECT/heartbeat, Meter1 fresco, roda parada e velocidade 0, nenhuma recepção pendente, um comando por vez. Diferente de tração/cruise, **pode** ser enviado com o patinete bloqueado (o original o envia logo ao conectar). Sem campo de telemetria que confirme o efeito: o melhor resultado é `Accepted` (ACK 0x05 = recebido). A UI diz "Comando aceito pelo patinete. Efeito ainda em validação.", nunca "concluído" (regra 5). ACK 0x89 = `Rejected`, sem reenvio.
4. Envio: (a) ao alternar a preferência com o patinete pronto (conectado com telemetria); (b) uma vez por sessão ao ficar pronto depois do CONNECT/ACK, enviando o valor atual (também `false`). Sem a repetição do original (a cada 2 s até 14x): 1 escrita e, sem ACK em `ackTimeoutMs` (2 s), 1 reenvio; depois `Unconfirmed`. Recusas locais (telemetria velha/recepção pendente/ocupado, nada escrito) são reavaliadas até 3 vezes com 2 s entre elas. Em movimento: recusa e aviso, nunca envia.
5. Aviso no Preferências (padrão do aviso do cofre): recusado, sem confirmação, em movimento, não enviado.

## Evidências
- `[ESTÁTICO]` texto do original (`values-en/strings.xml:19-22`): ligado, o veículo bloqueia quando o app desconecta por Bluetooth e desbloqueia ao reconectar. Código: `DeviceCarControlFrag.java:2123-2133` (`sendHasCurrentUserCar`), `q3/a.java:99-103`.
- `[CONFIRMADO]` d=0: `1aa141020100b4481ff1` em 12 capturas do original (`s13pin/ix7tap-pin.txt:13-15`, `s2d/ix7tap-roteiro2.dedup.txt:5-7`) com ACK `1aa141 05`. Fixado em `TxFrameTest`.
- `[HIPÓTESE]` d=1: nunca capturado; o CRC é calculado (teste confere a estrutura e a função). O efeito no firmware (bloquear ao perder o link) segue hipótese.
- `[DESCONHECIDO]` se o valor persiste no patinete ou só vale com o laço de reenvio do original.

## Valor padrão e migração
O padrão continua `false` (desmarcada): é o valor que o original enviava com o switch desligado (capturado) e o patinete do dono já bloqueia ao desconectar sem o app novo ter enviado 0x41 (`[CONFIRMADO]` como observação, causa `[DESCONHECIDO]`). Consequência: ao conectar, o app novo passa a enviar `0x41 = 0`, que o original já enviava; se isso mudar o comportamento atual do patinete, o teste real mostrará. Instalações existentes: a chave `lockWithPin` guardada continua valendo; quem a marcou no texto antigo ("Em breve: ainda sem efeito") passa a enviar 0x41 = 1 na próxima conexão, por isso o aviso "Em teste" e o teste real antes de uso diário.

## Riscos e conflitos
- **Desbloqueio automático na reconexão** (texto do original, d=1): contraria a regra 6 do CLAUDE.md se ocorrer sem o app pedir PIN. Aqui é função do firmware com o Bluetooth já pareado do dono (equipamento próprio); o app não contorna antifurto nem autenticação, mas **o risco é registrado** e o teste deve verificar se desbloqueia sozinho.
- Se d=1 não funcionar como suposto, é comportamento do firmware: o app não configura tempo nem condição.
- O envio não depende do original estar fechado; o original, se aberto, reenviaria o seu valor (usar um de cada vez).
- Patinete em movimento na conexão: o ajuste não é enviado (aviso); alternar a preferência depois de parar reenvia.

## Teste real (obrigatório antes de `[CONFIRMADO]`)
Só com "pode" do proprietário no chat, patinete parado no cavalete, feito pelo coordenador, conforme `S34-bloqueio-ao-desconectar.md` §(c): enviar d=0 e d=1 com ACK, Desconectar e observar 5 min se bloqueia, reconectar só por CCCD e ver se desbloqueia sozinho; abortar com 0x89 ou qualquer comportamento inesperado. Até lá, d=1 e o efeito permanecem `[HIPÓTESE]`.
