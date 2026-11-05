
## Adendo (sessão 20) — risco residual irredutível do gate de escrita (Unlock/Lock)
Antes de escrever, `unlock()`/`lock()` recusam se houver recepção pendente (`boundary.sequence > lastProcessedSequence`, dentro do `writeLock`, sem suspensão), além das demais pré-condições (Meter1 fresco, roda parada, velocidade 0). Restam, e **não são eliminados por software**, dois intervalos: (1) entre `markBoundary()` e o envio de `gatt.write()` (µs), em que o callback do Android pode carimbar um Meter1 de movimento; (2) entre o envio e a chegada ao patinete (~70 ms medidos na S20) e, claro, qualquer movimento que ainda não tenha sido reportado. Refazer a checagem imediatamente antes do `write` só encurta (1) sem fechá-lo. **Mitigação operacional [CONFIRMADO no desenho]:** teste real só com o patinete parado no cavalete, proprietário presente, uma tentativa; Unlock/Lock nunca são enviados em sequência automática; confirmação só por Meter1 posterior. Em uso normal (no solo), o botão só habilita com Meter1 fresco e roda parada e o usuário está parado ao lado do patinete; o comando é LOCK (modo 2) ou Unlock com PIN, ambos sem efeito direto no movimento. Revisores Codex (r3) e agy (r3) apontaram esta janela como P1; Sonnet (r3) a classificou como TOCTOU inerente. Decisão: risco residual aceito e documentado; reabrir se houver evidência de movimento reportado durante a janela.

## Adendo S20 r4 — perda entre recepção e consumo

[ESTÁTICO] O carimbo continua sendo atribuído na chegada, antes do buffer. Como `tryEmit` pode recusar uma
notificação, o transporte compara cada sequência com a última consumida + 1 **antes de interpretar o quadro**.
Uma descontinuidade invalida `AgedTelemetry.meter1AtMs` (`null`), pois o quadro ausente pode ter indicado movimento.
ACK, Meter2 e quadro inválido não renovam Meter1; `unlock()`/`lock()` recusam `TelemetryStale` até outro Meter1 válido.
Se a própria recepção que revela a lacuna for Meter1 válido, ela já fornece essa nova leitura.

[ESTÁTICO] A primeira recepção de cada conexão estabelece a referência, pois o acumulador e os contadores são
novos; a sequência de `GattReception` permanece crescente ao reconectar. `RxStats.droppedFrames` soma os números
ausentes em lacunas crescentes dentro da conexão e participa da diferença retornada por `observe()`.
Referências: `BleScooterTransport.connect/onNotification`, `GattReception.notification`, `RxStats.minus`.
