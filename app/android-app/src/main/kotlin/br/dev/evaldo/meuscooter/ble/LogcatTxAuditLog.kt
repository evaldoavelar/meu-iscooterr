package br.dev.evaldo.meuscooter.ble

import android.util.Log
import br.dev.evaldo.meuscooter.FileDebugLog
import br.dev.evaldo.meuscooter.data.TxAuditEvent
import br.dev.evaldo.meuscooter.data.TxAuditLog

/** Grava o log de auditoria em `logcat` (tag `IX7TX`). Só existe em build depurável com TX ligado; release usa [TxAuditLog.None]. */
class LogcatTxAuditLog(private val fileLog: FileDebugLog? = null) : TxAuditLog {
    override fun record(event: TxAuditEvent) {
        val line = event.line()
        Log.i(TAG, line)
        fileLog?.append(TAG, line)
    }

    companion object {
        const val TAG = "IX7TX"

        /** Release (ou TX desligado) = nulo; o PIN e o CRC do Unlock já chegam mascarados em [TxAuditEvent]. */
        fun create(debuggable: Boolean, txEnabled: Boolean, fileLog: FileDebugLog? = null): TxAuditLog =
            if (debuggable && txEnabled) LogcatTxAuditLog(fileLog) else TxAuditLog.None
    }
}
