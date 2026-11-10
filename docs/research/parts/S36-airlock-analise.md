# S36 — Air Lock: por que "Bloquear ao desconectar" pode não ter funcionado (análise, sem tocar o patinete)

Contexto: relato do proprietário em 2026-11-10 (fim da S35). Nenhum comando foi enviado ao patinete na S36 até este registro; sem "pode" no chat e sem dispositivo no adb (mDNS lista o celular, mas `adb connect` dá "No route to host"; o log `IX7TX`/Registro de eventos do celular NÃO foi lido).

## O que os prints provam (e o que não provam)

| Fato | Classe |
|---|---|
| 08:47, switch ligado: "Comando aceito pelo patinete. Efeito ainda em validação." = ACK 0x05 do 0x41 d=1 recebido (`HomeViewModel.kt:221`) | `[ESTÁTICO]` no código; ACK em si só pelo print |
| 10:14, switch desligado: "…não enviado agora…" = `Refused` local **antes de escrever** (`HomeViewModel.kt:226-235`). O d=0 NUNCA chegou ao patinete; o último valor que ele recebeu foi d=1 | `[ESTÁTICO]` |
| "Não funcionou" (o patinete não bloqueou ao desconectar) | relato do proprietário, ambíguo (retirado e reafirmado); sem log, sem hora de desconexão, sem informação de PIN/estado | 
| Efeito de d=1 no firmware | `[HIPÓTESE]` não confirmada pelo uso real; **não refutada** |

## Causas possíveis (a investigar, em ordem de plausibilidade)

1. **Laço do original ausente.** O original reenvia 0x41 a cada 2 s até 14 vezes por sessão e rearma o laço a cada ACK (`DeviceCarControlFrag.java:285-301,3307-3309`). O app novo envia 1 vez (+1 reenvio sem ACK). Se o firmware só vale o 0x41 enquanto a sessão o repete, ou exige o valor "fresco" perto da desconexão, o envio único não basta. `[DESCONHECIDO]` (ADR 0011: persistência desconhecida).
2. **Condição de vínculo de conta.** O original só manda d=1 se o switch está ligado **e** o patinete é do usuário logado/vinculado na nuvem do fabricante (`:2123-2133`; função de nuvem `bluetooth_disconnect_lock`). O firmware pode ignorar d=1 de um cliente não vinculado ou o app novo não ter o equivalente do vínculo. Não contornar autenticação de propriedade (regra 6): se for isso, é limitação a registrar (regra 3).
3. **Bloqueio só após timeout de perda do link.** O patinete pode bloquear alguns minutos depois (o proprietário pode ter olhado cedo; S34 §(a)10 mostra bloqueios sozinhos com tempo/causa desconhecidos). O teste deve observar ≥5 min e registrar a hora da desconexão.
4. **Como o app desconectou.** Desconexão pelo chip/serviço em primeiro plano (`disconnect` GATT limpo) pode diferir de "link perdido". O texto do original fala em BT desconectado pelo APP; sem evidência de diferença. `[HIPÓTESE]`
5. **Estado do switch/valor errado na hora.** Com o 10:14 não enviado, o estado interno do patinete (d=1 do 08:47?) é incerto; um teste a partir desse estado mistura variáveis. O teste real deve começar com d=0 confirmado por ACK.
6. **Patinete já desbloqueado só por instantes / sem PIN (`lock_has_pwd`).** Sem PIN o "bloquear" pode não ter efeito visível (S34 achado 13). `[DESCONHECIDO]`

## Conclusão provisória

O app novo não consegue, hoje, **provar** o efeito; nada indica ainda impossibilidade (por isso nenhum ADR de limitação). Próximo passo é o teste real S36 com "pode" no chat (roteiro em S34 §(c) + o item "laço de reenvio" da causa 1 como Experimento B: repetir 0x41 d=1 a cada 2 s por 14 vezes antes de desconectar, imitando o original). Não alterar CMD/bytes sem captura do original com d=1. Se o Experimento B também falhar, registrar limitação em `docs/adr/` e rebaixar/retirar a linha da UI.

Pendência de honestidade da UI (independe do teste): o texto da linha deve dizer que o efeito está "Em teste"; já há o selo. Considerar avisar quando o valor enviado por último foi d=1 e o d=0 não chegou (hoje só há o aviso genérico).
