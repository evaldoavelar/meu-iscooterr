# TASK s52-backup-core — formato, cifra e leitura/escrita do backup (parte 1 de 2; aprovado pelo proprietário, 2026-11-17)

Contexto e mockups aprovados: `design/mockups/{preferencias-sistema-backup,backup-criar,backup-concluido,restaurar,restaurar-concluido,primeira-abertura-backup}-v1-*.png`. Esta tarefa é só a **camada sem UI** (JVM, testável). A UI/integração é `TASK-s52-backup-ui.md` (depois).

## O que entra no backup
1. **Preferências** (`KeyValuePreferencesStore`: tema, unidades, `scooterAddress` = o patinete pareado, etc.).
2. **Histórico de viagens**: conteúdo cru de `files/trips.v1` (formato `TRIPS2`, com migração de `TRIPS1`; `FileTripRepository`) e todos os `files/routes/<id>.route` (`FileRouteStore`, formato versionado v1/v2).
3. **PIN** (opcional): texto do PIN lido do cofre (`PinVault.load()`, exige biometria; só em memória). Se não houver PIN salvo, o backup sai sem ele e o resumo diz "PIN não guardado".
Fora: log de eventos, logs de depuração, tiles em cache.

## Formato `.ixbak` (versão 1) — tudo cifrado, nada de PIN em claro
- Cabeçalho em claro (só dados não sensíveis): magic `IXBK`, versão (1 byte), `createdAtEpochMs`, `appVersion`, parâmetros do KDF (PBKDF2-HMAC-SHA256, **≥ 600 000 iterações**, salt 16 B aleatório), IV 12 B.
- Corpo: AES-256-GCM sobre o payload; **o cabeçalho inteiro entra como AAD** (adulterar data/KDF invalida o arquivo). Payload = ZIP em memória com entradas `preferences` (chave=valor, UTF-8), `trips.v1`, `routes/<id>.route`, `pin` (opcional) e `manifest` (contagens: viagens, rotas; para o resumo da tela de restauração).
- Senha: `CharArray`, mínimo 8 caracteres (validar na escrita; na leitura só tentar). Zerar arrays após o uso. Erro de senha **e** arquivo adulterado/corrompido têm o mesmo resultado genérico (`BackupError.WrongPasswordOrCorrupt`); versão desconhecida = `UnsupportedVersion`; arquivo maior que um teto (ex. 32 MB) = `TooLarge` (zip bomb: limitar descompressão e número de entradas, rejeitar `..`/caminhos absolutos).
- **Nunca** logar senha, PIN ou conteúdo; nenhum `toString` com dados sensíveis (`BackupContents.toString` redigido); PIN/senha não vão em exceções.

## API (core-domain, puro Kotlin; sem Android)
- `BackupContents(preferences: Map<String,String>, tripsFile: ByteArray?, routes: Map<String,ByteArray>, pin: CharArray?)` + `BackupSummary(createdAtMs, appVersion, tripCount, routeCount, hasPin, scooterName?)`.
- `BackupCodec.encode(contents, password, createdAtMs, appVersion, random: SecureRandom): ByteArray` e `BackupCodec.peek(bytes): BackupHeader | error` (só cabeçalho, sem senha: data/versão/tamanho) e `BackupCodec.decode(bytes, password): Result<BackupContents, BackupError>`.
- Em `data`: `BackupFiles` lê/escreve `trips.v1`/`routes/` de um diretório (injeta `File`), com **escrita atômica** (temp + rename) e **validação antes de aplicar** (parsear `trips.v1` com o parser existente; rotas com o parser do `FileRouteStore`; rejeitar o backup inteiro se algo inválido; nada é aplicado pela metade). `apply(contents)` substitui o histórico atual e devolve o que fazer (recarregar repositório). Preferências são validadas por `KeyValuePreferencesStore.read` (valor inválido cai no padrão).
- Chaves de preferência desconhecidas são ignoradas; `scooterAddress` passa por `Preferences.normalizeAddress`.

## Aceite (TDD, valores esperados independentes)
- Round-trip (conteúdo igual byte a byte), senha errada → `WrongPasswordOrCorrupt`, 1 bit alterado no cabeçalho **ou** no corpo → idem, versão futura → `UnsupportedVersion`, ZIP com `../x` rejeitado, arquivo truncado rejeitado, zip bomb rejeitado, backup sem PIN, `toString` sem vazamento, KDF ≥ 600 000 (teste lê o cabeçalho), dois backups do mesmo conteúdo têm salt/IV diferentes, aplicar backup inválido não altera o histórico atual, aplicar válido troca `trips.v1`+rotas atomicamente (simular falha no meio → estado anterior intacto).
- `cd app && ./gradlew clean check` EXIT 0. Sem push, sem tocar patinete/`design/`. ADR curto `docs/adr/0017-backup-restauracao.md` (formato, ameaça: arquivo roubado sem a senha = nada legível; senha fraca = força bruta offline, aceito pelo proprietário; PIN é credencial do próprio equipamento, uso restrito ao dono; o cofre continua exigindo biometria para ler/gravar). Revisor independente (Sol).
