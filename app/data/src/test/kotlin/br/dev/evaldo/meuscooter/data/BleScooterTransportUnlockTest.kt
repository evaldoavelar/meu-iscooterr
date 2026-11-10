package br.dev.evaldo.meuscooter.data

import br.dev.evaldo.meuscooter.protocol.AgedTelemetry
import br.dev.evaldo.meuscooter.protocol.Clock
import br.dev.evaldo.meuscooter.protocol.ConnectionStatus
import br.dev.evaldo.meuscooter.protocol.Pin
import br.dev.evaldo.meuscooter.protocol.UnconfirmedCause
import br.dev.evaldo.meuscooter.protocol.UnlockRefusal
import br.dev.evaldo.meuscooter.protocol.UnlockResult
import java.lang.management.ManagementFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Desbloqueio com PIN no `BleScooterTransport` (etapa 4.4, plano Fase 4 §7.4) sobre `FakeGattClient` e tempo virtual.
 * NENHUM BLE real. PIN SINTÉTICO 1357. Quadro esperado calculado fora do código (ver `TxFrameTest`). Meter1/ACKs de
 * CONNECT/heartbeat são literais de `research/captures/s4-3/ix7tap.txt`; ACK/recusa do LOCK seguem S13
 * (`1aa1 32 05 00` / `1aa1 32 89 00`, CRC de resposta desconhecido e não exigido).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BleScooterTransportUnlockTest {
    private val connectTx = "1aa1300201016eb41ff1"
    private val heartbeatTx = "1aa110010071951ff1"
    private val unlockTx = "1aa13202050001030507523d1ff1"
    private val connectAck = hex("1aa130050101d5b31ff1")
    private val heartbeatAck = hex("1aa1100500be8c1ff1")
    private val lockAck = hex("1aa13205000000" + "1ff1")
    private val pinRejected = hex("1aa13289000000" + "1ff1")
    private val meter1Unlocked = hex("1aa1200312000000640000003c000000002323230203081f981ff1")
    private val meter1Locked = hex("1aa1200312003000640000003c000000002323230203082f8c1ff1")
    private val meter2 = hex("1aa12103120000ae1500000000170000000000160f03085d8b1ff1")

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun str(b: List<Byte>) = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private inner class Rig(
        val scope: TestScope,
        config: BleConfig = BleConfig(txEnabled = true),
        speed: ((AgedTelemetry) -> Float?)? = { 0f },
        receptionClock: Clock = Clock { scope.currentTime },
    ) {
        val gatt = FakeGattClient(receptionClock)
        var deliveryGate: CompletableDeferred<Unit>? = null
        var captureUnlockFrame = false
        var unlockFrame: ByteArray? = null
        private val deliveredGatt = object : GattClient by gatt {
            override val notifications = gatt.notifications.onEach { deliveryGate?.await() }
            override suspend fun write(service: java.util.UUID, characteristic: java.util.UUID, value: ByteArray) {
                if (captureUnlockFrame && value[2] == 0x32.toByte()) {
                    // Referência emprestada; o esperado é verificado ANTES de a implementação apagar o frame.
                    assertEquals(unlockTx, str(value.toList()))
                    unlockFrame = value
                }
                gatt.write(service, characteristic, value)
            }
        }
        val transport = if (speed == null) {
            BleScooterTransport(scope.backgroundScope, Clock { scope.currentTime }, deliveredGatt, ADDRESS, config)
        } else {
            BleScooterTransport(scope.backgroundScope, Clock { scope.currentTime }, deliveredGatt, ADDRESS, config, speedKmh = speed)
        }

        /** Meter1 atual do patinete simulado (emitido a cada 300 ms, como o real). */
        var meter1 = meter1Locked
        var emitMeter1 = true
        var ackHeartbeat = true

        /** Resposta ao quadro de desbloqueio e o que muda no Meter1 depois (`null` = nada). */
        var unlockReply: ByteArray? = lockAck
        var unlockFlipsAfterMs: Long? = 660

        init {
            scope.backgroundScope.launch {
                delay(10)
                while (true) {
                    if (emitMeter1) gatt.emit(meter1)
                    gatt.emit(meter2)
                    delay(300)
                }
            }
            gatt.reply = { bytes ->
                when (str(bytes.toList())) {
                    connectTx -> connectAck
                    heartbeatTx -> if (ackHeartbeat) heartbeatAck else null
                    unlockTx -> {
                        unlockFlipsAfterMs?.let { ms -> scope.backgroundScope.launch { delay(ms); meter1 = meter1Unlocked } }
                        unlockReply
                    }
                    else -> null
                }
            }
        }

        val written get() = gatt.writes.map { str(it.third) }
        val unlockWrites get() = written.count { it.startsWith("1aa132") }
    }

    private fun TestScope.unlockAsync(r: Rig, pin: String = "1357"): Deferred<UnlockResult> =
        async { r.transport.unlock(Pin.of(pin)) }

    /** Conecta (CONNECT com ACK em t=10) e deixa passar [settleMs] com heartbeats. */
    private suspend fun TestScope.connected(r: Rig, settleMs: Long = 1_490) {
        r.transport.connect()
        advanceTimeBy(settleMs)
        assertEquals(ConnectionStatus.Connected, r.transport.connection.value)
    }

    // ---------- Pré-condições: cada uma recusa SEM escrever nada ----------

    @Test fun txDisabledRefusesAndWritesNothing() = runTest {
        val r = Rig(this, BleConfig())
        connected(r)
        assertFalse(r.transport.pinUnlockAvailable)
        assertEquals(UnlockResult.Refused(UnlockRefusal.TxDisabled), r.transport.unlock(Pin.of("1357")))
        advanceTimeBy(5_000)
        assertTrue(r.gatt.writes.isEmpty())
    }

    @Test fun notConnectedRefuses() = runTest {
        val r = Rig(this)
        assertTrue(r.transport.pinUnlockAvailable)
        assertEquals(UnlockResult.Refused(UnlockRefusal.LinkNotReady), r.transport.unlock(Pin.of("1357")))
        assertTrue(r.gatt.writes.isEmpty())
    }

    @Test fun connectNotYetAcknowledgedRefuses() = runTest {
        val r = Rig(this)
        r.gatt.writeDelayMs = 500 // CONNECT em voo, sem ACK ainda
        r.transport.connect()
        advanceTimeBy(100)
        assertEquals(UnlockResult.Refused(UnlockRefusal.LinkNotReady), r.transport.unlock(Pin.of("1357")))
        assertEquals(listOf(connectTx), r.written)
    }

    @Test fun heartbeatWithoutRecentAckRefuses() = runTest {
        val r = Rig(this)
        connected(r, settleMs = 1_100) // último ACK de heartbeat em t=1010
        r.ackHeartbeat = false // 2010 e 3010 sem ACK (ainda < 3 faltas: enlace segue Conectado)
        advanceTimeBy(2_000) // t=3110: último ACK há 2100 ms
        assertEquals(ConnectionStatus.Connected, r.transport.connection.value)
        assertEquals(UnlockResult.Refused(UnlockRefusal.LinkNotReady), r.transport.unlock(Pin.of("1357")))
        assertEquals(0, r.unlockWrites)
    }

    @Test fun aHeartbeatAckBufferedPastLinkAliveDoesNotRenewItsReceptionTime() = runTest {
        val r = Rig(this, BleConfig(txEnabled = true, linkAliveMs = 300))
        connected(r, settleMs = 1_100)
        val gate = CompletableDeferred<Unit>()
        r.deliveryGate = gate
        advanceTimeBy(1_301) // ACK recebido em t=2010; consumo em t=2411, Meter1 em t=2410.
        gate.complete(Unit)
        runCurrent()
        assertEquals(ConnectionStatus.Connected, r.transport.connection.value)
        val writesBefore = r.gatt.writes.size
        assertEquals(UnlockResult.Refused(UnlockRefusal.LinkNotReady), r.transport.unlock(Pin.of("1357")))
        assertEquals(writesBefore, r.gatt.writes.size)
        assertEquals(0, r.unlockWrites)
    }

    @Test fun meter1OlderThanTwoSecondsRefuses() = runTest {
        val r = Rig(this)
        connected(r, settleMs = 300) // último Meter1 em t=310
        r.emitMeter1 = false // só Meter2 continua (renova `receivedAtMs`, não o Meter1)
        advanceTimeBy(2_000) // t=2310: Meter1 com 2000 ms
        assertEquals(UnlockResult.Refused(UnlockRefusal.TelemetryStale), r.transport.unlock(Pin.of("1357")))
        assertEquals(0, r.unlockWrites)
    }

    @Test fun neverSeenMeter1WithFreshMeter2RefusesBeforeWriting() = runTest {
        val r = Rig(this)
        r.emitMeter1 = false // só Meter2 desde o início: receivedAtMs fresco, Meter1 nunca visto
        connected(r)
        assertEquals(null, r.transport.aged.value?.meter1AtMs)
        assertEquals(UnlockResult.Refused(UnlockRefusal.TelemetryStale), r.transport.unlock(Pin.of("1357")))
        assertEquals(0, r.unlockWrites)
    }

    @Test fun aLockedMeter1BufferedForOverTwoSecondsRefusesWithoutWriting() = runTest {
        val r = Rig(this)
        connected(r)
        r.emitMeter1 = false
        val gate = CompletableDeferred<Unit>()
        r.deliveryGate = gate
        r.gatt.emit(meter1Locked)
        runCurrent()
        advanceTimeBy(2_101)
        gate.complete(Unit)
        runCurrent() // ACK de heartbeat em voo também liberado; enlace continua vivo.
        assertEquals(ConnectionStatus.Connected, r.transport.connection.value)
        val writesBefore = r.gatt.writes.size
        assertEquals(UnlockResult.Refused(UnlockRefusal.TelemetryStale), r.transport.unlock(Pin.of("1357")))
        assertEquals(writesBefore, r.gatt.writes.size)
        assertEquals(0, r.unlockWrites)
    }

    @Test fun unknownSpeedRefuses() = runTest {
        val r = Rig(this, speed = { null }) // seam preservado: desconhecido continua recusando.
        connected(r)
        assertEquals(UnlockResult.Refused(UnlockRefusal.SpeedUnknown), r.transport.unlock(Pin.of("1357")))
        assertEquals(0, r.unlockWrites)
    }

    @Test fun s19StoppedFramePassesSpeedPreconditionWithTheDefaultDecoder() = runTest {
        val r = Rig(this, speed = null) // sem injeção de velocidade
        r.meter1 = hex("1aa120031200000064000000320000000023232302030853f81ff1") // S19:6
        connected(r)
        // O quadro real é desbloqueado: chegar a NotLocked prova que passou pelo teste de velocidade.
        assertEquals(UnlockResult.Refused(UnlockRefusal.NotLocked), r.transport.unlock(Pin.of("1357")))
        assertEquals(0, r.unlockWrites)
        r.meter1 = meter1Locked // fixture bloqueada S4-3, também parada: agora pode escrever e confirmar.
        advanceTimeBy(300)
        val result = unlockAsync(r)
        advanceTimeBy(1_000)
        assertEquals(UnlockResult.Unlocked, result.await())
        assertEquals(1, r.unlockWrites)
    }

    @Test fun aMovingMeter1PendingDeliveryRefusesWithoutWritingThenRefusesAsMoving() = runTest {
        val r = Rig(this)
        connected(r)
        val gate = CompletableDeferred<Unit>()
        r.deliveryGate = gate
        // S19:1063, movimento de uma unidade; ainda não consumido pelo transporte.
        r.meter1 = hex("1aa1200312000000640100003200000000232323020308ae3b1ff1")
        r.gatt.emit(r.meter1)
        runCurrent()
        assertEquals(false, r.transport.aged.value?.telemetry?.wheelMoving)
        val pending = async(start = CoroutineStart.UNDISPATCHED) { r.transport.unlock(Pin.of("1357")) }
        runCurrent()
        assertFalse("S35: espera UM reenvio antes de recusar", pending.isCompleted)
        advanceTimeBy(150)
        runCurrent()
        assertTrue("deve recusar sem aguardar confirmação", pending.isCompleted)
        assertEquals(UnlockResult.Refused(UnlockRefusal.PendingReception), pending.await())
        assertEquals(0, r.unlockWrites)
        gate.complete(Unit)
        runCurrent()
        assertEquals(true, r.transport.aged.value?.telemetry?.wheelMoving)
        assertEquals(UnlockResult.Refused(UnlockRefusal.Moving), r.transport.unlock(Pin.of("1357")))
        assertEquals(0, r.unlockWrites)
    }

    @Test fun aDroppedMovingMeter1RefusesUntilANewStoppedMeter1Arrives() = runTest {
        // Pedido r4 / ADR 0004: uma recepção posterior não pode ocultar a perda de movimento (S19:1063).
        for (nextReception in listOf(meter2, heartbeatAck, byteArrayOf(0))) {
            val r = Rig(this)
            connected(r)
            r.emitMeter1 = false
            assertTrue(r.transport.aged.value?.meter1AtMs != null)
            assertEquals(false, r.transport.aged.value?.telemetry?.wheelMoving)
            val writesBefore = r.gatt.writes.size
            r.gatt.emit(hex("1aa1200312000000640100003200000000232323020308ae3b1ff1"), dropAfterReception = true)
            r.gatt.emit(nextReception)
            runCurrent() // fronteira RX drenada, mas o Meter1 de movimento nunca chegou ao acumulador.

            val refused = async(start = CoroutineStart.UNDISPATCHED) { r.transport.unlock(Pin.of("1357")) }
            runCurrent()
            assertEquals("nenhuma escrita após a perda", writesBefore, r.gatt.writes.size)
            assertTrue("recusa imediata, sem esperar confirmação", refused.isCompleted)
            assertEquals(UnlockResult.Refused(UnlockRefusal.TelemetryStale), refused.await())
            assertEquals(0, r.unlockWrites)
            assertEquals(null, r.transport.aged.value?.meter1AtMs)
            r.gatt.emit(meter2)
            runCurrent()
            assertEquals(UnlockResult.Refused(UnlockRefusal.TelemetryStale), r.transport.unlock(Pin.of("1357")))
            assertEquals(writesBefore, r.gatt.writes.size)

            r.emitMeter1 = true
            r.gatt.emit(meter1Locked)
            runCurrent()
            val accepted = unlockAsync(r)
            advanceTimeBy(1_000)
            assertEquals(UnlockResult.Unlocked, accepted.await())
            assertEquals(1, r.unlockWrites)
            r.transport.disconnect()
        }
    }

    @Test fun movingRefuses() = runTest {
        val r = Rig(this, speed = { 3f })
        connected(r)
        assertEquals(UnlockResult.Refused(UnlockRefusal.Moving), r.transport.unlock(Pin.of("1357")))
        assertEquals(0, r.unlockWrites)
    }

    @Test fun s19OneUnitOfWheelMotionRefusesBeforeWritingEvenWithInjectedZeroSpeed() = runTest {
        // S19:1063: último movimento à mão, byte[9]=0x01; nunca arredondar para "parado".
        for (speed in listOf<((AgedTelemetry) -> Float?)?>(null, { 0f })) {
            val r = Rig(this, speed = speed)
            connected(r) // começa parado; exercita também a acumulação parado -> movimento.
            r.meter1 = hex("1aa1200312000000640100003200000000232323020308ae3b1ff1")
            advanceTimeBy(300)
            val writesBefore = r.gatt.writes.size
            assertEquals(UnlockResult.Refused(UnlockRefusal.Moving), r.transport.unlock(Pin.of("1357")))
            assertEquals(writesBefore, r.gatt.writes.size)
            assertEquals(0, r.unlockWrites)
        }
    }

    @Test fun alreadyUnlockedRefuses() = runTest {
        val r = Rig(this)
        r.meter1 = meter1Unlocked
        connected(r)
        assertEquals(UnlockResult.Refused(UnlockRefusal.NotLocked), r.transport.unlock(Pin.of("1357")))
        assertEquals(0, r.unlockWrites)
    }

    // ---------- Envio e confirmação ----------

    @Test fun unlockedOnlyWhenMeter1ShowsTheLockBitCleared() = runTest {
        val r = Rig(this)
        connected(r) // t=1500
        var doneAt = -1L
        val res = async { r.transport.unlock(Pin.of("1357")).also { doneAt = currentTime } }
        runCurrent()
        assertEquals(1, r.unlockWrites)
        assertEquals(unlockTx, r.written.single { it.startsWith("1aa132") })
        // ACK chegou na hora, mas ainda não houve Meter1 desbloqueado: nada concluído (regra 5).
        advanceTimeBy(800)
        assertFalse(res.isCompleted)
        advanceTimeBy(200) // flip em 2160; próximo Meter1 em 2410
        assertTrue(res.isCompleted)
        assertEquals(UnlockResult.Unlocked, res.await())
        assertEquals(2_410L, doneAt) // o 1º Meter1 após o flip (2160) chega em 2410
        assertEquals(1, r.unlockWrites)
        assertEquals(1, r.gatt.maxWritesInFlight)
    }

    @Test fun ackAloneIsNotEnoughAndThereIsNoConfirmationAfterThreeSeconds() = runTest {
        val r = Rig(this)
        r.unlockFlipsAfterMs = null
        connected(r)
        val res = unlockAsync(r)
        advanceTimeBy(2_999)
        assertFalse(res.isCompleted)
        advanceTimeBy(2)
        assertEquals(UnlockResult.Unconfirmed(UnconfirmedCause.Timeout), res.await())
        advanceTimeBy(10_000)
        assertEquals("sem retry automático", 1, r.unlockWrites)
        assertEquals(ConnectionStatus.Connected, r.transport.connection.value)
    }

    @Test fun pinRejectionStopsAndIsNeverResent() = runTest {
        val r = Rig(this)
        r.unlockReply = pinRejected
        r.unlockFlipsAfterMs = null
        connected(r)
        val res = unlockAsync(r)
        runCurrent()
        assertEquals(UnlockResult.PinRejected, res.await())
        advanceTimeBy(10_000)
        assertEquals(1, r.unlockWrites)
        assertTrue(r.written.drop(1).all { it == heartbeatTx || it == unlockTx })
    }

    @Test fun aLateConfirmationAfterTheDeadlineDoesNotCount() = runTest {
        val r = Rig(this)
        r.unlockFlipsAfterMs = 3_100
        connected(r)
        val res = unlockAsync(r)
        advanceTimeBy(3_001)
        assertEquals(UnlockResult.Unconfirmed(UnconfirmedCause.Timeout), res.await())
        advanceTimeBy(1_000) // o Meter1 desbloqueado chega depois: a tentativa já terminou sem confirmação
        assertEquals(false, r.transport.aged.value?.telemetry?.locked)
        assertEquals(1, r.unlockWrites)
    }

    @Test fun confirmationWindowIncludes3000MsButExcludes3001Ms() = runTest {
        // Contrato do plano Fase 4 §7.4: confirmação em ≤ 3 s; fronteira inclusiva exigida na r3 P3.
        for (receivedAfterMs in listOf(2_999L, 3_000L, 3_001L)) {
            val r = Rig(this)
            r.unlockReply = null
            r.unlockFlipsAfterMs = null
            connected(r)
            r.emitMeter1 = false
            val res = unlockAsync(r)
            runCurrent() // escrita concluída; o Meter1 abaixo é estritamente posterior ao envio.
            advanceTimeBy(receivedAfterMs)
            r.gatt.emit(meter1Unlocked)
            runCurrent()
            val expected = if (receivedAfterMs <= 3_000) UnlockResult.Unlocked
                else UnlockResult.Unconfirmed(UnconfirmedCause.Timeout)
            assertTrue("tentativa encerrada após Meter1 em $receivedAfterMs ms", res.isCompleted)
            assertEquals("Meter1 recebido em $receivedAfterMs ms", expected, res.await())
            r.transport.disconnect()
        }
    }

    @Test fun aLatePinRejectionAfterTheDeadlineIsIgnored() = runTest {
        val r = Rig(this)
        r.unlockReply = null
        r.unlockFlipsAfterMs = null
        connected(r)
        val res = unlockAsync(r)
        advanceTimeBy(3_001)
        assertEquals(UnlockResult.Unconfirmed(UnconfirmedCause.Timeout), res.await())
        r.gatt.emit(pinRejected)
        runCurrent()
        assertEquals(ConnectionStatus.Connected, r.transport.connection.value)
        // Próxima tentativa (nova ação do usuário) não herda a recusa tardia.
        r.unlockReply = lockAck
        r.unlockFlipsAfterMs = 100
        val second = unlockAsync(r)
        advanceTimeBy(3_001)
        assertEquals(UnlockResult.Unlocked, second.await())
    }

    @Test fun anUnlockedMeter1BufferedBeforeTheWriteRefusesWithoutWriting() = runTest {
        val r = Rig(this)
        r.unlockFlipsAfterMs = null
        connected(r)
        assertEquals(true, r.transport.aged.value?.telemetry?.locked)
        // Chegou ao GATT, mas o coletor ainda não consumiu: não pode decidir pelo Meter1 bloqueado anterior.
        val gate = CompletableDeferred<Unit>()
        r.deliveryGate = gate
        r.meter1 = meter1Unlocked
        r.gatt.emit(meter1Unlocked)
        val res = async(start = CoroutineStart.UNDISPATCHED) { r.transport.unlock(Pin.of("1357")) }
        assertEquals(UnlockResult.Refused(UnlockRefusal.PendingReception), res.await())
        assertEquals(0, r.unlockWrites)
        gate.complete(Unit)
        runCurrent()
        assertEquals(UnlockResult.Refused(UnlockRefusal.NotLocked), r.transport.unlock(Pin.of("1357")))
        assertEquals(0, r.unlockWrites)
    }

    @Test fun aPinRejectionBufferedFromAttemptACannotRejectAttemptBButANewOneCan() = runTest {
        for (newRejection in listOf(false, true)) {
            val r = Rig(this)
            r.unlockReply = null
            r.unlockFlipsAfterMs = null
            connected(r)
            val first = unlockAsync(r)
            advanceTimeBy(3_001)
            assertEquals(UnlockResult.Unconfirmed(UnconfirmedCause.Timeout), first.await())
            val gate = CompletableDeferred<Unit>()
            r.deliveryGate = gate
            r.gatt.emit(pinRejected) // A já expirou; recusa chegou ANTES de B, ainda no buffer.
            runCurrent()
            assertEquals(UnlockResult.Refused(UnlockRefusal.PendingReception), r.transport.unlock(Pin.of("1357")))
            assertEquals(1, r.unlockWrites)
            gate.complete(Unit)
            runCurrent()
            // Nova ação do usuário após drenar o RX, ainda no mesmo ms, pode iniciar B.
            val second = async(start = CoroutineStart.UNDISPATCHED) { r.transport.unlock(Pin.of("1357")) }
            runCurrent()
            assertEquals(2, r.unlockWrites)
            assertFalse("B não pode herdar a recusa de A", second.isCompleted)
            if (newRejection) {
                r.gatt.emit(pinRejected) // posterior ao início de B, ainda no mesmo milissegundo.
                runCurrent()
                assertEquals(UnlockResult.PinRejected, second.await())
            } else {
                advanceTimeBy(3_001)
                assertEquals(UnlockResult.Unconfirmed(UnconfirmedCause.Timeout), second.await())
            }
            r.transport.disconnect()
        }
    }

    @Test fun aPinRejectionPausedBetweenClockAndSequenceCannotRejectAttemptB() = runTest {
        val clockRead = CountDownLatch(1)
        val resumeCallback = CountDownLatch(1)
        lateinit var callback: Thread
        val r = Rig(this, receptionClock = Clock {
            val now = currentTime
            if (Thread.currentThread() === callback) {
                clockRead.countDown()
                check(resumeCallback.await(5, TimeUnit.SECONDS))
            }
            now
        })
        // Inicializado antes de qualquer leitura do relógio; só esta thread pausa o callback.
        callback = Thread { r.gatt.emit(pinRejected) }
        r.unlockReply = null
        r.unlockFlipsAfterMs = null
        connected(r)
        val first = unlockAsync(r)
        advanceTimeBy(3_001)
        assertEquals(UnlockResult.Unconfirmed(UnconfirmedCause.Timeout), first.await())
        callback.start()
        assertTrue(clockRead.await(5, TimeUnit.SECONDS))
        val boundaryThread = arrayOfNulls<Thread>(1)
        val executor = Executors.newSingleThreadExecutor { task -> Thread(task).also { boundaryThread[0] = it } }
        try {
            val second = unlockAsync(r)
            // Só o scheduler executa o transporte, sem duas chamadas simultâneas a runCurrent.
            val boundary = executor.submit { runCurrent() }
            val threads = ManagementFactory.getThreadMXBean()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            // Código antigo termina a captura parcial; código corrigido bloqueia no lock do callback.
            while (!boundary.isDone &&
                threads.getThreadInfo(checkNotNull(boundaryThread[0]).id)?.lockOwnerId != callback.id &&
                System.nanoTime() < deadline
            ) Thread.yield()
            assertTrue("B alcançou a fronteira antes de liberar o callback antigo",
                boundary.isDone || threads.getThreadInfo(checkNotNull(boundaryThread[0]).id)?.lockOwnerId == callback.id)
            resumeCallback.countDown()
            callback.join(5_000)
            assertFalse(callback.isAlive)
            boundary.get(5, TimeUnit.SECONDS)
            runCurrent()
            // S35: a 1a tentativa de B foi recusada (recepção pendente, sem escrita); o reenvio único vem após 150 ms.
            assertFalse(second.isCompleted)
            assertEquals(1, r.unlockWrites)
            advanceTimeBy(150)
            runCurrent()
            // Recepção consumida: o reenvio escreve, e o erro antigo não é herdado.
            assertEquals(2, r.unlockWrites)
            assertFalse("0x89 de A não pode rejeitar a nova tentativa", second.isCompleted)
            r.gatt.emit(pinRejected)
            runCurrent()
            assertEquals(UnlockResult.PinRejected, second.await())
        } finally {
            resumeCallback.countDown()
            callback.join(5_000)
            executor.shutdownNow()
        }
    }

    @Test fun aPreviousMeter1StillLockedDoesNotConfirm() = runTest {
        val r = Rig(this)
        r.unlockFlipsAfterMs = null
        connected(r)
        val res = unlockAsync(r)
        advanceTimeBy(1_000) // vários Meter1 bloqueados chegam
        assertFalse(res.isCompleted)
    }

    // ---------- Corridas ----------

    @Test fun aSecondCallWhileOneIsInFlightIsRefused() = runTest {
        val r = Rig(this)
        connected(r)
        val first = unlockAsync(r)
        runCurrent()
        assertEquals(UnlockResult.Refused(UnlockRefusal.Busy), r.transport.unlock(Pin.of("1357")))
        advanceTimeBy(3_001)
        assertEquals(UnlockResult.Unlocked, first.await())
        assertEquals(1, r.unlockWrites)
    }

    @Test fun aDoubleTapBeforeTheFirstWriteStartsIsRefused() = runTest {
        val r = Rig(this)
        connected(r)
        val a = unlockAsync(r)
        val b = unlockAsync(r)
        advanceTimeBy(3_001)
        val results = listOf(a.await(), b.await())
        assertTrue(UnlockResult.Refused(UnlockRefusal.Busy) in results)
        assertTrue(UnlockResult.Unlocked in results)
        assertEquals(1, r.unlockWrites)
    }

    @Test fun disconnectWhileWaitingEndsWithoutConfirmation() = runTest {
        val r = Rig(this)
        r.unlockFlipsAfterMs = null
        connected(r)
        val res = unlockAsync(r)
        advanceTimeBy(500)
        r.transport.disconnect()
        runCurrent()
        assertTrue(res.isCompleted)
        assertEquals(UnlockResult.Unconfirmed(UnconfirmedCause.LinkLost), res.await())
        assertEquals(1, r.unlockWrites)
    }

    @Test fun linkDropWhileWaitingEndsWithoutConfirmation() = runTest {
        val r = Rig(this)
        r.unlockFlipsAfterMs = null
        connected(r)
        val res = unlockAsync(r)
        advanceTimeBy(500)
        r.gatt.dropLink()
        runCurrent()
        assertEquals(UnlockResult.Unconfirmed(UnconfirmedCause.LinkLost), res.await())
        assertEquals(ConnectFailure.LinkLost, r.transport.failure.value)
    }

    @Test fun linkDropDuringTheWriteEndsWithoutConfirmation() = runTest {
        val r = Rig(this)
        connected(r)
        r.gatt.writeDelayMs = 500
        val res = unlockAsync(r)
        advanceTimeBy(100)
        r.gatt.dropLink()
        runCurrent()
        assertEquals(UnlockResult.Unconfirmed(UnconfirmedCause.LinkLost), res.await())
        assertEquals(ConnectionStatus.Disconnected, r.transport.connection.value)
    }

    @Test fun callerCancellationDuringWriteOrConfirmationClosesBeforeRethrowingAndRefusesAnotherUnlock() = runTest {
        for (duringWrite in listOf(true, false)) {
            val r = Rig(this)
            r.unlockFlipsAfterMs = null
            r.unlockReply = null
            connected(r)
            if (duringWrite) r.gatt.writeDelayMs = 60_000
            var closedWhenCancelled = false
            val res = async {
                try {
                    r.transport.unlock(Pin.of("1357"))
                } catch (e: CancellationException) {
                    closedWhenCancelled = r.gatt.closeCalls == 1
                    throw e
                }
            }
            runCurrent()
            assertEquals(1, r.unlockWrites)
            assertFalse(res.isCompleted)
            res.cancelAndJoin()
            assertTrue(res.isCancelled)
            assertTrue("GATT fechado antes de relançar (duringWrite=$duringWrite)", closedWhenCancelled)
            assertEquals(ConnectFailure.WriteTimedOut, r.transport.failure.value)
            assertEquals(ConnectionStatus.Disconnected, r.transport.connection.value)
            assertEquals(0, r.gatt.writesInFlight)
            assertEquals(UnlockResult.Refused(UnlockRefusal.LinkNotReady), r.transport.unlock(Pin.of("1357")))
            val count = r.gatt.writes.size
            advanceTimeBy(10_000)
            assertEquals(count, r.gatt.writes.size)
        }
    }

    @Test fun aHangingUnlockWriteIsIndeterminateClosesTheGattAndNeverWritesAgain() = runTest {
        val r = Rig(this)
        connected(r) // t=1500; próximo heartbeat (2010) espera a escrita do desbloqueio
        r.gatt.writeDelayMs = 60_000
        val res = unlockAsync(r)
        advanceTimeBy(1_999)
        assertFalse(res.isCompleted)
        advanceTimeBy(2)
        assertEquals(UnlockResult.Unconfirmed(UnconfirmedCause.WriteTimedOut), res.await())
        assertEquals(ConnectFailure.WriteTimedOut, r.transport.failure.value)
        assertEquals(ConnectionStatus.Disconnected, r.transport.connection.value)
        assertEquals(1, r.gatt.closeCalls)
        val count = r.gatt.writes.size
        advanceTimeBy(10_000)
        assertEquals(unlockTx, r.written.last())
        assertEquals(count, r.gatt.writes.size)
    }

    @Test fun aFailedUnlockWriteEndsTheLinkWithoutConfirmation() = runTest {
        val r = Rig(this)
        connected(r)
        r.gatt.writeError = GattException(GattError.WriteFailed)
        assertEquals(UnlockResult.Unconfirmed(UnconfirmedCause.WriteFailed), r.transport.unlock(Pin.of("1357")))
        assertEquals(ConnectFailure.Gatt(GattError.WriteFailed), r.transport.failure.value)
        assertEquals(1, r.unlockWrites)
    }

    @Test fun theUnlockWaitsForAHeartbeatInFlightInsteadOfOverlappingWrites() = runTest {
        val r = Rig(this)
        r.gatt.writeDelayMs = 300
        connected(r, settleMs = 1_350) // heartbeat de ~1310 em voo até ~1610
        val res = unlockAsync(r)
        advanceTimeBy(3_500)
        assertEquals(UnlockResult.Unlocked, res.await())
        assertEquals(1, r.gatt.maxWritesInFlight)
    }

    @Test fun receptionWhileWaitingForTheWriteLockIsCheckedBeforeUnlockWrites() = runTest {
        val r = Rig(this, BleConfig(txEnabled = true, linkAliveMs = 5_000))
        r.gatt.writeDelayMs = 300
        connected(r, settleMs = 1_350) // heartbeat em voo de t=1310 a 1610, ainda com o mutex.
        assertEquals(1, r.gatt.writesInFlight)
        val res = async(start = CoroutineStart.UNDISPATCHED) { r.transport.unlock(Pin.of("1357")) }
        assertFalse(res.isCompleted)
        val gate = CompletableDeferred<Unit>()
        r.deliveryGate = gate
        r.meter1 = hex("1aa1200312000000640100003200000000232323020308ae3b1ff1")
        r.gatt.emit(r.meter1)
        advanceTimeBy(951) // ACK também retido; heartbeat larga o mutex ao expirar em t=2310.
        // S35: a 1a recusa é PendingReception; o reenvio único revalida tudo e, com o Meter1 retido e já velho, recusa por
        // TelemetryStale (await avança o tempo virtual). Em nenhum caso escreve.
        assertEquals(UnlockResult.Refused(UnlockRefusal.TelemetryStale), res.await())
        assertEquals(0, r.unlockWrites)
        gate.complete(Unit)
        runCurrent()
        assertEquals(UnlockResult.Refused(UnlockRefusal.Moving), r.transport.unlock(Pin.of("1357")))
        assertEquals(0, r.unlockWrites)
    }

    @Test fun theFrameGivenToGattIsWipedAfterSuccessFailureAndCancellation() = runTest {
        for (path in listOf("success", "failure", "cancellation")) {
            val r = Rig(this)
            connected(r)
            r.captureUnlockFrame = true
            if (path == "failure") r.gatt.writeError = GattException(GattError.WriteFailed)
            if (path == "cancellation") r.gatt.writeDelayMs = 60_000
            val res = unlockAsync(r)
            runCurrent()
            if (path == "cancellation") {
                res.cancelAndJoin()
            } else {
                advanceTimeBy(1_001)
                assertEquals(
                    if (path == "success") UnlockResult.Unlocked else UnlockResult.Unconfirmed(UnconfirmedCause.WriteFailed),
                    res.await(),
                )
            }
            assertTrue("frame apagado em $path", checkNotNull(r.unlockFrame).all { it == 0.toByte() })
            r.transport.disconnect()
        }
    }

    @Test fun thePinDigitsNeverAppearInResultsOrFailures() = runTest {
        val r = Rig(this)
        r.unlockReply = pinRejected
        connected(r)
        val res = unlockAsync(r)
        advanceTimeBy(3_001)
        val texts = listOf(res.await().toString(), r.transport.failure.value.toString(), r.gatt.calls.joinToString())
        assertTrue(texts.none { "1357" in it || "1,3,5,7" in it || "01030507" in it })
    }

    private companion object {
        const val ADDRESS = "AA:BB:CC:DD:EE:FF"
    }

    // ---------- S35: UM reenvio automático após PendingReception ----------

    @Test fun pendingReceptionClearingWithinTheRetryWindowSendsOnceAfter150ms() = runTest {
        val r = Rig(this)
        r.unlockFlipsAfterMs = null
        connected(r)
        val gate = CompletableDeferred<Unit>()
        r.deliveryGate = gate
        r.gatt.emit(meter1Locked) // parado e fresco, mas retido no buffer
        runCurrent()
        val t0 = currentTime
        val res = async(start = CoroutineStart.UNDISPATCHED) { r.transport.unlock(Pin.of("1357")) }
        runCurrent()
        assertFalse("espera o reenvio, sem bloquear", res.isCompleted)
        assertEquals(0, r.unlockWrites)
        gate.complete(Unit)
        runCurrent()
        assertEquals("ainda dentro dos 150 ms", 0, r.unlockWrites)
        advanceTimeBy(150)
        runCurrent()
        assertEquals(t0 + 150, currentTime)
        assertEquals("exatamente UM envio", 1, r.unlockWrites)
        res.cancelAndJoin()
    }

    @Test fun pendingReceptionPersistingIsRefusedAfterExactlyOneRetryAndNeverLoops() = runTest {
        val r = Rig(this)
        r.unlockFlipsAfterMs = null
        connected(r)
        val gate = CompletableDeferred<Unit>()
        r.deliveryGate = gate
        r.gatt.emit(meter1Locked)
        runCurrent()
        val t0 = currentTime
        val res = async(start = CoroutineStart.UNDISPATCHED) { r.transport.unlock(Pin.of("1357")) }
        advanceTimeBy(149)
        assertFalse(res.isCompleted)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(UnlockResult.Refused(UnlockRefusal.PendingReception), res.await())
        assertEquals(t0 + 150, currentTime)
        advanceTimeBy(5_000)
        assertEquals(0, r.unlockWrites)
        gate.complete(Unit)
        runCurrent()
    }

    @Test fun movementArrivingDuringTheRetryWindowRevalidatesAndRefusesAsMoving() = runTest {
        val r = Rig(this)
        r.unlockFlipsAfterMs = null
        connected(r)
        val gate = CompletableDeferred<Unit>()
        r.deliveryGate = gate
        r.meter1 = hex("1aa1200312000000640100003200000000232323020308ae3b1ff1")
        r.gatt.emit(r.meter1) // roda em movimento retida no buffer
        runCurrent()
        val res = async(start = CoroutineStart.UNDISPATCHED) { r.transport.unlock(Pin.of("1357")) }
        runCurrent()
        assertFalse(res.isCompleted)
        gate.complete(Unit)
        runCurrent()
        advanceTimeBy(150)
        runCurrent()
        assertEquals(UnlockResult.Refused(UnlockRefusal.Moving), res.await())
        assertEquals(0, r.unlockWrites)
    }
}
