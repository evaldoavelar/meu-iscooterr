package br.dev.evaldo.meuscooter.data

import br.dev.evaldo.meuscooter.protocol.Clock

/** Ordem única de recepções e fronteiras, compartilhada pelo Android e pelo Fake; não reinicia ao reconectar. */
class GattReception(private val clock: Clock) {
    private val lock = Any()
    private var sequence = 0L

    fun notification(value: ByteArray): GattNotification = synchronized(lock) {
        val atMs = clock.nowMs()
        GattNotification(value, ++sequence, atMs)
    }

    fun markBoundary(): GattBoundary = synchronized(lock) {
        GattBoundary(sequence, clock.nowMs())
    }
}
