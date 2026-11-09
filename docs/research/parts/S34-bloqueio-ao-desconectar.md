# S34 — Existe configuração no patinete para "bloquear ao desconectar"? (análise estática, somente leitura)

Pergunta: "Bloquear usando PIN" marcada = ao desconectar do app o patinete bloqueia; desmarcada = não bloqueia. Nada foi enviado ao patinete; só leitura de `research/decompiled/` e das capturas/docs.

## (a) Achados

Caminhos abreviados: `J` = `research/decompiled/jadx/sources/com/iscooter/car/`; `A` = `research/decompiled/apktool/res/`.

| # | Achado | Classe | Evidência |
|---|---|---|---|
| 1 | O app original tem um recurso "Air Lock / Verrou auto": "se ligado, o veículo bloqueia automaticamente quando o APP desconecta do veículo por Bluetooth e desbloqueia ao reconectar; se a conexão cair por bateria do celular, bloqueia e não desbloqueia" | `[ESTÁTICO]` (texto de UI) | `A/values-en/strings.xml:19-22` (`auto_lock*`); pt-BR: `A/values-pt/strings.xml:49` ("Déconnexion BT = verrouillage auto") |
| 2 | O switch é uma tela de configuração do dispositivo (`switch_distance`, `rl_distance_switchs`); o slider de distância 5-10 m (`rl_distances`) está `visibility=gone` e o valor `CAR_DISTANCE_CURRENT` não é lido em nenhum outro lugar (sem uso de RSSI) | `[ESTÁTICO]` | `A/layout/aty_device_setting_ds.xml:48-54`; `J/uis/car/act/DeviceCarSettingAty.java:421-451`; grep `CAR_DISTANCE_CURRENT`: só nesse arquivo + `PreferenceSpUtil.java:21` |
| 3 | O switch só grava preferência LOCAL (`switchdistanceclick`) e dispara evento `EventQuery(1)`; não escreve nada direto no patinete | `[ESTÁTICO]` | `DeviceCarSettingAty.java:716-721`; `utilss/PreferenceSpUtil.java:24` |
| 4 | O switch só aparece se a lista de funções vinda da NUVEM do fabricante contém `bluetooth_disconnect_lock` e o patinete está no cache de "meu dispositivo" (conta logada + vínculo) | `[ESTÁTICO]` | `DeviceCarSettingAty.java:1018,1031-1041`; `utilss/Constant.java:59` |
| 5 | Ao conectar, o app envia periodicamente (a cada 2 s, até `readICount>=14`) o CMD `APP_CURRENT_USER_CAR` = **0x41** (65), tipo 02, LEN 1, dado = 1 se (switch ligado E o patinete é do usuário logado/vinculado) senão 0. Esse é o único caminho do código ligado ao switch | `[ESTÁTICO]` | `blue/BleConstants.java:14-15`; `uis/car/frag/DeviceCarControlFrag.java:2123-2133` (`sendHasCurrentUserCar`), `:285-301` (laço 2 s/14x), `:3307-3309` (ACK 0x41 rearma o laço), chamadores `:768-789,1638-1711`; montagem `q3/a.java:99-103` (`g(i)` = `d(0x41, 2, [i], true)`) |
| 6 | Capturas reais do app original mostram TX `1aa1 41 02 01 00 b448 1ff1` (0x41 com dado **00**, switch desligado) → ACK `1aa1 41 05 …`. 12 ocorrências; **dado 01 nunca foi capturado** | `[CONFIRMADO]` só o frame com 00 e o ACK; efeito de 01 `[DESCONHECIDO]` | `research/captures/s13pin/ix7tap-pin.txt:13-15`; `research/captures/s2d/ix7tap-roteiro2.dedup.txt:5-7`; grep `1aa14102 0101` = 0 ocorrências |
| 7 | Que 0x41=01 faça o FIRMWARE bloquear ao perder o link (e desbloquear ao reconectar) | `[HIPÓTESE]` (coerente com a UI e o nome "current user car"; sem teste) | achados 1 e 5 |
| 8 | Nenhum CMD de "desligamento automático"/"tempo ocioso"/"sleep" existe no app: `BleConstants` lista todos os CMDs (0x10, 0x30-0x3C, 0x41, 0x42, 0x45, 0x60-0x63, 0x66, 0x67, 0x91-0x92); strings sem "auto power off/standby/idle" | `[ESTÁTICO]` (negativo) | `blue/BleConstants.java:10-64`; grep em `A/values-en/strings.xml` |
| 9 | Sem CONNECT 0x30/heartbeat 0x10 o patinete continua a mandar telemetria; fechar o app (`force-stop`) e ficar ~100 s sem heartbeat NÃO bloqueou | `[CONFIRMADO]` | `docs/research/parts/S18-4-3b-tentativa1.md:19-20` |
| 10 | Bloqueios sozinhos observados: após a escuta pura da S17 (foto com cadeado, patinete com PIN, bit `lock_has_pwd`=1) e na S21 (durante escuta pura, sem toque). Causa e tempo exatos desconhecidos (hipótese S18: timeout maior, desligamento automático ~10 min, interação com app original) | observação `[CONFIRMADO]` (relato+foto); causa `[DESCONHECIDO]` | `S17-4-2-tentativa1.md:38,48`; `S21-lock-modo2-real.md:8`; `S18:20` |
| 11 | Patinete desbloqueou e apitou sozinho com o app congelado em segundo plano (S29); causa não provada | `[HIPÓTESE]` | `S29-cofre-primeiro-uso-real.md:12,23` |
| 12 | Com heartbeat 1 Hz em segundo plano (serviço em primeiro plano) o link se mantém e "Desconectar" encerra a sessão sem reconexão automática; **não há registro do que o patinete fez depois do Desconectar** (bloqueou? em quanto tempo?) | `[CONFIRMADO]` o link/encerramento; pós-desconexão `[DESCONHECIDO]` | `S30-fgs-e-unlock-automatico-real.md:7,9`; `S29:21` |
| 13 | `lockHasPwd` (Meter1 dado[1] bit4) só informa estado/orienta a UI do original (`checkLockPwdDiag`); nenhuma evidência de que influencie bloqueio por perda de link | `[ESTÁTICO]` o uso na UI; influência no firmware `[DESCONHECIDO]` | `DeviceCarControlFrag.java:1017,1341,1412-1420`; `S13-pin-captura.md:7,9` |
| 14 | Que manter heartbeat impeça o bloqueio automático | `[HIPÓTESE]`; indício a favor: link mantido por >80 s sem bloqueio (S30) mas o patinete estava já desbloqueado só por instantes e o teste não visou isso | `S30:7` |

## (b) Existe comando configurável?

Há um candidato, não um comando provado.

- Mecanismo do original `[ESTÁTICO]`: o app manda **CMD 0x41, TIPO 0x02, LEN 1, dado = 0x00 ou 0x01** (quadro `1A A1 41 02 01 <d> CRC 1F F1`, CRC como os demais quadros do projeto; exemplo real com d=00: `1aa141020100b4481ff1`). O original liga o dado a 1 apenas quando o switch "Air Lock" está ligado; o rótulo da UI diz que, ligado, o veículo bloqueia quando o BT cai e desbloqueia na reconexão.
- Estado da prova: d=00 `[CONFIRMADO]` (capturado, ACK recebido); d=01 e o efeito real no firmware `[HIPÓTESE]`/`[DESCONHECIDO]`. Não se sabe se o valor é persistente no patinete ou só vale enquanto a sessão envia o laço de 2 s. Nenhuma captura do original com o switch ligado existe.
- Se 0x41=01 não funcionar como suposto, é comportamento do firmware: o app não consegue configurar tempo nem condição; só pode (i) bloquear ele mesmo antes de desconectar (CMD 0x32 modo 2, já aprovado) e (ii) manter o heartbeat. Não existe CMD de timer de desligamento/ocioso no app original.
- Atenção ao mapeamento da pergunta: o mecanismo 0x41 liga "bloqueio ao perder o link" **e** "desbloqueio automático ao reconectar" (texto do original). A preferência "Bloquear usando PIN" do app novo é outra coisa (modo do LOCK 0x32); o mapeamento do proprietário (marcada = 0x41=1, desmarcada = 0x41=0) seria uma reutilização de nome e precisa de decisão/ADR. O "já acontece hoje" sem o app novo ter enviado 0x41 sugere que o estado padrão/persistido do patinete (talvez deixado pelo app original) ou outro mecanismo do firmware já bloqueia: `[DESCONHECIDO]`.
- Efeito colateral possível de 0x41=01: desbloqueio automático na reconexão (contraria a regra 6 se ocorrer sem PIN); o teste deve verificá-lo.

## (c) Teste real mínimo e seguro (só com "pode" do proprietário no chat)

Pré-condições: patinete parado no cavalete, desligado do movimento, proprietário presente, app original fechado, logcat `IX7TX` ligado, PIN do proprietário só por ele.

1. Linha de base (0 TX novo): conectar (CONNECT + heartbeat), desbloquear pelo app, **Desconectar** pelo chip; observar o painel por 5 min e registrar quando/se o cadeado aparece (Meter1 bit5 numa reconexão só-CCCD depois). Repetir uma vez para ter linha de base com a preferência "desmarcada" de hoje.
2. Experimento A (um comando, reversível): conectar, enviar **uma vez** `0x41 02 01 00` (já visto) e confirmar o ACK; enviar `0x41 02 01 01`, esperar ACK `1aa141 05 …`; manter heartbeat 30 s; Desconectar pelo chip. Observar o painel por 5 min (bloqueia? em quanto tempo?). Reconectar só por CCCD e ler Meter1; não enviar Unlock; ver se desbloqueou sozinho.
3. Voltar a `0x41 02 01 00` na próxima sessão e repetir o passo 1 para ver se o efeito depende do valor (persistência: o valor 01 ainda vale após o app fechar/reconectar sem reenviar?).
4. Critério de aborto: qualquer bloqueio/desbloqueio inesperado com o patinete em movimento, ACK de erro (tipo 0x89) ou comportamento diferente do esperado, parar e documentar. Nunca repetir automaticamente; nenhuma outra CMD 0x40-0x70 experimental.
5. Só implementar a preferência no app novo depois de `[CONFIRMADO]` com ADR; o rótulo/semântica da UI deve refletir "bloqueio ao perder o link", não "PIN".
