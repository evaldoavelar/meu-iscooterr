# TASK — Gravar o trajeto da viagem pelo GPS (pedido do proprietário, S34, 2026-11-09)

**Problema:** a preferência "Guardar trajeto das viagens" (`Preferences.saveRoute`) não tem efeito: o app só usa a velocidade do GPS (`AndroidGpsSpeedSource`), nunca grava a posição, e nada lê `saveRoute`.

**Escopo (próximas sessões, ordem sugerida):**
1. Contrato: `GpsSpeedSample` (ou fonte nova) passa a trazer latitude/longitude/precisão/tempo; só grava com `saveRoute` ligado, permissão concedida e viagem em curso.
2. Armazenamento local (só neste celular): pontos da viagem junto do histórico (`TripHistory`); amostragem com filtro por distância/precisão para limitar o tamanho; "Apagar histórico" apaga o trajeto.
3. UI: o detalhe da viagem já tem `RouteMap` (tiles OSM com cache e atribuição): desenhar a polilinha; sem trajeto → mensagem atual.
4. Privacidade: nunca em logs/`IX7TX`/exportação; ADR curto (dado de localização sensível, uso local).
5. Testes JVM (filtro, persistência, `saveRoute` desligado não grava); GPS em API < 31 continua pendência separada.

**Aceite:** com `saveRoute` ligado, uma viagem real gera trajeto visível no detalhe; desligado, nada é gravado. Revisor independente; `clean check` EXIT 0. Sem tocar o patinete.
