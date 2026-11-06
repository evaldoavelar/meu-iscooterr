package br.dev.evaldo.meuscooter.domain

import br.dev.evaldo.meuscooter.protocol.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class EventLogTest {
    private class MapStorage(val map: MutableMap<String, String> = mutableMapOf()) : KeyValueStorage {
        override fun get(key: String): String? = map[key]
        override fun putAll(values: Map<String, String>) { map += values }
    }

    private var now = 1_000L
    private val clock = Clock { now }

    @Test fun `append carimba o instante, numera em ordem e preserva a ordem cronologica`() {
        val log = FakeEventLogStore(wallClock = clock)
        log.append(AppEventType.Connected, "a"); now = 2_000; log.append(AppEventType.Lock, "b")
        assertEquals(listOf(1_000L to "a", 2_000L to "b"), log.events.value.map { it.atMs to it.detail })
        assertEquals(listOf(1L, 2L), log.events.value.map { it.id })
        assertEquals("Bloqueado", log.events.value[1].title)
    }

    @Test fun `limite de 500 descarta os mais antigos`() {
        val log = FakeEventLogStore(wallClock = clock)
        repeat(503) { log.append(AppEventType.Error, "e$it") }
        assertEquals(500, log.events.value.size)
        assertEquals("e3", log.events.value.first().detail)
        assertEquals("e502", log.events.value.last().detail)
    }

    @Test fun `clear esvazia`() {
        val log = FakeEventLogStore(wallClock = clock)
        log.append(AppEventType.Error, "x"); log.clear()
        assertTrue(log.events.value.isEmpty())
    }

    @Test fun `filtros por tipo`() {
        fun f(filter: EventFilter) = AppEventType.entries.filter(filter::matches).toSet()
        assertEquals(AppEventType.entries.toSet(), f(EventFilter.All))
        assertEquals(setOf(AppEventType.Lock, AppEventType.Unlock, AppEventType.Refused, AppEventType.WrongPin), f(EventFilter.Lock))
        assertEquals(setOf(AppEventType.Connected, AppEventType.Disconnected, AppEventType.LinkDropped, AppEventType.Reconnecting), f(EventFilter.Connection))
        assertEquals(setOf(AppEventType.WrongPin, AppEventType.Error), f(EventFilter.Errors))
    }

    @Test fun `severidade por tipo segue as cores aprovadas`() {
        assertEquals(EventSeverity.Success, AppEventType.Unlock.severity)
        assertEquals(EventSeverity.Success, AppEventType.Lock.severity)
        assertEquals(EventSeverity.Info, AppEventType.Connected.severity)
        assertEquals(EventSeverity.Neutral, AppEventType.Disconnected.severity)
        assertEquals(EventSeverity.Warning, AppEventType.Refused.severity)
        assertEquals(EventSeverity.Error, AppEventType.WrongPin.severity)
        assertEquals(EventSeverity.Error, AppEventType.Error.severity)
    }

    @Test fun `resumo do dia conta so o dia local de agora`() {
        val zone = ZoneOffset.ofHours(-3)
        // 2026-11-06 12:00 local = 15:00Z; ontem 23:59 local = 02:59Z de 06/11.
        val noon = java.time.ZonedDateTime.of(2026, 11, 6, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        val yesterday = java.time.ZonedDateTime.of(2026, 11, 5, 23, 59, 0, 0, zone).toInstant().toEpochMilli()
        fun e(id: Long, at: Long, t: AppEventType) = AppEvent(id, at, t, t.title, "")
        val events = listOf(
            e(1, yesterday, AppEventType.Unlock), e(2, noon - 3000, AppEventType.Unlock), e(3, noon - 2000, AppEventType.Unlock),
            e(4, noon - 1500, AppEventType.Lock), e(5, noon - 1000, AppEventType.LinkDropped), e(6, noon - 500, AppEventType.Connected),
        )
        assertEquals(DaySummary(2, 1, 1, noon - 500), DaySummary.of(events, zone, noon))
        assertEquals(DaySummary(0, 0, 0, null), DaySummary.of(emptyList(), zone, noon))
    }

    @Test fun `persistencia faz ida e volta e continua a numeracao`() = runTest {
        val storage = MapStorage()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val a = KeyValueEventLogStore(storage, clock, scope)
        a.append(AppEventType.Unlock, "PIN digitado · confirmado em 0,8 s"); now = 5_000; a.append(AppEventType.Refused, "x", "Desbloqueio recusado")
        scope.advanceUntilIdle()
        val b = KeyValueEventLogStore(storage, clock, scope)
        assertEquals(a.events.value, b.events.value)
        b.append(AppEventType.Error, "y")
        assertEquals(3L, b.events.value.last().id)
        assertEquals("Desbloqueio recusado", b.events.value[1].title)
    }

    @Test fun `escrita fica fora da chamada e so ocorre no escritor`() = runTest {
        val storage = MapStorage()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val log = KeyValueEventLogStore(storage, clock, scope)
        log.append(AppEventType.Error, "x")
        assertNull(storage.map[KeyValueEventLogStore.KEY])   // append não gravou na thread do chamador
        scope.advanceUntilIdle()
        assertTrue(storage.map[KeyValueEventLogStore.KEY]!!.contains("x"))
    }

    @Test fun `clear persiste vazio`() = runTest {
        val storage = MapStorage()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val log = KeyValueEventLogStore(storage, clock, scope)
        log.append(AppEventType.Error, "x"); scope.advanceUntilIdle(); log.clear(); scope.advanceUntilIdle()
        assertTrue(KeyValueEventLogStore(storage, clock, scope).events.value.isEmpty())
    }

    @Test fun `leitura ignora linhas ruins e nunca lanca`() = runTest {
        val raw = listOf(
            "lixo", "1\t100\tUnlock\tDesbloqueado\tok", "x\t200\tLock\tT\tD", "3\ty\tLock\tT\tD", "4\t300\tTipoNovo\tT\tD",
            "5\t400\tLock\tBloqueado", "", "6\t500\tError\tErro\tdetalhe\tcom tab", "\u0000\u0001ÿ",
        ).joinToString("\n")
        val log = KeyValueEventLogStore(MapStorage(mutableMapOf(KeyValueEventLogStore.KEY to raw)), clock, TestScope(StandardTestDispatcher(testScheduler)))
        assertEquals(listOf(1L, 6L), log.events.value.map { it.id })
        assertEquals("detalhe\tcom tab", log.events.value[1].detail)
    }

    @Test fun `leitura ignora ids duplicados e storage que lanca no get`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val dup = "1\t1\tError\tErro\ta\n1\t2\tError\tErro\tb\n2\t3\tError\tErro\tc"
        val a = KeyValueEventLogStore(MapStorage(mutableMapOf(KeyValueEventLogStore.KEY to dup)), clock, scope)
        assertEquals(listOf(1L, 2L), a.events.value.map { it.id })
        val broken = object : KeyValueStorage {
            override fun get(key: String): String? = throw IllegalStateException("corrompido")
            override fun putAll(values: Map<String, String>) = Unit
        }
        assertTrue(KeyValueEventLogStore(broken, clock, scope).events.value.isEmpty())
    }

    @Test fun `leitura tambem respeita o limite`() = runTest {
        val raw = (1..520).joinToString("\n") { "$it\t$it\tError\tErro\td" }
        val log = KeyValueEventLogStore(MapStorage(mutableMapOf(KeyValueEventLogStore.KEY to raw)), clock, TestScope(StandardTestDispatcher(testScheduler)))
        assertEquals(500, log.events.value.size)
        assertEquals(21L, log.events.value.first().id)
    }

    @Test fun `texto com quebra de linha ou tab nao corrompe o formato`() = runTest {
        val storage = MapStorage()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val log = KeyValueEventLogStore(storage, clock, scope)
        log.append(AppEventType.Error, "a\nb\tc\r")
        scope.advanceUntilIdle()
        assertEquals(1, KeyValueEventLogStore(storage, clock, scope).events.value.size)
    }

    @Test fun `falha do armazenamento nao derruba o app`() = runTest {
        val broken = object : KeyValueStorage {
            override fun get(key: String): String? = null
            override fun putAll(values: Map<String, String>) = throw IllegalStateException("disco cheio")
        }
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val log = KeyValueEventLogStore(broken, clock, scope)
        log.append(AppEventType.Error, "x"); scope.advanceUntilIdle(); log.append(AppEventType.Error, "y"); scope.advanceUntilIdle()
        assertEquals(2, log.events.value.size)
    }

    @Test fun `PIN nunca vai ao armazenamento mesmo depois de fluxos de desbloqueio com PIN`() = runTest {
        // Relógios escolhidos para que nenhuma sequência "1234"/"9876" apareça por acaso nos números persistidos.
        val storage = MapStorage()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val log = KeyValueEventLogStore(storage, clock, scope)
        var mono = 10_000L
        val rec = EventRecorder(log, { mono }, "iX7 Pro")
        // Os desbloqueios abaixo foram pedidos com PIN "1234" e "9876": o recorder nem recebe o PIN.
        rec.unlock(UnlockStatus.Waiting(10_000), viaSavedPin = false)
        rec.unlock(UnlockStatus.Finished(br.dev.evaldo.meuscooter.protocol.UnlockResult.Unlocked, 10_800), viaSavedPin = false)
        rec.unlock(UnlockStatus.Waiting(20_000), viaSavedPin = false)
        rec.unlock(UnlockStatus.Finished(br.dev.evaldo.meuscooter.protocol.UnlockResult.PinRejected, 20_500), viaSavedPin = false, rejects = 1)
        scope.advanceUntilIdle()
        val persisted = storage.map[KeyValueEventLogStore.KEY]!!
        assertEquals(2, persisted.lines().size)
        assertFalse(persisted.contains("1234")); assertFalse(persisted.contains("9876"))
        assertFalse(persisted.contains("1,2,3,4"))
    }
}
