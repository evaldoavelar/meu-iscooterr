# Log de depuração em arquivo (S48b)

Em build **depurável**, o app grava as linhas `IX7TX`, `IX7GPS` e `IX7APP` em `files/debuglog/ix7-0.log` (o mais novo; rotação até `ix7-3.log`, 8 MB cada). Útil quando o `adb logcat` sem fio cai no meio do passeio. Release: nenhum arquivo.

`[ESTÁTICO]` O logger é único por processo (`FileDebugLog.shared`), inclusive após recriar a Activity. A E/S ocorre em uma thread, com flush a cada 1 s ou 50 linhas. A fila tem capacidade para **5.000 linhas aguardando E/S**, além de uma em escrita. Quando enche, **descarta as linhas novas** e conta os descartes; ao voltar a escoar, grava `yyyy-MM-dd HH:mm:ss.SSS IX7APP dropped=N`. Esse aviso indica uma lacuna na captura. E/S bloqueada também atrasa o flush; `append` não espera o disco. Referência: `FileDebugLog.kt` (`append`, `drain`, `writeLine`) e `FileDebugLogTest`.

`[ESTÁTICO]` A rotação considera os bytes UTF-8 da próxima linha mais LF, inclusive na primeira escrita após reabrir. Se uma exclusão ou renomeação falhar, aborta a rotação e continua em **append** no arquivo atual, que pode ultrapassar 8 MB enquanto a falha persistir. Movimentações anteriores de arquivos antigos não são desfeitas. Uma linha maior que 8 MB é mantida inteira. Referência: `RotatingLogFiles.write/rotate` e testes de falha de rotação em `FileDebugLogTest`.

Puxar (adb com cabo ou sem fio):

    adb exec-out run-as br.dev.evaldo.meuscooter cat files/debuglog/ix7-0.log > research/captures/ix7-0.log

(repita para `ix7-1.log`..`ix7-3.log` se precisar de mais histórico). `research/captures/` é ignorada pelo git. Formato: `yyyy-MM-dd HH:mm:ss.SSS TAG texto`, hora local. Depois da análise, apague no aparelho: `adb shell run-as br.dev.evaldo.meuscooter rm -r files/debuglog`. Ver ADR 0012, adendo S48b.
