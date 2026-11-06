package br.dev.evaldo.meuscooter.ui

import br.dev.evaldo.meuscooter.ui.home.MainButtonTone
import br.dev.evaldo.meuscooter.ui.home.mainButtonTone
import org.junit.Assert.assertEquals
import org.junit.Test

/** S29: só o botão "Procurar patinete" (desconectado) vira alerta vermelho; os demais estados não mudam. */
class MainButtonToneTest {
    private fun b(kind: MainButtonKind, enabled: Boolean = true) = MainButtonUi(kind, "x", enabled)

    @Test fun disconnectedSearchIsAlert() = assertEquals(MainButtonTone.Alert, mainButtonTone(b(MainButtonKind.Search)))
    @Test fun connectingStaysNeutral() = assertEquals(MainButtonTone.Disabled, mainButtonTone(b(MainButtonKind.Connecting, enabled = false)))
    @Test fun disabledUnlockStaysGrey() = assertEquals(MainButtonTone.Disabled, mainButtonTone(b(MainButtonKind.Unlock, enabled = false)))
    @Test fun movingStaysGrey() = assertEquals(MainButtonTone.Disabled, mainButtonTone(b(MainButtonKind.Moving, enabled = false)))
    @Test fun enabledUnlockIsPrimary() = assertEquals(MainButtonTone.Primary, mainButtonTone(b(MainButtonKind.Unlock)))
    @Test fun lockIsOutlined() = assertEquals(MainButtonTone.Outlined, mainButtonTone(b(MainButtonKind.Lock)))
    @Test fun retryIsAlert() = assertEquals(MainButtonTone.Alert, mainButtonTone(b(MainButtonKind.Retry)))
    @Test fun pendingAndConfirmedKeepTheirColors() {
        assertEquals(MainButtonTone.Pending, mainButtonTone(b(MainButtonKind.Pending, enabled = false)))
        assertEquals(MainButtonTone.Success, mainButtonTone(b(MainButtonKind.Confirmed, enabled = false)))
    }
}
