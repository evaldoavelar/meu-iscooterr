# Evidência TDD — módulo `ui` (sessão 07)
Comando: `source scripts/android-env.sh && cd app && ./gradlew :ui:testDebugUnitTest`. Valores esperados: FigCraft (`design/home.py`), plano Fase 3.

| Ciclo | Teste (seam) | Vermelho | Verde |
|---|---|---|---|
| 1 | `HomeUiMapperTest` botão principal (S4 lógica) | build falha: `HomeUiMapper`/`MainButtonKind` não resolvidos | ver abaixo |
| 1 | (cont.) | — | `BUILD SUCCESSFUL`, 10 testes (após `compileSdk = 37`, exigido pelo Compose 1.12.1 — SDK baixado pelo AGP em `tools/`) |
| 2 | habilitação/motivos por controle (movimento, velocidade desconhecida, telemetria velha, desbloqueio com regra própria, pendente) | build falha: `headlight`, `motors`, `mode`… não resolvidos | `BUILD SUCCESSFUL`, 18 testes |
| 3 | valores exibidos (82%, 0 km/h, 1.248 km, `--`), catálogo de modos, alvo otimista pendente, chip de conexão, mensagens de resultado | build falha: `battery`/`connection`/`ConnectionKind`… não resolvidos; construtor sem `deviceName` | `BUILD SUCCESSFUL`, 27 testes |
| 4 | `HomeViewModelTest` (S3/S4-lógica: conectar, pendente→confirmado só após telemetria, sem duplicidade, movimento ignora, falha→retry, timeout) | build falha: `HomeViewModel` não resolvido | 1ª execução travou (loop de tick não ocioso no `runTest`); corrigido cancelando `viewModelScope` no corpo do teste → `BUILD SUCCESSFUL`, 36 testes (27 mapper + 9 ViewModel) |
| 5 | `ThemeTokensTest` (tokens ↔ `lib.py` COLORS) e `HomeScreenTest` (S4, 17 testes Robolectric: conteúdo, botão fixo, rolagem, tela pequena + fonte 2.0, desabilitação, ações, resultados, menu ⋮) | build falha: pacotes `theme`/`home` inexistentes | ver abaixo |
| 5 | (cont.) | — | Após tema/ícones/`HomeScreen`: 17 testes `HomeScreenTest` + 2 `ThemeTokensTest` verdes. **Nota honesta:** o vermelho foi falha de compilação (pacotes inexistentes); na 1ª execução com a implementação completa os testes passaram — não houve vermelho por asserção nesse ciclo. |
| 6 | Telas pequenas (`w320dp`) fonte 1.5 e 2.0: cards sem sobreposição, botão visível, 4 modos acessíveis | — (adicionado sobre a implementação; verde de imediato) | `BUILD SUCCESSFUL` |
