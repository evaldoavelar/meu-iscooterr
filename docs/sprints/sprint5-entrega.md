# Entrega — Sprint 5 (demais telas), fatia 5.6 Fechamento, sessão 09
Ramo `sprint-5/fechamento` (a partir da `main` @ `7f5dc3e`). **Nenhum BLE real; nenhum comando ao patinete** (só `FakeScooterTransport`). Revisão cruzada (Codex ≥ 2026-11-04 09:27 e Sonnet) e aprovação do proprietário pendentes para esta fatia.

## Como reproduzir
```
source scripts/android-env.sh && cd app
./gradlew --no-daemon clean check :android-app:assembleDebug     # ~3,5 min, BUILD SUCCESSFUL
```
Screenshots: `app/ui/build/screenshots/{claro,escuro}/*.png`; relatórios `report.csv` (Home, `HomeScreenshotTest`) e `report-telas.csv` (telas novas, `ScreensScreenshotTest`; arquivo separado para não sobrescrever o da Home). Referência: `design/out/{claro,escuro}`.

## Fatias 5.1–5.5 (merge local na `main`)
| Fatia | Merge | Commits principais |
|---|---|---|
| 5.1 Navegação | `dc9e750` | `1d61f81` Navigator/AppDestination · `8f456e9` ressalvas (Navigator imutável/salvável) |
| 5.2 Telemetria | `051a89d` | `5ff7507` métricas/TripTracker · `fb50ab4` TelemetryUiMapper · `e3f1fbe` TelemetryScreen/SpeedGauge · `133d06c` revisão Sonnet |
| 5.3 Histórico + detalhe + mapa | `36a8efe` | `a6d7333` Trip/TripRepository · `0e5ccfb` MapTileSource/OSM/TileCachePolicy · `00990a6` telas · `7ed7b9d` revisão |
| 5.4 Preferências | `c3de996` | `03388cd` Units/Preferences · `c56f8ac` ViewModel · `750e3b4` SettingsScreen · `901b3b2` revisão |
| 5.5 Primeiro uso + Recuperação | `7f5dc3e` | `0998fc6` domínio · `b3c1756` ambiente simulado · `6a342c4` ViewModel · `46f3703` telas · `ce5d00b` AppRoot · `9896e4f`/`4fb16d8`/`4c6f314` correções |
| 5.6 Fechamento | (este ramo) | `455d894` pendências baixas + testes 360×780 · `7e9d88f` S5 das telas novas · docs e gate (ver `git log`) |

## Fechamento 5.6 (esta fatia)
Com TDD (`docs/tdd/sprint5-vermelho-verde.md`, seção 5.6):
- **Telemetria velha:** a tela de Telemetria mostra "--" e "Dados desatualizados" após `STALE_AFTER_MS` (2 s) ou quando a conexão cai, como a Home; a viagem atual é mantida.
- **Duração ≥ 60 min** vira `h:mm:ss` (ex.: `1:05:30`); decisão: `h:mm:ss` em vez de `h:mm` para não confundir com `mm:ss`.
- **Viagem < 30 s:** "< 1 min" em vez de "0 min" (lista e detalhe).
- **Limite de velocidade em mi/mph:** só o rótulo converte ("16 mph (25 km/h)", faixa "12 mph … 31 mph"); slider e comando continuam em km/h (teste: arrastar a 40 envia 40). Não houve mudança no comando.
- **Cobertura de Composables** passou a incluir `telemetry`, `history` e `settings` (antes só home/onboarding/recovery).
- **Testes 360×780 dp com fonte 1,5/2,0:** já existiam para Home, Primeiro uso, Recuperação e Preferências; **adicionados** para Telemetria, Histórico e Detalhe (D). Achado: na Telemetria o cabeçalho (voltar e menu ⋮) rola com o conteúdo; em fonte grande o menu só é alcançado com a tela no topo `[ESTÁTICO]`.

## Resultado do gate (2026-11-03, `clean check :android-app:assembleDebug`: BUILD SUCCESSFUL)
| Módulo | Testes | Cobertura de linhas (JaCoCo; gate ≥ 80 %) |
|---|---|---|
| `core-protocol` | 0 próprios (modelos/interfaces) | n/a |
| `core-domain` | 98 | 99,0 % (285/288) |
| `data` | 17 | 100 % (92/92) |
| `ui` lógica (mapeadores, estado, ViewModel; sem `AppRoot`) | 187 (módulo inteiro) | 96,8 % linhas (395/408), 91,3 % ramos (informativo) |
| `ui` Composables (home, onboarding, recovery, telemetry, history, settings; reportado, sem gate) | — | 99,1 % linhas (682/688), 85,0 % ramos |
Números: `core-domain/build/reports/jacoco`, `ui/build/reports/jacoco/{uiCoverageReport,composableCoverageReport}`. `AppRoot` fica fora do gate por desenho (testado em `AppRootTest`). APK debug compila.

## S5: diferenças medidas vs FigCraft (360×780 dp @ xhdpi = 720×1560 px)
Método: igual ao `HomeScreenshotTest` (cor de fundo + diferença média de luma em blocos 8×8, teto de regressão 0,12; Home medida na sprint 4: 6–9 %). Telemetria no FigCraft tem 720×1800: compara-se o trecho visível (1560). Todos os 18 frames × 2 temas passam com fundo idêntico; a métrica **não** prova fidelidade visual, as diferenças reais estão na lista abaixo (vistas lado a lado nos PNGs).

| Frame(s) | MAD claro / escuro | Diferenças reais |
|---|---|---|
| Telemetria-Parado / -Andando / -1-Motor | 0,047–0,061 / 0,048–0,063 | (a) **sem chip de conexão** "Conectado · iX7 Pro" no topo (FigCraft tem); (b) velocímetro sem o ícone de patinete, números 0/60 mais baixos e arco sem o brilho em gradiente; (c) bateria é um cartão "Bateria 82%" em vez da pílula com ícone; (d) **barras "últimos 30 s" não implementadas**; (e) cartões de Corrente/Tensão/Temperaturas/Odômetros sem ícones; odômetros em 2 cartões em vez de um cartão com divisor; (f) "Viagem atual" fica abaixo do trecho visível (layout mais alto: ~1,25 tela a mais); (g) altimetria "Sem leitura" em vez de "+18 m" (**altimetria fora**, `[HIPÓTESE]` GPS do celular) |
| Historico | 0,037 / 0,038 | resumo é dos 5 viagens simuladas (5 · 33,6 km · 2 h 19) e não de "12 · 86,4 km · 5 h 12" do desenho; "Viagens · 30 dias" quebra em 2 linhas em 360 dp; miniatura do trajeto sem as ruas/áreas do mapa de exemplo (o app não mostra mapa falso) e com trajetos de `SampleTrips` mais achatados |
| Historico-Vazio | 0,016 / 0,017 | sem diferença visível além da tipografia |
| Viagem-1..5 | 0,043–0,044 / 0,049–0,050 | sem menu ⋮ no topo; mapa sem ruas/rótulo "Mapa de exemplo" (decisão 4: sem selo de exemplo; mapa real só na Fase 5); sem ícones nos cartões de estatística; altimetria "Sem leitura" |
| Configuracoes | 0,046 / 0,043 | seletores segmentados são o `SingleChoiceSegmentedButtonRow` M3 (lavanda + check) e não o pill ciano do FigCraft; linha do patinete sem o subtítulo "Conectado · toque para detalhes" nem chevron (a linha não é navegável); "Código aberto" sem chevron |
| PrimeiroUso-1..3 | 0,046–0,049 / 0,049–0,053 | título e anéis em posição/tamanho diferentes (anéis estáticos, **sem animação de ondas**); passo 2 mostra **"Encontrado agora"** no lugar de "Sinal forte · bem perto" (o app não mede sinal); botão "Cancelar" ~16 px mais baixo/menos alto |
| Recuperacao-Desligado / -Bluetooth / -Reconectando | 0,039 / 0,037 / 0,023 (claro); 0,045 / 0,042 / 0,025 (escuro) | ícone e texto ~30 px mais altos; tipografia; fora isso iguais |
| Recuperacao-Permissao | 0,075 / 0,078 (maior da sprint) | **ordem dos botões invertida**: FigCraft tem "Abrir configurações do Android" (tonal) em cima e "Permitir" (principal) embaixo; o app tem "Permitir" em cima. Diferença real de layout, não corrigida nesta fatia |
Diferenças gerais em todas as telas: tipografia Roboto/M3 do sistema em lugar da fonte do FigCraft `[HIPÓTESE: fonte Inter não empacotada]`, cantos e espaçamentos 2–6 dp diferentes, switch M3. Não capturados no S5 (janela separada, fora do `onRoot`): menu ⋮ e diálogo "Zerar" da Telemetria (cobertos em S4).

## Diferenças funcionais conhecidas vs FigCraft/plano `[ESTÁTICO]`
1. **"Encontrado agora"** no lugar de "Sinal forte · bem perto" (sem medida de sinal).
2. **Anéis estáticos** no primeiro uso (animação infinita travaria testes Compose).
3. **Altimetria fora** (campo "Sem leitura").
4. **Barras "últimos 30 s"** do cartão de consumo não implementadas.
5. **`PreferencesStore` em memória** (DataStore na Fase 4): primeiro uso reaparece a cada processo novo; `deviceName` fixo "Meu Patinete" em `AppRoot`.
6. **Permissões BLE só simuladas** (`SimulatedBluetoothEnvironment`; `BLUETOOTH_SCAN/CONNECT`, intent de ativar Bluetooth e "Abrir configurações" reais são Fase 4).
7. "Reconectar automaticamente" e "Guardar trajeto" só gravam a preferência (efeitos nas fases 4–5); "Esquecer patinete" só desconecta.
8. Mapa: `OsmTileSource` e cache de tiles existem e são testados, mas nenhuma tela desenha tiles (só traçado simulado).

## Decisões `[HIPÓTESE]` a apresentar ao proprietário
- Campos extras de telemetria (V, I, temperaturas, odômetros) existem **só no Fake**; leitura real de cada um é `[DESCONHECIDO]` até a Fase 4/5.
- Cache de tiles OSM: validade 30 dias; tamanho 50 MB `[HIPÓTESE]`.
- Cada tentativa de reconexão espera até 5 s, sem backoff, 5 tentativas; busca do primeiro uso = 2 s (de `design/build.py`).
- Duração `h:mm:ss` a partir de 60 min (em vez de `h:mm`).
- Limite de velocidade exibido como "16 mph (25 km/h)" com mph selecionado (passo do slider continua 1 km/h ≈ 0,6 mph).
- Fonte Inter não empacotada (diferença tipográfica sistemática).

## Pendências restantes
- **Tick diário do histórico:** "Hoje/Ontem" e a janela de 30 dias só recalculam quando a lista muda (baixo; histórico em memória).
- **Atribuição OSM:** garantir por teste, na Fase 5, a exibição de "© colaboradores do OpenStreetMap" sempre que tiles forem desenhados.
- **Rotação com diálogo aberto:** sem teste de recriação com `AlertDialog` aberto (Zerar/Esquecer/Apagar).
- **BAIXO da re-revisão 5.5:** `cancelRecovery` com o transporte em `Connecting` ainda não publicado; piscada de Home ao "Esquecer"; retorno de `withTimeoutOrNull` ignorado em `HomeViewModel`.
- Do 5.3: `InMemoryTripRepository.save` com `update{}`/id atômico quando houver Room; `Navigator.openTrip` dentro de `TripDetail` empilha duplicado; data futura cai em "Seg 3".
- Do 5.4: `preferences` com `stateIn(Eagerly, padrão)` causará flash de tema com DataStore.
- Do 5.2: risco aceito (Desbloquear habilitado com telemetria velha mostrando 0 km/h); `SpeedGauge` com paddings fixos (conferido em 360×780/fonte 2,0 por teste; sem revisão visual em aparelho `[HIPÓTESE]`).
- Corrigir ordem dos botões da tela de Permissão e demais diferenças de layout acima, se o proprietário quiser aproximar mais do FigCraft.
- Revisão Codex (≥ 2026-11-04 09:27) e Sonnet desta fatia; nada foi publicado (sem push).
