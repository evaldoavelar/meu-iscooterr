package br.dev.evaldo.meuscooter.domain

import br.dev.evaldo.meuscooter.protocol.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/** Tipos de evento do registro (tela "Registro de eventos"). Telemetria NÃO é registrada. Novos tipos entram aqui. */
enum class AppEventType(val title: String, val severity: EventSeverity) {
    Unlock("Desbloqueado", EventSeverity.Success),
    Lock("Bloqueado", EventSeverity.Success),
    Connected("Conectado", EventSeverity.Info),
    Reconnecting("Reconectando", EventSeverity.Info),
    Disconnected("Desconectado", EventSeverity.Neutral),
    LinkDropped("Queda de conexão", EventSeverity.Neutral),
    Refused("Recusado", EventSeverity.Warning),
    WrongPin("PIN errado", EventSeverity.Error),
    Error("Erro", EventSeverity.Error),
}

/** Cor semântica: confirmado = verde, conexão = ciano, desconexão = cinza, recusa = âmbar, PIN errado/erro = vermelho. */
enum class EventSeverity { Success, Info, Neutral, Warning, Error }

/** Filtros da tela. PIN errado aparece em Bloqueio e em Erros. */
enum class EventFilter(val label: String, private val types: Set<AppEventType>?) {
    All("Todos", null),
    Lock("Bloqueio", setOf(AppEventType.Lock, AppEventType.Unlock, AppEventType.Refused, AppEventType.WrongPin)),
    Connection("Conexão", setOf(AppEventType.Connected, AppEventType.Disconnected, AppEventType.LinkDropped, AppEventType.Reconnecting)),
    Errors("Erros", setOf(AppEventType.WrongPin, AppEventType.Error));

    fun matches(type: AppEventType) = types == null || type in types
}

/** [atMs] = relógio de parede (epoch ms). [title]/[detail] são textos prontos e NUNCA contêm o PIN. */
data class AppEvent(val id: Long, val atMs: Long, val type: AppEventType, val title: String, val detail: String) {
    val severity: EventSeverity get() = type.severity
}

/** Registro de eventos do app. Ordem cronológica crescente em [events]; no máximo [EventLog.LIMIT] itens. */
interface EventLog {
    val events: StateFlow<List<AppEvent>>
    fun append(type: AppEventType, detail: String, title: String = type.title)
    fun clear()

    companion object {
        const val LIMIT = 500
        /** Não registra nada (padrão de quem não quer registro). */
        val None: EventLog = object : EventLog {
            override val events: StateFlow<List<AppEvent>> = MutableStateFlow(emptyList<AppEvent>()).asStateFlow()
            override fun append(type: AppEventType, detail: String, title: String) = Unit
            override fun clear() = Unit
        }
    }
}

/** Em memória, com carimbo de [wallClock] (epoch ms). Base de [KeyValueEventLogStore] e dublê de testes/simulado. */
open class FakeEventLogStore(initial: List<AppEvent> = emptyList(), private val wallClock: Clock) : EventLog {
    private val state = MutableStateFlow(initial.takeLast(EventLog.LIMIT))
    private var nextId = (initial.maxOfOrNull { it.id } ?: 0L) + 1
    override val events: StateFlow<List<AppEvent>> = state.asStateFlow()

    override fun append(type: AppEventType, detail: String, title: String) {
        synchronized(this) {
            val event = AppEvent(nextId++, wallClock.nowMs(), type, clean(title), clean(detail))
            state.value = (state.value + event).takeLast(EventLog.LIMIT)
            try { changed() } catch (e: Exception) { /* registro é melhor-esforço */ }
        }
    }

    override fun clear() {
        synchronized(this) { state.value = emptyList(); try { changed() } catch (e: Exception) { /* melhor-esforço */ } }
    }

    /** Chamado dentro do lock após cada mudança. */
    protected open fun changed() = Unit

    internal fun clean(text: String) = text.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
}

/**
 * Persistente sobre [KeyValueStorage] (uma chave, uma linha por evento, campos separados por TAB). A gravação roda em
 * [scope] (fora da main thread, coalescida: só o último estado importa); a leitura ignora linhas ruins e nunca lança.
 * Só grava texto vindo do app; o PIN nunca chega aqui.
 */
class KeyValueEventLogStore(
    private val storage: KeyValueStorage,
    wallClock: Clock,
    scope: CoroutineScope,
) : FakeEventLogStore(parse { storage.get(KEY) }, wallClock) {
    private val writes = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            for (ignored in writes) {
                val snapshot = events.value
                try { storage.putAll(mapOf(KEY to serialize(snapshot))) } catch (e: Exception) { /* registro é melhor-esforço */ }
            }
        }
    }

    override fun changed() { writes.trySend(Unit) }

    companion object {
        const val KEY = "eventLog"

        internal fun serialize(events: List<AppEvent>): String =
            events.joinToString("\n") { "${it.id}\t${it.atMs}\t${it.type.name}\t${it.title}\t${it.detail}" }

        internal fun parse(read: () -> String?): List<AppEvent> {
            return try {
                val raw = read()
                if (raw.isNullOrEmpty()) return emptyList()
                raw.split('\n').mapNotNull { line ->
                    val f = line.split('\t', limit = 5)
                    if (f.size != 5) return@mapNotNull null
                    val type = AppEventType.entries.firstOrNull { it.name == f[2] } ?: return@mapNotNull null
                    AppEvent(f[0].toLongOrNull() ?: return@mapNotNull null, f[1].toLongOrNull() ?: return@mapNotNull null, type, f[3], f[4])
                }.distinctBy { it.id }.takeLast(EventLog.LIMIT)
            } catch (e: Exception) { emptyList() }
        }
    }
}

/** Resumo do dia local de [nowMs]: contagens de desbloqueios/bloqueios/quedas e instante do último evento do dia. */
data class DaySummary(val unlocks: Int, val locks: Int, val drops: Int, val lastAtMs: Long?) {
    companion object {
        fun of(events: List<AppEvent>, zone: ZoneId, nowMs: Long): DaySummary {
            val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
            val day = events.filter { Instant.ofEpochMilli(it.atMs).atZone(zone).toLocalDate() == today }
            return DaySummary(
                day.count { it.type == AppEventType.Unlock }, day.count { it.type == AppEventType.Lock },
                day.count { it.type == AppEventType.LinkDropped }, day.maxOfOrNull { it.atMs },
            )
        }
    }
}
