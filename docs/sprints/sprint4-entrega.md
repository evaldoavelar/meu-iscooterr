# Entrega — Sprint 4 (Esqueleto + Home), sessão 07
Branch `sprint-4/esqueleto-home` (não mesclada). **Nenhum BLE real; nenhum comando ao patinete** (só `FakeScooterTransport`).

## Como reproduzir
```
source scripts/android-env.sh && cd app
./gradlew clean check :android-app:assembleDebug     # ~2 min, BUILD SUCCESSFUL
```
Screenshots: `app/ui/build/screenshots/{claro,escuro}/*.png` + `report.csv` (gerados por `HomeScreenshotTest`); referência `design/out/{claro,escuro}` (regenerar: `cd design && ./run.sh`).

## Resultado (2026-11-03)
| Módulo | Testes | Cobertura de linhas (JaCoCo; gate ≥ 80 %, build falha abaixo) |
|---|---|---|
| `core-domain` | 28 | 98,3 % |
| `data` | 9 | 100 % |
| `ui` (lógica: mapper, estado, ViewModel; sem `AppRoot`) | 65 (29 mapper · 9 ViewModel · 21 HomeScreen · 2 AppRoot · 2 tema · 2 screenshots) | 97,3 % linhas (gate ≥ 80 %), 90,9 % ramos (informativo) |
| `ui` Composables (reportado, sem gate) | — | 98,1 % |
APK debug (`android-app`, `applicationId br.dev.evaldo.meuscooter`) compila. Nome de trabalho: recurso único `app_name` = "Meu Patinete" (`app/ui/src/main/res/values/strings.xml`). Evidência vermelho→verde: `docs/tdd/sprint4-ui-vermelho-verde.md` (incl. ressalvas honestas: ciclo 5 sem vermelho por asserção; ciclo do layout 360×780 teve 2 vermelhos reais).

## Diferenças em relação ao FigCraft aprovado (Home, 360×780 dp @ xhdpi = 720×1560 px)
Cor de fundo e cor do botão principal coincidem com o FigCraft em 8 estados × 2 temas (teste automático, inclusive Desconectado). Diferença média de luma 6–9 % (fontes/antialias). Diferenças conhecidas:
1. **Título:** "Meu Patinete" (nome de trabalho neutro) em vez de "Meu iScooter"; tipografia Roboto/M3 no lugar da fonte do FigCraft (um pouco mais larga; tamanhos de título menores para caber).
2. **Estado desconectado (decisão do proprietário aplicada):** mantém o último estado dos controles, leituras viram `--`, botão "Procurar patinete" cinza (clicável), ícone Bluetooth cinza — como no FigCraft.
3. **Aviso de restrição:** faixa laranja com o motivo ("Pare o patinete para alterar", "Dados desatualizados — aguardando o patinete"…) aparece abaixo do chip; não existe no FigCraft (decisão 3/5 do plano exige motivo sem jargão).
4. **Layout:** espaçamentos/raios/alturas ligeiramente diferentes (cartões ~2–6 dp mais baixos ou altos); tudo cabe sem rolar em 360×780 (teste). Slider usa thumb circular M3 sem o anel do FigCraft; chave (Switch) M3 (polegar claro em vez de escuro no estado ligado, no tema claro).
5. **Botão enviando:** spinner do Material (no screenshot estático aparece parcial). Ícone de cadeado/check/alerta idênticos aos de `lib.py`.
6. **Fonte grande (1.5–2.0) e tela pequena:** não existe no FigCraft. App: cartões de informação empilham, opções de modo/tração em 2 colunas; botão continua fixo e visível; conteúdo rola (testes). Em 2.0, "Conectado · iX7 Pro" quebra em 2 linhas (aceito).
7. **Menu ⋮:** `DropdownMenu` M3 (Telemetria · Preferências · Histórico de viagens → telas stub "Esta tela chega na Sprint 5.").
8. **Não implementado nesta sprint:** estados "Enviando por controle" visuais além do alvo laranja/mensagem de erro por controle não têm screenshot dedicado (testados por semântica); telas da Sprint 5.

## Decisões `[HIPÓTESE]` para apresentar ao proprietário
- Regra própria do **desbloqueio**: exige último estado "bloqueado" conhecido; tolera telemetria velha/velocidade desconhecida; nunca em movimento.
- Timeouts: pendência 3 s, "confirmado" visível 2 s, telemetria velha > 2 s.
- Rótulos de modo (valor 0–3 ↔ Pedestre/Eco/Race/Sport) só no `FakeScooterTransport`.
- Botão de busca habilitado quando desconectado (diferença 2).
- `compileSdk = 37` (exigido pelo Compose 1.12.1; SDK baixado pelo AGP em `tools/`; `targetSdk` 35, `minSdk` 26).

## Revisão cruzada (Sonnet, só leitura, `git diff 115e029..c931f55`) — `.omc/prompts/review-sprint4-sonnet-r1.md`
Sem bloqueantes. Corrigidos com TDD: (1) leituras ao vivo só quando conectado; (2) "Tentar de novo" só habilitado se o reducer aceitaria; (3) botão principal desabilitado fica cinza; (9/10) gate sem `AppRoot` e com falha explícita se vazio; (14) testes de ViewModel checam QUAL comando; docs (11, 22). **Não tratados (registrados para a Sprint 5):** 4 banner usa o motivo do Modo como proxy; 5 slider não reverte se o comando falhar/limite desconhecido mostra 20; 6–7 guarda contra `connect()` repetido e leitura de `uiState` defasado no ViewModel; 8 outcomes de Lock não expiram (chip "Sem resposta" persiste) e recusa rotulada como "sem resposta"; 13 regra de desbloqueio `[HIPÓTESE]`; 21 Fake no APK sem flavor/ícone. Revisão Codex continua pendente (≥ 2026-11-04 09:27).

## Pendências desta sprint
Identidade: proprietário decidiu **manter "Meu Patinete"** (nome de trabalho); verificar disponibilidade antes de qualquer distribuição pública. Revisão Codex pendente. Merge na `main` autorizado pelo proprietário.
