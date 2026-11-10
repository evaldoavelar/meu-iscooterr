# ADR 0007 — Serviço em primeiro plano para manter a sessão BLE e o heartbeat
Status: aceito (decisão do proprietário, sessão 30, 2026-11-06) · Implementação `[ESTÁTICO]` até o teste no aparelho

## Contexto
- Com o app em segundo plano, o Android congela o processo em cache (`am_freeze`, freezer de apps em cache). Os coroutines do `viewModelScope` param, e com eles o heartbeat 0x10 de 1 s do `BleScooterTransport`.
- S29 `[HIPÓTESE]`: com o app em segundo plano, o patinete desbloqueou/apitou sozinho, compatível com o heartbeat parado. Não confirmado como causa.
- O app original não é referência aqui: a decisão é de plataforma Android, não de protocolo. Nenhum comando novo é introduzido.

## Decisão
1. **Serviço em primeiro plano (`ScooterKeepAliveService`, android-app), tipo `connectedDevice`.** Ele só mantém o processo fora do estado "em cache" (não congelado). Não possui o transporte, não fala com o GATT e **não envia nada**: o transporte, o CONNECT 0x30 e o heartbeat 0x10 continuam no `HomeViewModel`/`BleScooterTransport`, exatamente como hoje.
2. **Quando fica ativo — política pura `KeepAlivePolicy.next` (core-domain, testada na JVM):**
   - `Connected` → ativo (começa só com a conexão real estabelecida);
   - `Connecting` ou reconexão automática pendente → mantém o estado anterior (não começa por isso, mas não para no meio de uma reconexão automática após queda);
   - `Disconnected` sem reconexão pendente → inativo.
   Consequências: desconectar pelo menu ou pela notificação, cancelar, esquecer o patinete e esgotar as tentativas de reconexão param o serviço; reconexão automática (ADR 0006 §6) continua valendo e o serviço fica vivo durante ela.
3. **Só com BLE real e TX ligado.** O `HomeViewModel` recebe um `SessionKeepAlive` (interface em core-domain; padrão `SessionKeepAlive.None`). A `MainActivity` só passa a implementação Android (`AndroidSessionKeepAlive`) quando `realBle && bleTx`; Fake, primeiro uso e modo só escuta não iniciam serviço.
4. **Sair do app** (`ViewModel.onCleared`, Activity finalizada) para o serviço. **Remover a tarefa dos recentes** (`onTaskRemoved`) é tratado como "Desconectar": pede a desconexão ao ViewModel e encerra o serviço.
5. **Notificação fixa** (canal "Conexão com o patinete", importância baixa, sem som) com o texto "Conectado ao patinete" e a ação **"Desconectar"**, que chama `HomeViewModel.onNotificationDisconnect()` = mesmo caminho do "Desconectar" do menu (pedido do usuário: a vigia de queda não reconecta; também cancela reconexão pendente). Tocar na notificação abre a `MainActivity`.
6. **Permissões (manifesto):** `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE` (API 34+) e `POST_NOTIFICATIONS` (API 33+, runtime). O pré-requisito do tipo `connectedDevice` na API 34+ é `BLUETOOTH_CONNECT` concedida, que o BLE real já exige. `POST_NOTIFICATIONS` entra no pedido único de permissões da `MainActivity` (só com BLE real + TX).
7. **Nenhuma lógica de segurança muda:** Unlock/Lock, `wheelMoving`, cooldown do PIN, escopo do TX (CONNECT/heartbeat, Unlock, Lock modo 2) e o reducer ficam intactos.

## Ciclo de vida
`Connected` (app em primeiro plano, em geral) → `startForegroundService` → `onStartCommand` chama `startForeground(tipo connectedDevice)` imediatamente → app vai ao segundo plano sem congelar → queda: reconexão automática com o serviço vivo → sucesso (continua) ou esgotou (para) · "Desconectar" (menu/notificação), esquecer, sair, remover dos recentes → `stopService`.

## Riscos e limites `[ESTÁTICO]`
- **POST_NOTIFICATIONS negada:** o serviço ainda inicia e mantém o processo; a notificação pode não aparecer (na API 33+ ela fica visível só no gerenciador de tarefas do sistema). O app segue funcionando; a ação "Desconectar" da notificação fica indisponível (resta o menu do chip).
- **Início em segundo plano (API 31+):** `startForegroundService` com o app em segundo plano pode lançar `ForegroundServiceStartNotAllowedException` (ex.: a conexão completa segundos depois de o usuário sair do app). A exceção é capturada e registrada; o comportamento volta ao anterior (processo pode congelar). O serviço não é reiniciado sozinho (`START_NOT_STICKY`).
- **Parar antes de `startForeground`:** conectar e desconectar em milissegundos pode chamar `stopService` antes de o serviço se promover; algumas versões do Android acusam "did not then call startForeground" `[HIPÓTESE]`. Improvável (a conexão leva segundos), não tratado.
- **Avaliação síncrona:** a política é avaliada pela vigia de enlace (depois de decidir a reconexão) e no fim do job de reconexão, não por um coletor à parte (que poderia ver "Desconectado" antes de a reconexão ser marcada e parar o serviço de vez).
- **Limite de tempo do tipo:** `connectedDevice` não tem o limite de 6 h do `dataSync` (API 35); fabricantes com economia agressiva de bateria ainda podem matar o processo `[DESCONHECIDO]`.
- **Remover dos recentes desconecta:** sem heartbeat o patinete pode bloquear/apitar sozinho `[HIPÓTESE]` (igual a fechar o app hoje).
- **Bateria:** processo e heartbeat de 1 s vivos enquanto conectado; aceitável pela decisão do proprietário.
- **Sem teste JVM do serviço** (Context/NotificationManager; sem Robolectric no cache offline). Testados na JVM: a política e a integração com o `HomeViewModel` (início/parada, reconexão, desconexão pela notificação, `onCleared`). Validação no aparelho: conectar, ir ao segundo plano por > 1 min, conferir no logcat que o heartbeat continua e que não há `am_freeze` do app; tocar "Desconectar" na notificação. Teste no aparelho só com o proprietário presente e o patinete parado.

## Adendo — Endurecimento e tratamento dos 5 BAIXOs da S30 (sessão 41, 2026-11-10)
1. **Flag `cleared` no `HomeViewModel` (BAIXO 1):** `onCleared()` marca `cleared = true` antes de parar o serviço; `updateKeepAlive()` confere a flag e aborta imediatamente se já estiver finalizado, impedindo que chamadas tardias (vigia de enlace ou encerramento assíncrono de reconexão) religuem o `SessionKeepAlive`. Testado na JVM em `HomeViewModelKeepAliveTest`.
2. **`startForeground` falho e ação "Desconectar" (BAIXO 2):** caso a chamada a `startForeground` lance exceção (permissão ausente, início em segundo plano não permitido, etc.), o erro é registrado no Logcat e o serviço executa `stopSelf()` imediatamente para não permanecer em execução sem notificação nem sofrer ANR do sistema. A ação "Desconectar" continua presente na notificação sempre que construída. A lógica de decisão foi extraída para `KeepAliveServiceLogic.evaluateStart` e testada na JVM sem Context (`KeepAliveServiceLogicTest`).
3. **Remoção da tarefa dos recentes (BAIXO 3):** `onTaskRemoved` formaliza o ADR 0007 §4: aciona o `disconnectHandler` (solicitando desconexão ao `HomeViewModel` sem reconexão automática) e para o serviço. Lógica pura validada na JVM em `KeepAliveServiceLogicTest`.
4. **Delimitação de testes JVM vs Aparelho (BAIXO 4):**
   - `[ESTÁTICO]`: O que fica sem teste unitário JVM por depender do framework Android (`android.app.Service`, `NotificationManager`, `Notification.Builder`, `PendingIntent`, permissões em runtime) devido à ausência de Robolectric no cache offline.
   - `[HIPÓTESE]`: O que depende de validação no aparelho real: renderização visual da notificação persistente no painel do sistema, comportamento do botão "Desconectar" na gaveta de notificações em diferentes OEMs, remoção da notificação ao "limpar tudo" dos recentes e estabilidade do processo contra congelamento (`am_freeze`) sob longos períodos em segundo plano.
5. **Ícone da ação "Desconectar" (BAIXO 5):** a ação utiliza ícone nulo (`null`), exibindo apenas o texto padrão "Desconectar", enquanto o ícone de status do serviço utiliza `android.R.drawable.stat_sys_data_bluetooth`. Nenhuma arte final customizada foi criada e o ícone do aplicativo permaneceu inalterado. Fica registrado como pendência de decisão do proprietário caso deseje definir um ícone específico do sistema (`android.R.drawable.*`) ou vetor monocromático dedicado.

## Adendo S42 — Tipo `location` durante a viagem
O serviço passa a ser `connectedDevice|location`, com `location` só durante viagem gravando e permissão concedida. Ver ADR 0015.
