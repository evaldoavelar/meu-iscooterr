# ADR 0017 — Backup e restauração (`.ixbak` v1)
Status: aceito · Tarefa S52-BACKUP-CORE · Extensão dos ADRs 0012 (rota é dado sensível), 0014 (histórico) e do cofre do PIN

## Contexto
O proprietário quer exportar/restaurar preferências, histórico (`trips.v1` + `routes/`) e, opcionalmente, o PIN do próprio equipamento. Esta tarefa é só a camada sem UI (`core-domain/.../backup`, `data/BackupFiles`); a tela é `TASK-s52-backup-ui`.

## Decisão: formato
- Cabeçalho em claro (não sensível): `IXBK` | versão (1 byte, = 1) | `createdAtEpochMs` | `appVersion` | KDF (1 = PBKDF2-HMAC-SHA256) | iterações (4 B, **600 000**; leitor rejeita < 600 000 ou > 3 M) | salt 16 B | IV 12 B. Todos aleatórios por arquivo (`SecureRandom`).
- Corpo: AES-256-GCM (tag 128 bits) do payload, com o **cabeçalho inteiro como AAD**: adulterar data, versão do app ou KDF invalida o arquivo.
- Payload: ZIP em memória com `preferences` (chave=valor UTF-8), `trips.v1` (bytes crus), `routes/<id>.route`, `pin` (opcional, texto) e `manifest` (contagens, conferidas na leitura).
- Senha: mínimo 8 caracteres (validado só na escrita). Senha errada, bit alterado, truncado e entrada malformada autenticada têm o mesmo resultado genérico (`WrongPasswordOrCorrupt`); versão desconhecida = `UnsupportedVersion`; excesso = `TooLarge`.
- Limites anti zip-bomb: arquivo ≤ 32 MiB, descompressão ≤ 64 MiB, ≤ 20 000 entradas; só os nomes conhecidos são aceitos (`..`, caminho absoluto, subpasta, duplicata = `InvalidContent`).

## Decisão: restauração
- `BackupFiles.apply` valida o backup inteiro antes de tocar no estado (parser estrito de `FileTripRepository`, parser do `FileRouteStore` por rota) e troca `trips.v1` + `routes/` em etapas por `rename` atômico, com rollback se qualquer etapa falhar. Backup inválido não altera nada.
- Preferências: só as chaves conhecidas; valor inválido volta ao padrão; `scooterAddress` passa por `normalizeAddress`. O PIN não é gravado por esta camada: o cofre continua exigindo biometria para ler e gravar.
- Fora do backup: log de eventos, logs de depuração, tiles em cache.

## Ameaças e riscos aceitos
- Arquivo roubado sem a senha: nada legível (rotas, viagens, MAC, PIN e preferências estão cifrados; só data, versão do app e parâmetros do KDF ficam em claro).
- Senha fraca: força bruta offline é possível; o PBKDF2 só encarece. Risco aceito pelo proprietário.
- O PIN é credencial do próprio equipamento do dono; o backup com PIN deve ficar sob guarda do dono. Uso restrito ao próprio patinete (CLAUDE.md, regra 6).
- Senha e PIN nunca em log, exceção ou `toString`; arrays internos (chave, payload, bytes do PIN) são zerados; `BackupContents.wipe()` zera o conteúdo; o chamador zera a própria senha.

## Consequências
- Ao restaurar, o chamador deve recarregar o repositório de viagens, e `apply` não pode rodar durante gravação de viagem/rota.
- Troca com marcador de commit: `trips.v1.bkcommit` é criado depois de todo o stage validado e antes da primeira troca, e só apagado no fim, depois de limpar os `.bkold`. `BackupFiles.recover()` é público e idempotente e DEVE ser chamado na inicialização do app, antes de abrir o repositório (a tarefa de UI liga isso). Com marcador, completa a troca (rotas e viagens novas já no lugar) ou a desfaz por inteiro: nunca estado misto. Sem marcador, descarta stages órfãos e só descarta `.bkold` órfão se o estado atual estiver íntegro (senão restaura ou mantém). `apply` chama `recover` antes de começar.
- Sem fsync (mesmo padrão do `FileTripRepository`): queda de energia pode perder a troca, mas o rename atômico + marcador mantém o estado consistente em morte de processo.
- Backup sem `trips.v1` substitui (apaga) o histórico atual; a UI confirma antes.
- Zeramento de senha/PIN/chave é de melhor esforço: buffers internos do JDK (PBEKeySpec, Cipher, ZipInputStream, String) podem reter cópias.
