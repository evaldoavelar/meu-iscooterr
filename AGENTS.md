# AGENTS.md — iScooter Connect (Claude, Codex, Antigravity)

Fonte única das regras: [`CLAUDE.md`](CLAUDE.md). Leia antes de agir. Resumo operacional:

- Fase atual: ver `docs/sprints/README.md`. Não pule fases.
- Proibido: inventar protocolo, enviar comando de controle com o patinete em movimento, contornar antifurto, commitar APK/capturas/credenciais, fazer push/publicação sem autorização (merge local só após revisor independente aprovar e `clean check` verde, ver CLAUDE.md regra 7).
- Todo achado técnico recebe rótulo `[ESTÁTICO]/[HIPÓTESE]/[CONFIRMADO]/[DESCONHECIDO]` e referência (classe, arquivo, linha ou captura).
- Registre decisões em `docs/adr/NNNN-titulo.md` e resultados em `docs/research/`.
- Autoria e revisão em passes separados; quem escreve não aprova.
- Comando "enviado" nunca vira "concluído" sem confirmação do dispositivo.
- Método de trabalho: `docs/process/coordenacao-agentes.md`. Workers: só a tarefa da ficha em `docs/tasks/`, nunca commitar, nunca tocar no patinete/Bluetooth real.
- **Desenvolvimento com TDD (decisão do proprietário, sessão 06):** um teste vermelho → implementação mínima verde, um de cada vez, nos seams aprovados (S1 reducer/máquina de estados · S2 `FakeScooterTransport` com relógio virtual · S3 ViewModel + Fake · S4 Compose UI na JVM · S5 screenshots vs `design/out/`). Valores esperados vêm de fonte independente (spec, `docs/research`, FigCraft), nunca do código testado. Refatoração fica fora do ciclo (passe de revisão). Bug começa com teste que o reproduz.
- **Cobertura mínima: 80 % de linhas** (JaCoCo, build falha abaixo) em `core-domain`, `data` e lógica da UI (ViewModels/estado); Composables medidos e reportados, `@Preview`/tema/código gerado excluídos.
