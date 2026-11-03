# Direção de UX/UI (definida pelo proprietário em 2026-11-03)
Referência: imagem-guia "iScooter Connect — Guia de UX/UI, redesign (Android)" (dark, azul/ciano de destaque). É referência de hierarquia e agrupamento, não arte final.

## Objetivo
Home muito mais rápida que o app original: tudo que é frequente visível, sem menus escondidos, uso com uma mão.

## Home (de cima para baixo, por prioridade)
1. Barra superior: "iScooter Connect" + engrenagem. Cartão de conexão ("Conectado ao ix7Pro", ícone Bluetooth, chevron → Detalhes).
2. **Ação principal**: botão circular grande com anel luminoso, ícone de cadeado, "Bloqueado / Toque para desbloquear" (ou o inverso).
3. Info essencial (2 cartões): Bateria %, Velocidade atual km/h.
4. Controles principais: **Farol** (interruptor) · **Tração** (segmentado `1 motor | 2 motores`, acesso direto) .
5. **Modo de velocidade** (segmentado `Pedestre | Eco | Race | Sport` — 4 modos informados pelo proprietário; o app original enviou valores 0–3 no CMD 0x35 `[CONFIRMADO]`, o mapeamento nome↔valor é `[HIPÓTESE]`; não mostrar km/h por modo até confirmar).
6. Controles secundários (3 cartões em linha): **Cruise** (interruptor) · **Partida 0** (interruptor) · **Limite** (valor km/h, abre seletor).
7. Barra inferior: Início · Detalhes · Configurações.

## Telas secundárias
- **Tração e limite de velocidade**: controlados direto na home (segmentado de tração; cartão de limite com slider 20–50 km/h). Sem sheets separados (decisão do proprietário, sessão 5).
- **Detalhes do dispositivo/telemetria**: cartão do patinete + lista (bateria, odômetro total/atual, tensão, corrente, temp. motor 1/2, versão do controlador).
- **Menu ⋮ na home** (topo direito, no lugar da engrenagem): Telemetria · Preferências · Histórico de viagens (decisão do proprietário, sessão 5). Preferências = antiga tela Configurações.
- Primeiro uso/pareamento, Recuperação (BT desligado, permissão negada, fora de alcance).

## Estados obrigatórios (home)
desconectado · conectando · conectado+bloqueado · conectado+desbloqueado · **comando em andamento** (por controle: spinner/"aguardando o patinete", nunca "ok") · **confirmado** (só após telemetria) · erro/falha (com Tentar de novo). "Em movimento" (velocidade>0): botão de bloqueio desabilitado com motivo `[ESTÁTICO]` o app original recusa lock com velocidade>0; demais controles: comportamento `[HIPÓTESE]`/a decidir com o proprietário.

## Design system
Paleta: Primária azul (ação/seleção), Sucesso verde (conectado/OK), Pendente laranja (aguardando), Erro vermelho, Superfície cinza-azulado (cards). Componentes: botão principal, segmentado, interruptor, controle de valor (chip com chevron), cartão de info, cartão de conexão, bottom sheet, barra inferior, banner de erro. Temas Claro e Escuro por variáveis (Escuro é o tema de referência).

## Entregas
`.figcraft` com componentes reutilizáveis e variáveis; PNGs; protótipo navegável; **matriz de cobertura funcional** (`design/COBERTURA.md`: função × tela/estado onde aparece) para guiar a implementação Android depois (mapa componente → composable).
Funções a cobrir: bloquear/desbloquear, farol, tração 1/2 motores, modo de velocidade, cruise, partida do zero, limite de velocidade, bateria, velocidade atual, status de conexão, detalhes/telemetria.

## Regras que continuam valendo
Sem campo de PIN (patinete sem PIN `[CONFIRMADO]`); "concluído/confirmado" só após telemetria; nenhuma UI mostra rótulos de pesquisa nem dados de captura; valores de exemplo marcados "PROTÓTIPO · dados de exemplo"; interações que simulam a telemetria rotuladas "SIMULAÇÃO".

## Telemetria (2026-11-03)
Tela secundária (a partir dos cartões Velocidade/Total da home): velocímetro 0–60, bateria, **consumo em tempo real** (potência = tensão×corrente; Wh/km andando), corrente, tensão, **temperatura dos 2 motores** (motor 2 "sem leitura" em 1 motor), odômetros e cartão **Viagem atual** (velocidade média, máxima, duração, altimetria). Média/máxima/duração são calculadas pelo app a partir da velocidade; **altimetria vem do GPS/barômetro do celular** (o patinete não é fonte conhecida) `[HIPÓTESE]`. Corrente, tensão e temperaturas dos motores: campos da telemetria ainda a confirmar `[HIPÓTESE]`. Sem versões de comunicação/controlador na UI.

## Viagens e histórico (2026-11-03)
- Cartão "Viagem atual" (Telemetria) tem menu ⋮: **Zerar viagem** (confirmação; salva a viagem no histórico, não envia nada ao patinete) e **Ver histórico**.
- Tela **Histórico**: resumo dos últimos 30 dias, lista por data com miniatura do trajeto, distância, duração, média/máx.; estado vazio. **Detalhe da viagem**: mapa com trajeto, início/fim, distância, duração, média, máxima, altimetria, consumo médio, bateria usada.
- Dados e privacidade: viagens ficam só no celular. Trajeto exige permissão de localização e GPS do celular (o patinete não fornece posição) `[HIPÓTESE]`. **Decisão aberta:** fonte do mapa (tiles OpenStreetMap com atribuição, mapa offline, ou SDK de terceiros com chave); o protótipo usa mapa estilizado "de exemplo".

## Decisões do proprietário (2026-11-03, fim da sessão 4)
1. Com o patinete em movimento, **só o bloqueio fica desabilitado**; os demais controles continuam habilitados (cada um mostra "aguardando o patinete" até a confirmação). `[HIPÓTESE]` de que o patinete aceita; validar em teste real antes de liberar.
2. Mapa do histórico: **OpenStreetMap** (exibir a atribuição "© colaboradores do OpenStreetMap"; respeitar a política de uso de tiles ou usar cache/servidor próprio).
3. Nome do app: **mantido** "Meu iScooter" (marca de terceiros no nome: risco aceito pelo proprietário).
4. **Sem** selo "dados de exemplo" na UI.
5. Textos sem evidência reescritos: tração "Usa só um motor" / "Usa os dois motores"; onboarding sem promessa de reconexão automática.
