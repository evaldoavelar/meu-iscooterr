# S30 — teste real: serviço em primeiro plano e Unlock ao 4º dígito
Data: 2026-11-09, `main` @ 0b02328 (APK debug). Patinete ligado, parado, bloqueado; proprietário presente e com "pode" no chat. Log `IX7TX` (privado): `research/captures/s30-fgs/logcat.txt`.

| Verificação | Resultado | Classe |
|---|---|---|
| Serviço sobe ao conectar | `dumpsys`: `isForeground=true`, `types=0x10` (connectedDevice), notificação ONGOING, 1 ação | `[CONFIRMADO]` |
| Heartbeat em segundo plano | 1 TX/s sem lacuna de 10:16:44 a 10:18:05 (>80 s) e até o fim; nenhum `am_freeze` do app | `[CONFIRMADO]` (o congelamento da S29 não ocorreu) |
| Unlock ao 4º dígito, sem botão | 10:20:03.753 `UNLOCK requested`, 1 único `TX #287 UNLOCK` (PIN mascarado), `UNLOCK result=Unlocked` em ~0,7 s | `[CONFIRMADO]` |
| Desconectar pelo chip | `SESSION end reason=UserDisconnect` 10:20:16; serviço não aparece mais no `dumpsys` | `[CONFIRMADO]` |
| Ação "Desconectar" da notificação | **não testada**: o proprietário limpou todas as notificações e a do serviço sumiu, com o serviço ainda em primeiro plano | lacuna |

## Achado
No Android atual (API 37 no aparelho) "limpar tudo" remove também a notificação do serviço em primeiro plano; o serviço continua. A ação "Desconectar" da notificação some até nova conexão. Correção candidata: reeditar/re-postar a notificação a cada mudança de estado, ou aceitar que o chip é o caminho.
