# S48 TRIPSPEED — correção da revisão r1

[ESTÁTICO] `Telemetry.forTrip` (`GpsSpeed.kt`) exige `meter1AtMs` conhecido e idade ≤ `HomeRules.STALE_AFTER_MS` para usar `scooterSpeedKmh`; Meter2 não pode renovar o fallback. `HomeViewModel` passa o carimbo do grupo apenas na linha da viagem. GPS fresco, inclusive zero, conserva prioridade (ADR 0005, adendo S48).

[ESTÁTICO] Oráculos quantitativos em `TripSpeedTest`: Meter1 a 18 km/h e Meter2 a cada segundo por 60 s → 0,010 km / 2.000 ms / média 18 km/h; retorno do Meter1 não integra a lacuna. Trocas patinete→GPS→patinete→GPS → 0,0375 km / 6.000 ms / média 22,5 km/h. Pausa/retomada com troca de fonte → 0,0225 km / 4.000 ms / média 20,25 km/h. Cálculos à mão por distância = velocidade × tempo; reancoragem conforme ADR 0013. `HomeViewModelTest` verifica integração e persistência com valores exatos, incluindo Meter1 vencido e GPS zero com patinete a 18 km/h; `GpsSpeedTest` cobre prioridade, limite inclusivo e Meter1 ausente.

[DESCONHECIDO] Testes/compilação/JaCoCo e `clean check` não executados nesta rodada: a nota explícita do coordenador proíbe Gradle no sandbox e reserva a execução ao coordenador. Testes da guarda foram escritos antes da implementação, sem observação de vermelho/verde. EXIT de `clean check`: não disponível. Nenhum acesso a patinete/adb, commit, merge ou push.
