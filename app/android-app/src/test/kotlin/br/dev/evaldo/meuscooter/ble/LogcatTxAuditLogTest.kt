package br.dev.evaldo.meuscooter.ble

import br.dev.evaldo.meuscooter.data.TxAuditLog
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LogcatTxAuditLogTest {
    @Test fun releaseOrTxOffIsNull() {
        assertSame(TxAuditLog.None, LogcatTxAuditLog.create(debuggable = false, txEnabled = true))
        assertSame(TxAuditLog.None, LogcatTxAuditLog.create(debuggable = true, txEnabled = false))
        assertSame(TxAuditLog.None, LogcatTxAuditLog.create(debuggable = false, txEnabled = false))
    }

    @Test fun debuggableWithTxIsLogcat() {
        assertTrue(LogcatTxAuditLog.create(debuggable = true, txEnabled = true) is LogcatTxAuditLog)
    }
}
