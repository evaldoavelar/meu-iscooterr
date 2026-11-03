# T12 — Plano de testes experimentais (Sprint 2)
**Nenhum teste é executado sem aprovação explícita do proprietário.** Patinete **parado, sobre o cavalete**, rodas livres, sem pessoa montada. Apenas leitura/captura do tráfego do app **original** no próprio aparelho (SM_S911B). Nenhum comando enviado por nós; nenhum OTA, SET_SN, UNBIND_RESET.

## Preparação
1. Habilitar “Registro de snoop HCI Bluetooth” (Opções do desenvolvedor), reiniciar Bluetooth.
2. `adb logcat -c`; abrir o app original; ações abaixo; `adb bugreport` ou `adb pull` do btsnoop para `research/captures/` (ignorado pelo git). **Remover/mascarar o PIN antes de qualquer commit.**

## Hipótese → observação → critério
| ID | Hipótese | Ação no app original | Critério de confirmação |
|----|----------|----------------------|--------------------------|
| H1 | GATT FF60 / notify FF61 / write FF62 | Conectar | Serviço e características no log de descoberta |
| H2 | Quadro `1A A1 … CRC16 … 1F F1`, CRC Modbus | Qualquer ação | CRC recalculado dos bytes capturados confere (ordem de bytes) |
| H3 | Heartbeat 0x10 ~1 s mantém a sessão | Conectar e aguardar | Quadros 0x10 periódicos; resposta Meter1 (0x20) |
| H4 | LOCK 0x32 modo 1 = bloquear / modo 0 = desbloquear, PIN em dígitos 0–9 | Bloquear e desbloquear | Bytes de DADOS observados; **PIN mascarado no relatório** |
| H5 | Estado em Meter1 `byte[6]` bit5 (`lockStatus`; 1=bloqueado) | Alternar lock | Bit muda após o comando; tempo entre comando e estado (medir) |
| H6 | CONNECT 0x30(1) na abertura, 0x30(0) ao desconectar | Abrir/fechar sessão | Quadros presentes e ordem |
| H7 | 0x41 só serve à proximidade, não ao lock manual | Desligar “distância/desbloqueio por proximidade”, repetir H4 | Lock funciona com 0x41=0; sem sessão de conta (modo avião, sem login) se possível |
| H8 | Sem ACK dedicado de 0x32 | H4 | Resposta existente? Só mudança de estado? |
| H9 | Identificação do anúncio (nome/prefixo/UUID) | Scan | Nome/manufacturer data do anúncio; endereço estável ou rotativo (repetir após religar) |
| H10 | Bateria %, velocidade em Meter1 | Observar | Compatível com o painel do patinete |

## Segurança
Parar de imediato se o patinete se mover ou apresentar comportamento inesperado. Não repetir comandos em laço. Registrar versão do app (1.0.20) e do firmware.

## Entregáveis
`docs/research/parts/S2-captura-resultados.md` com rótulos `[CONFIRMADO]/refutado`, sem PIN, sem MAC completo (mascarar).
