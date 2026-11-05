# Handoff — card de modo desabilitado (sprint-19/mode-card-disabled)

- `ChoiceCard` (HomeComponents.kt): com `options` vazio desenha card visível, alfa 0,5, semântica `disabled()`, sem clique, com texto "Modos indisponíveis neste momento". HomeScreen.kt compõe o card sempre.
- Testes atualizados: HomeScreenTest (card presente/desabilitado + fonte 1,5x e 2x), HomeBleRealFramesTest.
- TDD: antes da implementação, 4 testes falharam (251 executados); depois, `:ui:testDebugUnitTest` BUILD SUCCESSFUL.
- Comando: `source /home/evaldo/Documents/ix7/scripts/android-env.sh && cd app && flock /tmp/gradle.lock gradle :ui:testDebugUnitTest`
- Nenhum golden/screenshot alterado. `clean check` NÃO executado (coordenador).
