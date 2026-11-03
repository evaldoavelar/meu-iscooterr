# Roteiro da captura passiva (Sprint 2) — para o proprietário
Só observação do app **original**. Nenhum comando nosso ao patinete. Patinete **parado no cavalete**, sem ninguém montado. Pare tudo se ele se mover.

## Antes (Claude + você)
1. Telefone por USB, depuração USB autorizada (`adb devices` deve listar o aparelho).
2. Claude liga “Registro de snoop HCI” (Opções do desenvolvedor) e confirma. **Você** religa o Bluetooth do telefone (isso desconecta fones etc.).
3. Feche o app original (forçar parada) e comece a gravar.

## Roteiro (anote o horário aproximado de cada passo, ou grave a tela)
| # | Passo | Cobre |
|---|-------|-------|
| 1 | Abrir o app original e esperar conectar sozinho; ficar ~30 s parado | H1 H3 H6 H9 t_scan t_conn t_auth |
| 2 | Bloquear → esperar 10 s → desbloquear (2 vezes seguidas; use o PIN normalmente se ele pedir, **sem errar de propósito**) | H4 H5 H8 H11 t_cmd t_ack t_state |
| 3 | Desativar o recurso de proximidade/distância nas configurações do app; bloquear → desbloquear | H7A |
| 4 | Desligar Wi-Fi e dados móveis do telefone **sem sair da conta**; bloquear → desbloquear; religar a rede | H7B |
| 5 | Deixar ocioso ≥ 10 min com o app aberto; bloquear → desbloquear | H11-a (sessão longa) |
| 6 | Repetir o passo 2 mais 3 vezes (total ≥ 5 ciclos) | tempos |
| 7 | Fechar o app (ou sair da tela) | H6 (CONNECT 0) |

| 8 (opcional, só no fim) | No app original, com o patinete parado: ligar e desligar o farol; trocar o limite de velocidade e **voltar ao valor original**; alternar km/milhas e **voltar ao original**. Anote o valor inicial de cada um. | farol, velocidade, unidade (backlog) |

Não faça: logout, desparear, remover o patinete da conta, atualizar firmware, alterar/limpar o PIN, trocar o tipo de bloqueio.

## Depois
Claude puxa o `bugreport`/btsnoop para `research/captures/` (ignorado), roda `scripts/analysis/btsnoop_sanitize.py`, e só a saída sanitizada vira `docs/research/parts/S2-captura-resultados.md`. O arquivo bruto não vai para o git nem para o Codex.
