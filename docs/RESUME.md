# RESUME (atualizado após T1 e T3–T7 parcial)
## Estado
Fase 1 em andamento. Commits: b17da5a (estrutura), 3554a14 (coordenação), 7362974 (ferramentas), 11ab78f (plano), 8309e29 (T1), 11c7fbc (encerramento). Decompilado em `research/decompiled/` (ignorado). Telefone SM_S911B conectado via adb com app 1.0.20 (hashes iguais ao APK analisado).
## Lições
- JADX exit 3 = 397 métodos com erro, fontes OK; o `EXIT` do script de background mascara o código do JADX, conferir o log.
- Constantes `static final` são inlinadas: `grep BleConstants.NOME` não acha usos; seguir por `q3.a` (construtor de comandos ofuscado).
- zsh: aspas em globs de `--include`.
## Dívidas / próximos
T2 (manifest via Apktool), T9 (nuvem/login/hasBind; ADR 0002), parser de notificações FF61, critério de scan, fluxo CONNECT/CURRENT_USER_CAR, T11 revisão cruzada por agente ≠ autor, T12 plano de testes. Spec item 12+ já incorporada.
