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
| `ui` (lógica: mapper, estado, ViewModel) | 63 (27 mapper · 9 ViewModel · 21 HomeScreen · 2 AppRoot · 2 tema · 2 screenshots) | 97,7 % (gate) |
| `ui` Composables (reportado, sem gate) | — | 98,1 % |
APK debug (`android-app`, `applicationId br.dev.evaldo.meuscooter`) compila. Nome de trabalho: recurso único `app_name` = "Meu Patinete" (`app/ui/src/main/res/values/strings.xml`). Evidência vermelho→verde: `docs/tdd/sprint4-ui-vermelho-verde.md` (incl. ressalvas honestas: ciclo 5 sem vermelho por asserção; ciclo do layout 360×780 teve 2 vermelhos reais).

## Diferenças em relação ao FigCraft aprovado (Home, 360×780 dp @ xhdpi = 720×1560 px)
Cor de fundo e cor do botão principal coincidem com o FigCraft em 8 estados × 2 temas (teste automático). Diferença média de luma 6–9 % (fontes/antialias). Diferenças conhecidas:
1. **Título:** "Meu Patinete" (nome de trabalho neutro) em vez de "Meu iScooter"; tipografia Roboto/M3 no lugar da fonte do FigCraft (um pouco mais larga; tamanhos de título menores para caber).
2. **Estado desconectado:** FigCraft mantém últimos valores (farol ligado, 1 motor, Eco, limite 25) e botão "Procurar patinete" **cinza**; o app mostra `--`, controles sem seleção (limite no mínimo 20) e o botão **habilitado na cor primária** (precisa ser tocável). Ícone Bluetooth fica na cor primária (FigCraft: cinza quando offline). Reter "último estado conhecido" exige decisão do proprietário. `[HIPÓTESE]`
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

## Pendências desta sprint
Identidade (propor 2–3 nomes + verificar disponibilidade), revisão cruzada (Codex a partir de 2026-11-04 09:27; ficha em `docs/tasks/`), merge na `main` (só com autorização).
