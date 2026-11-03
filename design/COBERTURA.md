# Matriz de cobertura funcional
Frames no arquivo `home.figcraft` (páginas "Home Escuro" e "Home Claro", mesmos nomes). Rótulos: `[CONFIRMADO]` visto em captura real; `[HIPÓTESE]` ainda sem confirmação no patinete.

| Função | Onde aparece | Estados desenhados | Evidência do protocolo |
|---|---|---|---|
| Bloquear / desbloquear | Home (botão largo no rodapé) | bloqueado, desbloqueado, enviando, confirmado (bloq./desbloq.), falha, conectando, desconectado, em movimento (desabilitado) | `[CONFIRMADO]` CMD 0x32, ACK ~51 ms, estado ~419 ms |
| Farol | Home (linha com interruptor) | normal, enviando | `[ESTÁTICO]` CMD 0x33 |
| Tração 1 / 2 motores | Home (segmentado) + sheet Tração | normal, enviando, sheet com 1 ou 2 motores | `[CONFIRMADO]` observada na captura (uma roda) |
| Modo de velocidade (Pedestre/Eco/Race/Sport) | Home (segmentado) | normal, enviando | `[CONFIRMADO]` CMD 0x35 valores 0–3; mapeamento nome↔valor `[HIPÓTESE]` |
| Cruise | Home (cartão + interruptor) | normal, enviando | `[CONFIRMADO]` CMD 0x38 |
| Partida do zero | Home (cartão + interruptor) | normal, enviando | `[CONFIRMADO]` observada |
| Limite de velocidade | Home (cartão + slider) + sheet Limite | normal, enviando, sheet | `[CONFIRMADO]` CMD 0x36, 20–50 km/h |
| Bateria | Home (cartão), Telemetria (pílula), Detalhe da viagem | normal, sem dado ("--") | `[ESTÁTICO]` |
| Velocidade atual | Home (cartão → Telemetria), Telemetria (velocímetro 0–60) | parado, andando | `[CONFIRMADO]` telemetria ~300 ms |
| Status de conexão | Home (chip), Telemetria (chip), Recuperação, Primeiro uso | conectado, conectando, desconectado, falha, reconectando | `[CONFIRMADO]` |
| Telemetria: consumo (W, Wh/km), corrente, tensão, temp. motor 1 e 2, odômetros | Telemetria | parado, andando, 1 motor (motor 2 "sem leitura") | `[HIPÓTESE]` campos a confirmar |
| Viagem atual (média, máx., duração, altimetria) + zerar | Telemetria (cartão, menu ⋮ no topo, confirmação) | parado, andando, menu, diálogo | calculado no app; altimetria via GPS do celular `[HIPÓTESE]` |
| Histórico de viagens e mapa | Histórico, Detalhe da viagem | lista, vazio, 5 viagens de exemplo | local no celular; fonte do mapa **em aberto** |
| Configurações (tema, unidades, reconexão, trajeto, esquecer patinete, apagar histórico, sobre) | Configurações | padrão | n/a |
| Primeiro uso / cadastro | Primeiro uso 1–3 | procurando, encontrado, conectado | `[CONFIRMADO]` sem PIN neste patinete |
| Recuperação | Recuperação | patinete não encontrado, Bluetooth desligado, permissão negada, reconectando | n/a |

Lacunas: sem tela de PIN (o patinete testado não tem; se `lock_has_pwd=1`, cadastrar uma vez no Keystore); mapa real não escolhido; valores de exemplo marcados como `dados de exemplo` apenas neste documento (a UI não exibe selo).
