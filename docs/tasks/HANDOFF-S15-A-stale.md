# Handoff — TASK-S15-A: bloqueado no Gradle

## Comando de teste realmente executado

Da raiz `/home/evaldo/Documents/wt/s15a`, em foreground, aguardado até terminar:

```sh
flock /tmp/ix7-gradle.lock bash -c 'source scripts/android-env.sh && cd app && ./gradlew --no-daemon --offline :core-domain:test --tests "*HomeGroupAgeTest.unlockWithStaleMeter1IsAllowedUnlessMoving"; echo EXIT $?'
```

**Resultado: `EXIT 1` do Gradle**, antes de executar testes. O shell externo retorna 0 por terminar em `echo`, sem representar sucesso do build.

[ESTÁTICO] O wrapper não consegue criar `gradle-9.8.2-bin.zip.lck`: `Read-only file system`. O comando de diagnóstico executado `ls -ld tools tools/gradle-home` (EXIT 0) mostrou o link para `/home/evaldo/Documents/ix7/tools/gradle-home`, fora das raízes de escrita. Referência: `scripts/android-env.sh`, `GRADLE_USER_HOME`.

## Desvios do TDD

- `--offline` acrescentado ao comando para respeitar a proibição de rede real.
- Só foi acrescentado o teste `HomeGroupAgeTest.unlockWithStaleMeter1IsAllowedUnlessMoving`; nenhuma implementação foi alterada. Não houve vermelho funcional nem verde observado. Evidência em `docs/tdd/s15a.md`.
- Trabalho interrompido conforme `docs/process/coordenacao-agentes.md`, §8, após a recusa de escrita do wrapper.

## Pendências

- Coordenador disponibilizar cache Gradle gravável compatível com o sandbox e com o comando obrigatório; o worker não alterou links, scripts ou configuração fora do escopo.
- Executar o teste inicial e continuar os quatro itens da ficha, um teste por ciclo, registrando os vermelhos funcionais antes das correções.
- Verificar a presença/habilitação do botão de desbloqueio na UI com Meter1 velho.
- Executar o aceite `clean check :android-app:assembleDebug`, ainda não executado; não existe `EXIT 0` de aceite nesta entrega.
- Revisão independente pendente. Sem commit, push, BLE real ou adb.

## Retomada após atualização explícita do proprietário

O histórico acima permanece preservado. A proibição de Gradle nesta rodada substitui a antiga pendência de disponibilizar cache gravável ao worker; execução agora é responsabilidade do coordenador.

### Comandos de verificação realmente executados nesta retomada

| Comando, da raiz deste worktree | EXIT observado |
|---|---|
| `git status --short --branch` | 0 |
| `git worktree list` | 0 |
| `free -m` | 0 |
| `git diff --stat` | 0 |
| `git diff --check` | 0 |
| `git status --short` | 0 |

Nenhum comando Gradle foi executado nesta retomada. O `EXIT 1` acima pertence à tentativa histórica já presente no worktree. `git diff --check` valida somente whitespace.

### Desvios do TDD

- Por autorização explícita, testes editados antes das correções, mas sem vermelho/verde funcional observado. Motivos e vermelhos **esperados** por ciclo constam em `docs/tdd/s15a.md`; os comandos futuros estão nesse diário, não nesta lista de execução.
- Item 1: teste de domínio herdado preservado, acrescentadas guardas no mapper e Compose. Item 2: guardas isoladas do mapper para bateria velha, nulos e formatação. São regressões que já devem passar, sem inventar vermelho.
- Item 3: teste da publicação intermediária antes de consumir snapshot/idades atomicamente; teste adicional antes de alinhar a fixture `Spy.agedTelemetry` ao próprio fluxo sobrescrito. Produção só em `HomeController`/`HomeReducer`; fixture só em `HomeViewModelTest`.
- Item 4: teste de movimento com Meter1, leitura geral ou ambos velhos antes da guarda de precedência. Sem refatoração ou alterações de contrato/transporte.

### Pendências vigentes

- Coordenador executar os testes individuais e comandos agrupados por item em `docs/tdd/s15a.md`, devolver saídas e respectivos `EXIT`. Nenhuma compilação/teste/cobertura desta retomada foi verificada.
- Coordenador executar o aceite completo com gate ≥80% e `assembleDebug`, exigindo `EXIT 0` impresso; ainda não executado.
- Revisão independente dos dois arquivos de produção, sete testes novos, teste herdado e ajuste mínimo de fixture. Autor não aprova.
- Sem commit/push, sem BLE/adb/rede real; somente arquivos dos módulos permitidos e os dois documentos exigidos.
