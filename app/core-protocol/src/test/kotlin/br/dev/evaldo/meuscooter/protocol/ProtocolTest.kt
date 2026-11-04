package br.dev.evaldo.meuscooter.protocol

import org.junit.Assert.assertEquals
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
}
