# Log de depuração em arquivo (S48b)

Em build **depurável**, o app grava as linhas `IX7TX`, `IX7GPS` e `IX7APP` em `files/debuglog/ix7-0.log` (o mais novo; rotação até `ix7-3.log`, 8 MB cada). Útil quando o `adb logcat` sem fio cai no meio do passeio. Release: nenhum arquivo.

Puxar (adb com cabo ou sem fio):

    adb exec-out run-as br.dev.evaldo.meuscooter cat files/debuglog/ix7-0.log > research/captures/ix7-0.log

(repita para `ix7-1.log`..`ix7-3.log` se precisar de mais histórico). `research/captures/` é ignorada pelo git. Formato: `yyyy-MM-dd HH:mm:ss.SSS TAG texto`, hora local. Depois da análise, apague no aparelho: `adb shell run-as br.dev.evaldo.meuscooter rm -r files/debuglog`. Ver ADR 0012, adendo S48b.
