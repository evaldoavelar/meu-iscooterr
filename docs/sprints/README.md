# Plano de sprints

Legenda: ☐ pendente · ◐ em andamento · ☑ concluído

## Sprint 0 — Preparação (Fase 0)
| ID | Tarefa | Depende de | Critério de aceitação |
|----|--------|-----------|----------------------|
| S0-1 | Repo Git, estrutura, .gitignore, commit inicial | — | `git log` com commit inicial; APK/capturas ignorados |
| S0-2 | CLAUDE.md, AGENTS.md | S0-1 | Regras 1–8 presentes |
| S0-3 | Ambiente de RE (java, jadx, apktool, adb) ☑ | — | Comandos `--version` funcionam |
| S0-4 | APK em `research/original-apk/` ☑ (hashes em fase-1-plano.md) | — | SHA-256 registrado |
| S0-5 | Verificar OMC/skills e FigCraft (CLI/MCP) | — | Versões registradas em `docs/design/tooling.md` |

## Sprint 1 — Análise estática (Fase 1)
| ID | Tarefa | Depende de | Aceitação |
|----|--------|-----------|-----------|
| S1-1 | Manifest: permissões, services, receivers | S0-3, S0-4 | Tabela em protocol-analysis.md |
| S1-2 | Localizar camada BT (BLE vs Classic), libs nativas | S1-1 | Classes e rótulos de evidência |
| S1-3 | UUIDs, filtros de descoberta, identificação do patinete | S1-2 | Cada UUID com referência de código |
| S1-4 | Formato de mensagens, lock/unlock, auth/pareamento | S1-3 | Hipóteses listadas com plano de teste |
| S1-5 | Dependência de servidor/sessão/credenciais | S1-1 | Fluxo de login e expiração descrito |

## Sprint 2 — Validação experimental (Fase 1)
Capturas HCI/logcat autorizadas no patinete parado; confirmar cada `[HIPÓTESE]`. Aceitação: protocol-analysis.md sem hipóteses críticas abertas para lock/unlock.

## Sprint 3 — Protótipo FigCraft (Fase 2)
5 telas, componentes, variáveis, tema claro/escuro, estados. Aceitação: arquivo abre no FigCraft e fluxo validado por mim.

## Sprints 4+ — Frontend mock, protocolo real, integração, otimização (30 tentativas/cenário: mediana, p95, taxa de sucesso), validação
Detalhar após conclusão da Sprint 2.

## Critérios de aceitação do projeto (conclusão)
- [ ] App inicia no Android alvo
- [ ] Patinete cadastrado uma única vez, respeitando a autenticação
- [ ] Conexão automática em condições normais
- [ ] Bloqueio/desbloqueio no equipamento real
- [ ] Estado confirmado pelo patinete; nunca sucesso sem confirmação válida
- [ ] Erros de conexão recuperáveis
- [ ] Sem login manual repetido em condições normais
- [ ] Dashboard simples e responsivo
- [ ] Sem comandos duplicados indevidos
- [ ] Testes de conexão, segurança e bateria documentados
- [ ] `.figcraft` continua editável
- [ ] APK final compilável e instalável

## Papéis sugeridos (spec item 12)
Coordenador Claude · Analista RE (APK/docs) · Especialista Bluetooth · Designer UI/UX (FigCraft) · Dev Android (Kotlin/Compose) · QA/segurança · Revisor externo (Codex/agy). Sem edição simultânea dos mesmos arquivos; isolamento por worktree.
