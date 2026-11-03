# Fase 3 — Frontend com transporte simulado (Sprints 4–5)

Entrada: protótipo validado pelo proprietário (sessão 5, mesclado em `main`; `design/`). Esta fase **não fala com o patinete**: tudo roda sobre `ScooterTransport` simulado (regra: UI nunca bloqueia em operação BT; "concluído" só após confirmação simulada equivalente à telemetria).

## Escopo
Compose + Material 3, Coroutines/StateFlow, seguindo `design/ANDROID-MAPA.md` (home com menu ⋮ → Telemetria · Preferências · Histórico de viagens; sem sheets de tração/limite).

## Sprint 4 — Esqueleto e Home
1. Projeto Gradle (`app/core-domain`, `app/core-protocol` só interfaces, `app/data`, `app/ui`); `ScooterTransport` + `FakeScooterTransport` (latência ACK ~50 ms, estado confirmado ~420 ms, falhas injetáveis `[CONFIRMADO]` em S2).
2. Tokens: `ColorScheme` + `ExtendedColors` a partir de `design/lib.py` `COLORS` (claro/escuro).
3. `HomeScreen` com todos os estados (desconectado, conectando, bloqueado/desbloqueado, enviando por controle, confirmado, falha, timeout, telemetria desatualizada, em movimento) e menu ⋮; botão fixo no rodapé.
Aceitação: testes de unidade do reducer de estado (sem "confirmado" antes da telemetria); screenshots Compose comparados aos PNGs de `design/out/`.

## Sprint 5 — Demais telas
Telemetria (`SpeedGauge` Canvas com neon externo e ícone do patinete), Histórico + detalhe (mapa OSM com atribuição; **decidir** online × cache), Preferências (DataStore), Primeiro uso, Recuperação. Dados de exemplo só no fake.

## Decisões do proprietário (sessão 5, 2026-11-03) — obrigatórias
1. **Identidade:** nova identidade em app, docs e recursos visuais; **nenhuma marca de terceiros no identificador Android** (`applicationId`/pacote); verificar disponibilidade do nome antes de qualquer distribuição pública.
2. **OSM:** tiles online com cache local limitado, atribuição obrigatória, respeitar a política do provedor; arquitetura desacoplada do provedor (interface `MapTileSource`); histórico textual das viagens funciona sem internet; só dados simulados.
3. **Controles em movimento:** bloqueio, motores, modo, limite, cruise e partida do zero ficam **desabilitados** com patinete em movimento, com velocidade desconhecida ou telemetria desatualizada. Desbloqueio tem tratamento próprio na máquina de estados (não reutilizar a regra do bloqueio). Farol pode ficar acessível em movimento (sujeito ao protocolo real).
4. **Modos:** suportar os 4 valores conhecidos do CMD 0x35; **não** assumir correspondência definitiva valor↔nome; rótulos provisórios documentados no `FakeScooterTransport`; associação nome/valor isolada da UI.
5. **UX:** preservar o FigCraft aprovado; botão principal retangular, largura total, fixo no rodapé da Home respeitando áreas seguras; farol, motores, modo, cruise, partida do zero e limite na tela principal; rolagem nunca oculta o botão; sem sobreposição em tamanhos de tela/escalas de fonte diferentes; estados visuais de conexão, confirmação, erro, timeout e telemetria desatualizada.
6. **Arquitetura/testes:** domínio ≠ transporte ≠ UI; só `FakeScooterTransport`; nenhum BLE real; ação só concluída após telemetria simulada; sem comandos duplicados com operação pendente; testes Compose UI + unitários dos estados críticos; screenshots reais do Compose comparados ao design.
7. **Coordenação:** OMC distribui tarefas independentes com isolamento (worktree por tarefa); revisão cruzada Codex (disponível a partir de 2026-11-04 09:27) sem bloquear o desenvolvimento — antes disso, Haiku só leitura; sem push/publicação; não tocar processos/worktrees de outros projetos.

8. **TDD e cobertura (sessão 06):** desenvolvimento test-first (vermelho/verde) nos seams S1–S5 (ver `AGENTS.md`); **≥ 80 % de linhas** em `core-domain`, `data` e lógica da UI, verificado por JaCoCo com falha de build; evidência vermelho→verde registrada na entrega.

**Entrega final da Sprint 4:** evidências de compilação, testes, screenshots da Home e lista de diferenças em relação ao FigCraft aprovado.

## Ambiente (verificado 2026-11-03)
Java 17 presente; **sem** Gradle, Android SDK nem kotlinc instalados. Instalar apenas dos releases oficiais (Gradle wrapper, `cmdline-tools`), conferindo checksum, em `tools/` (ignorado pelo git) e documentar em `docs/design/tooling.md` ou ADR.

## Fora do escopo
BLE real (Fase 4), comando ao patinete, push/publicação.
