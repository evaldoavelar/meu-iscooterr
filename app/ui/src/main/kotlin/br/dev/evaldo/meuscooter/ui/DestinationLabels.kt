package br.dev.evaldo.meuscooter.ui

import br.dev.evaldo.meuscooter.domain.AppDestination

/** Rótulo exibido no menu ⋮ e no título das telas (design/ANDROID-MAPA.md). */
val AppDestination.label: String
    get() = when (this) {
        AppDestination.Home -> "Início"
        AppDestination.Telemetry -> "Telemetria"
        AppDestination.Settings -> "Preferências"
        AppDestination.History -> "Histórico de viagens"
        AppDestination.TripDetail -> "Viagem"
    }
