# ADR 0015 — Serviço em primeiro plano com tipo `location` durante a viagem
Status: aceito (autorizado pelo proprietário na sessão 42) · Tarefa S42 · Estende o ADR 0007

## Contexto e evidência
- Viagem real de ~20 min com a tela desligada: distância 0 e sem rota `[CONFIRMADO]` (relato do proprietário, sessão 42).
- O serviço `ScooterKeepAliveService` era só `connectedDevice` `[ESTÁTICO]` (manifest e `startForeground`). As fontes de GPS (`AndroidGpsFixSource`, `AndroidGpsSpeedSource`, `LocationManager`) são do app e, com o app em segundo plano, o Android 10+ só entrega localização a um app com serviço em primeiro plano do tipo `location` `[HIPÓTESE]` (documentação do Android; validar no aparelho).
- O trajeto continua sendo dado sensível (ADR 0012); este ADR só muda o TIPO do serviço, não o que é gravado.

## Decisão
1. Manifest: `foregroundServiceType="connectedDevice|location"` e `FOREGROUND_SERVICE_LOCATION` (API 34 exige a permissão do tipo). Sem `ACCESS_BACKGROUND_LOCATION`.
2. O tipo `location` entra no `startForeground` SOMENTE com viagem gravando (`TripRecState.Running`) E `ACCESS_FINE_LOCATION` concedida (`KeepAliveServiceLogic.foregroundType`). Senão, só `connectedDevice`. Antes da API 29 não há tipo.
3. `SecurityException` com `location` (permissão revogada entre a checagem e a chamada) é capturada e a promoção repete só com `connectedDevice`; o serviço não cai (`KeepAliveServiceLogic.promote`).
4. Ao iniciar/terminar a viagem com o serviço ativo, `HomeViewModel` chama `SessionKeepAlive.setTripRecording`; o Android reenvia o serviço com `ACTION_REFRESH` e ele chama `startForeground` de novo com o tipo certo. `start()` já leva o estado atual.
5. O serviço NUNCA pede localização: as fontes GPS atuais continuam as consumidoras e só assinam com `saveRoute` + permissão + Running (ADR 0012). Fora de viagem, nenhum GPS e o serviço volta a `connectedDevice`.
6. Notificação: "Conectado ao patinete · gravando trajeto" enquanto `location` estiver ativo.

## Por que sem `ACCESS_BACKGROUND_LOCATION`
O serviço é iniciado com o app em primeiro plano (o usuário inicia a viagem na tela) e o tipo `location` de um serviço assim mantém o acesso "enquanto em uso" ao passar para segundo plano. A permissão de segundo plano (prompt "Permitir o tempo todo") seria mais ampla do que o necessário e permitiria localização sem viagem em curso `[HIPÓTESE]` quanto ao comportamento exato por fabricante; validar no aparelho.

## Bateria e privacidade
- GPS só durante viagem Running (rota: também com "salvar trajeto"; a velocidade/distância por GPS segue o mesmo ciclo); Pausa/Parada desligam (ADR 0012) e o serviço sai do tipo `location`.
- O indicador de localização do sistema e a notificação deixam a gravação visível. Nada é enviado a rede.

## Consequências
- Android 14+: a permissão `FOREGROUND_SERVICE_LOCATION` é de instalação (normal); o tipo exige `ACCESS_FINE_LOCATION` concedida em tempo de execução, daí o fallback.
- Início do serviço em segundo plano na re-promoção pode ser recusado (API 31+); o erro é registrado e o serviço segue no tipo anterior.
- Testes JVM: decisão de tipo, fallback, re-promoção (efeito injetado) e integração com o `HomeViewModel`. Só no aparelho `[DESCONHECIDO]`: tela apagada por 20 min com rota gravada; Android 14 com e sem permissão; comportamento por OEM; indicador de localização. Só com o proprietário presente e o patinete parado, sem comando experimental.

## Alternativas
- `ACCESS_BACKGROUND_LOCATION`: rejeitada (acima).
- `location` sempre ativo com o serviço: rejeitada (localização sem viagem, bateria, privacidade).
- Serviço próprio de GPS: rejeitada (duplica fontes e estado).

## Como desfazer
Remover `|location` e `FOREGROUND_SERVICE_LOCATION` do manifest e a chamada `setTripRecording`; `foregroundType` passa a devolver só `connectedDevice`. Volta ao comportamento do ADR 0007 (sem rota com a tela apagada).
