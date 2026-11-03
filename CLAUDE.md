# iScooter Connect — regras do projeto

App Android independente para controlar/monitorar meu patinete elétrico (substitui o iScooter Club, `com.iscooter.car`). Uso exclusivo no meu equipamento.

## Regras fundamentais (inegociáveis)
1. **Investigação antes de implementação.** Nunca inventar comandos BLE, UUIDs, protocolo ou autenticação. Toda afirmação sobre o protocolo cita evidência.
2. **Protótipo `.figcraft` antes do frontend.** Arquivo: `design/iscooter-connect.figcraft`. Sem Compose até a validação formal.
3. **Independente do app original.** Se impossível, registrar a limitação em `docs/adr/` antes de qualquer alternativa.
4. **Bloqueio/desbloqueio é prioridade absoluta.** Estabilizar antes das demais funções.
5. **Comando enviado ≠ executado.** Só mostrar "concluído" após confirmação do patinete.
6. **Segurança.** Nenhum comando experimental com o patinete em movimento. Não contornar antifurto/autenticação de propriedade. Só equipamento próprio.
7. **Sem push, merge ou publicação sem autorização explícita.**
8. Sem copiar código proprietário para o app novo (engenharia reversa só para interoperabilidade).

## Classificação de achados (obrigatória em docs/research)
`[ESTÁTICO]` comprovado na análise estática · `[HIPÓTESE]` precisa validar · `[CONFIRMADO]` validado em teste real · `[DESCONHECIDO]`.

## Estrutura
- `research/original-apk/` APK (ignorado pelo git) · `research/decompiled/` saída JADX/Apktool (ignorado) · `research/captures/` logs privados (ignorado)
- `docs/research/protocol-analysis.md` entregável da Fase 1 · `docs/adr/` decisões · `docs/sprints/` plano
- `design/` protótipo FigCraft · `app/` módulos Android (`core-protocol`, `core-domain`, `data`, `ui`)

## Fases
0 Preparação → 1 Investigação → 2 Prototipação → 3 Frontend (mock) → 4 Comunicação → 5 Integração → 6 Otimização → 7 Validação. Não avançar para fase dependente de descoberta não confirmada. Plano: `docs/sprints/README.md`.

## Convenções
Kotlin, Compose, Material 3, Coroutines/StateFlow. Camada Bluetooth atrás de interface (`ScooterTransport`) com implementação simulada e real. UI nunca bloqueia em operações BT. Documentação em português; código/identificadores em inglês.

## Coordenação de agentes
Seguir `docs/process/coordenacao-agentes.md` (papéis, worktree por tarefa, revisão cruzada, fichas em `docs/tasks/`, scripts em `scripts/agents/`). Quem escreve não revisa. Só o coordenador/usuário tocam o patinete. Estado em `docs/STATUS.md` e `docs/RESUME.md`.
