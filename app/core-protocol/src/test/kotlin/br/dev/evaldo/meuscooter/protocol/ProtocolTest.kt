package br.dev.evaldo.meuscooter.protocol

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ProtocolTest {
    /** Cada comando pertence a exatamente um controle da Home (tabela de `Control`, escrita à mão). */
    @Test fun everyCommandMapsToItsControl() {
        assertEquals(Control.Lock, ScooterCommand.SetLocked(true).control)
        assertEquals(Control.Headlight, ScooterCommand.SetHeadlight(true).control)
        assertEquals(Control.Motors, ScooterCommand.SetDualMotor(true).control)
        assertEquals(Control.Mode, ScooterCommand.SetSpeedMode(1).control)
        assertEquals(Control.Cruise, ScooterCommand.SetCruise(true).control)
        assertEquals(Control.ZeroStart, ScooterCommand.SetZeroStart(true).control)
        assertEquals(Control.Limit, ScooterCommand.SetSpeedLimit(25).control)
    }

    /** S34: o padrão da interface NÃO oferece Air Lock; os resultados são valores comparáveis e legíveis. */
    @Test fun airLockDefaultIsNotSupportedAndResultsAreValues() = runBlocking {
        val bare = object : ScooterTransport {
            override val connection = MutableStateFlow(ConnectionStatus.Disconnected)
            override val telemetry = emptyFlow<Telemetry>()
            override val speedModes = SpeedModeCatalog { emptyList() }
            override suspend fun connect() = Unit
            override suspend fun disconnect() = Unit
            override suspend fun send(command: ScooterCommand): Ack = Ack.Accepted
        }
        assertFalse(bare.airLockAvailable)
        assertEquals(AirLockResult.Refused(CommandRefusal.NotSupported), bare.setAirLock(true))
        assertEquals(AirLockResult.Unconfirmed(UnconfirmedCause.Timeout), AirLockResult.Unconfirmed(UnconfirmedCause.Timeout))
        assertEquals("AirLock(on=true)", TxCommand.AirLock(true).toString())
        assertEquals("AirLock(on=false)", TxCommand.AirLock(false).toString())
    }
}
