# TASK s52-aviso-economia — aviso de Economia de energia na Telemetria (aprovado pelo proprietário, 2026-11-17)

**Fato [CONFIRMADO] (S51/S52):** com a Economia de energia do Android ligada (`location_mode=1`, GPS desligado com a tela apagada) o GPS não entrega fixes e a rota da viagem se perde. Com ela desligada: 153 fixes em 307 s com a tela apagada. Ver `docs/research/parts/S45-passeio-patinete.md` ("Passeio S51", "Teste de controle S52").

**Mockup aprovado:** `design/mockups/telemetria-aviso-economia-v1-escuro.png` e `-claro.png` (fonte: `design/telemetry.py`, parâmetro `aviso`). Cartão compacto 328×52 dp, borda e ícone `alert` na cor `pending`, 2 linhas: **"Economia de energia ligada"** (14 sp SemiBold) e **"O GPS não funciona com a tela apagada"** (12 sp, text2). Fica entre a pílula de Bateria e o cartão Duração, na rolagem normal (empurra os demais cartões 60 dp).

**Comportamento:**
- Visível só quando: viagem em gravação (Running, mesma condição do `RouteGate`: saveRoute + permissão FINE + Running) **e** Economia de energia que pare o GPS: `PowerManager.isPowerSaveMode` E, em API 28+, `locationPowerSaveMode` em {`LOCATION_MODE_GPS_DISABLED_WHEN_SCREEN_OFF`, `LOCATION_MODE_ALL_DISABLED_WHEN_SCREEN_OFF`, `LOCATION_MODE_THROTTLE_REQUESTS_WHEN_SCREEN_OFF`}; em API < 28 basta `isPowerSaveMode`. Some sozinho ao desligar a economia (receiver `ACTION_POWER_SAVE_MODE_CHANGED` + valor inicial).
- **Toque** ou **toque longo** no cartão abre os ajustes de bateria (`Settings.ACTION_BATTERY_SAVER_SETTINGS`; se não resolver, `Settings.ACTION_SETTINGS`). Nenhum botão extra, nenhum texto "Abrir ajustes".
- A11y: `Role.Button`, descrição "Economia de energia ligada. O GPS não funciona com a tela apagada. Toque para abrir os ajustes de bateria" e `onLongClickLabel` equivalente. Fonte 2,0 não pode cortar nem sobrepor (use o padrão `FitText`/`heightIn` dos outros cartões).
- Nenhuma permissão nova. Não registrar nada em log além do que já existe.

**Arquitetura (convenções do projeto):** interface `PowerSaveState` (StateFlow<Boolean> "GPS comprometido pela economia") em `core-domain` ou `ui`, com Fake em testes; adaptador Android (receiver + `PowerManager`) em `android-app` ligado na `MainActivity`; ViewModel expõe o booleano combinado com a condição de gravação; `TelemetryUiMapper`/`TelemetryScreen` só desenham. Abrir os ajustes = callback do `MainActivity` (a UI não conhece `Intent`).

**Arquivos permitidos:** `app/ui/src/main/.../telemetry/TelemetryScreen.kt`, `TelemetryUiMapper.kt`, `HomeViewModel.kt`, `app/android-app/src/main/...` (novo adaptador + `MainActivity`), testes correspondentes. Não tocar `design/`, protocolo nem `data/`.

**Aceite (TDD):** testes do ViewModel (visível só com gravação + economia que para o GPS; some ao desligar; API<28 usa só `isPowerSaveMode`), do mapper e de UI (texto, clique e clique longo disparam o callback, fonte 2,0 sem corte). `cd app && ./gradlew clean check` EXIT 0. Sem push. Revisor independente (Sol).
