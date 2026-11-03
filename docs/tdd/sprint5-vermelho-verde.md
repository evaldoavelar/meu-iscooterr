# Sprint 5 — evidência vermelho → verde

## 5.1 Navegação (S1)
- Vermelho: `NavigatorTest` (5 testes) adicionado antes da implementação; o build falhou com `Unresolved reference 'Navigator'` / `'AppDestination'` (compileTestKotlin de `core-domain`).
- Verde: `Navigator` + `AppDestination` mínimos em `core-domain`; `:core-domain:test --tests '*NavigatorTest*'` passa.
- Refatoração (fora do ciclo): `HomeDestination` (ui) substituído por `AppDestination`; `AppRoot` usa `Navigator`. `AppRootTest` e `HomeScreenTest` existentes seguem verdes.
- Gate: `clean check :android-app:assembleDebug` verde na branch `sprint-5/navegacao`.
