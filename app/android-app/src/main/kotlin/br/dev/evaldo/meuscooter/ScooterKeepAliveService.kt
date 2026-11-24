package br.dev.evaldo.meuscooter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.util.Log
import br.dev.evaldo.meuscooter.domain.SessionKeepAlive
import br.dev.evaldo.meuscooter.domain.TripNotificationAction

/**
 * Lógica pura do ciclo de vida e comandos do serviço de keep-alive (ADR 0007 §4 e S41),
 * desacoplada do [Context] do Android para permitir testes unitários na JVM sem Robolectric.
 */
object KeepAliveServiceLogic {
    const val CHANNEL_ID = "scooter_connection"
    const val NOTIFICATION_ID = 7
    const val ACTION_DISCONNECT = "br.dev.evaldo.meuscooter.action.DISCONNECT"
    const val ACTION_DISCONNECT_TITLE = "Desconectar"

    data class StartDecision(
        val shouldDisconnect: Boolean,
        val shouldStopSelf: Boolean,
        val logWarning: Boolean,
    )

    data class TaskRemovedDecision(
        val shouldDisconnect: Boolean,
        val shouldStopSelf: Boolean,
    )

    const val ACTION_TRIP_RESUME = "br.dev.evaldo.meuscooter.action.TRIP_RESUME"
    const val ACTION_TRIP_STOP = "br.dev.evaldo.meuscooter.action.TRIP_STOP"
    const val ACTION_TRIP_RESET = "br.dev.evaldo.meuscooter.action.TRIP_RESET"
    const val TRIP_RESUME_TITLE = "Continuar"
    const val TRIP_STOP_TITLE = "Parar"
    const val TRIP_RESET_TITLE = "Reiniciar"

    /** Ação de viagem da notificação (S53) ou null. */
    fun tripActionOf(action: String?): TripNotificationAction? = when (action) {
        ACTION_TRIP_RESUME -> TripNotificationAction.Resume
        ACTION_TRIP_STOP -> TripNotificationAction.Stop
        ACTION_TRIP_RESET -> TripNotificationAction.Reset
        else -> null
    }

    const val ACTION_REFRESH = "br.dev.evaldo.meuscooter.action.REFRESH"
    const val TYPE_NONE = 0
    const val TYPE_CONNECTED_DEVICE = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
    const val TYPE_LOCATION = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION

    data class PromotionResult(val promoted: Boolean, val type: Int, val locationFellBack: Boolean) {
        val locationActive get() = promoted && (type and TYPE_LOCATION) != 0
    }

    /**
     * Tipo de primeiro plano desejado (S42, ADR 0015): `connectedDevice` sempre; `location` SOMENTE com viagem gravando
     * (Running + salvar trajeto, decidido pelo ViewModel) E permissão ACCESS_FINE_LOCATION (só ela conta; aproximada/COARSE não) de localização concedida. Antes da API 29 não existe tipo ([TYPE_NONE]).
     */
    fun foregroundType(sdk: Int, tripRecording: Boolean, locationGranted: Boolean): Int = when {
        sdk < 29 -> TYPE_NONE
        tripRecording && locationGranted -> TYPE_CONNECTED_DEVICE or TYPE_LOCATION
        else -> TYPE_CONNECTED_DEVICE
    }

    /**
     * Promove com o tipo certo via [startForeground] (efeito injetável). com `location` pedido, qualquer exceção (permissão
     * revogada, início em segundo plano recusado) cai para só `connectedDevice`; sem `location`, falha = não promovido.
     */
    fun promote(sdk: Int, tripRecording: Boolean, locationGranted: Boolean, startForeground: (type: Int, locationActive: Boolean) -> Unit): PromotionResult {
        val wanted = foregroundType(sdk, tripRecording, locationGranted)
        try {
            startForeground(wanted, (wanted and TYPE_LOCATION) != 0)
            return PromotionResult(true, wanted, false)
        } catch (e: Exception) {
            // Com `location` pedido, QUALQUER falha (SecurityException, ForegroundServiceStartNotAllowedException/IllegalStateException
            // em segundo plano na API 31+) tenta de novo só com connectedDevice, para a conexão BLE não perder o serviço.
            if ((wanted and TYPE_LOCATION) == 0) return PromotionResult(false, wanted, false)
        }
        return try {
            startForeground(TYPE_CONNECTED_DEVICE, false)
            PromotionResult(true, TYPE_CONNECTED_DEVICE, true)
        } catch (e: Exception) {
            PromotionResult(false, TYPE_CONNECTED_DEVICE, true)
        }
    }

    /**
     * Avalia o comando recebido em [Service.onStartCommand].
     *
     * - Ação Desconectar: o usuário tocou "Desconectar" na notificação; sempre solicita
     *   desconexão ao ViewModel e encerra o serviço.
     * - Início normal com promoção com sucesso: o serviço foi promovido a foreground service com notificação válida;
     *   permanece em execução.
     * - Falha na promoção (exceção em startForeground, como permissão de notificação ausente ou restrição de início
     *   em segundo plano): o serviço NÃO pode ficar sem notificação (risco de ANR do sistema) nem mascarar o erro;
     *   deve parar-se com segurança (`shouldStopSelf = true`), registrar aviso (`logWarning = true`) e NÃO desconectar
     *   o patinete (`shouldDisconnect = false`).
     */
    fun evaluateStart(action: String?, promoted: Boolean): StartDecision {
        val isDisconnect = action == ACTION_DISCONNECT
        return when {
            isDisconnect -> StartDecision(shouldDisconnect = true, shouldStopSelf = true, logWarning = false)
            !promoted -> StartDecision(shouldDisconnect = false, shouldStopSelf = true, logWarning = true)
            else -> StartDecision(shouldDisconnect = false, shouldStopSelf = false, logWarning = false)
        }
    }

    /**
     * Avalia [Service.onTaskRemoved]: remover a tarefa dos recentes (ADR 0007 §4)
     * é tratado como "Desconectar", solicitando a desconexão ao ViewModel e encerrando o serviço.
     */
    fun evaluateTaskRemoved(): TaskRemovedDecision {
        return TaskRemovedDecision(shouldDisconnect = true, shouldStopSelf = true)
    }
}

/**
 * Serviço em primeiro plano `connectedDevice` (ADR 0007); com viagem gravando e permissão, também `location` (ADR 0015). Só mantém o processo fora do estado "em cache" (o sistema não
 * congela o app) para o heartbeat do `BleScooterTransport` continuar; NÃO toca no GATT e NÃO envia nada ao patinete.
 * Ação "Desconectar" e remoção da tarefa → o mesmo pedido de desconexão do usuário do menu do chip.
 */
class ScooterKeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    /** Só debug (S49): registra mudança de tela enquanto o serviço existir. */
    private var screenReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        SvcLog.log(this, "svc onCreate", listOf("pid" to android.os.Process.myPid()))
        if (SvcLog.isDebuggable(this)) runCatching {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    SvcLog.log(context, if (intent.action == Intent.ACTION_SCREEN_ON) "screen on" else "screen off")
                }
            }
            val filter = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) }
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(receiver, filter)
            screenReceiver = receiver
        }
    }

    override fun onDestroy() {
        SvcLog.log(this, "svc onDestroy")
        screenReceiver?.let { runCatching { unregisterReceiver(it) } }
        screenReceiver = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground primeiro, sempre (prazo do sistema após startForegroundService), mesmo para a ação Desconectar.
        // ACTION_REFRESH (viagem começou/terminou com o serviço ativo) repete a promoção com o tipo certo (S42, ADR 0015).
        val locationGranted = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        SvcLog.log(this, "svc onStartCommand", listOf("action" to intent?.action, "tripRecording" to tripRecording, "locationGranted" to locationGranted))
        SvcLog.logSystemState(this, "svc-start")
        val result = KeepAliveServiceLogic.promote(Build.VERSION.SDK_INT, tripRecording, locationGranted) { type, locationActive ->
            SvcLog.log(this, "svc startForeground try", listOf("type" to fgTypeName(type)))
            try {
                val notification = buildNotification(locationActive)
                if (type != KeepAliveServiceLogic.TYPE_NONE && Build.VERSION.SDK_INT >= 29) startForeground(KeepAliveServiceLogic.NOTIFICATION_ID, notification, type)
                else startForeground(KeepAliveServiceLogic.NOTIFICATION_ID, notification)
                SvcLog.log(this, "svc startForeground ok", listOf("type" to fgTypeName(type)))
            } catch (e: Exception) {
                SvcLog.log(this, "svc startForeground fail", listOf("type" to fgTypeName(type), "ex" to e.javaClass.simpleName, "msg" to e.message))
                throw e
            }
        }
        SvcLog.log(this, "svc promotion", listOf("promoted" to result.promoted, "type" to fgTypeName(result.type), "fellBackToConnectedDevice" to result.locationFellBack))
        if (result.locationFellBack) Log.w(TAG, "tipo location recusado (exceção na promoção); seguindo só com connectedDevice")
        val promoted = result.promoted
        if (!promoted) Log.w(TAG, "startForeground falhou")

        val decision = KeepAliveServiceLogic.evaluateStart(intent?.action, promoted)
        if (decision.shouldDisconnect) disconnectHandler?.invoke()   // o ViewModel desconecta e chama stop()
        else if (promoted) KeepAliveServiceLogic.tripActionOf(intent?.action)?.let { tripActionHandler?.invoke(it) }   // S53
        if (decision.shouldStopSelf) stopSelf()
        return START_NOT_STICKY
    }

    /** Removido dos recentes = sair: desconecta como o usuário (sem reconexão automática) e encerra (ADR 0007 §4). */
    override fun onTaskRemoved(rootIntent: Intent?) {
        SvcLog.log(this, "svc onTaskRemoved")
        val decision = KeepAliveServiceLogic.evaluateTaskRemoved()
        if (decision.shouldDisconnect) disconnectHandler?.invoke()
        if (decision.shouldStopSelf) stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    private fun buildNotification(recordingRoute: Boolean): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(KeepAliveServiceLogic.CHANNEL_ID, "Conexão com o patinete", NotificationManager.IMPORTANCE_LOW))
        // Mesmo intent do ícone do lançador: traz a tarefa existente à frente (uma 2ª MainActivity criaria um 2º ViewModel/transporte).
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val open = PendingIntent.getActivity(this, 0, launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED), PendingIntent.FLAG_IMMUTABLE)
        fun service(code: Int, action: String) = PendingIntent.getService(this, code, Intent(this, ScooterKeepAliveService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE)
        fun act(icon: Int, title: String, pending: PendingIntent) = Notification.Action.Builder(Icon.createWithResource(this, icon), title, pending).build()
        val builder = Notification.Builder(this, KeepAliveServiceLogic.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_scooter)
            .setContentTitle("Meu iScooterr")
            .setContentText(when {
                tripPaused -> "Viagem pausada"
                recordingRoute -> "Conectado ao patinete · gravando trajeto"
                else -> "Conectado ao patinete"
            })
            .setOngoing(true)
            .setContentIntent(open)
        // Android mostra até 3 ações: pausada = Continuar | Parar | Reiniciar (S53); senão só Desconectar.
        if (tripPaused) {
            builder.addAction(act(R.drawable.ic_action_trip_resume, KeepAliveServiceLogic.TRIP_RESUME_TITLE, service(2, KeepAliveServiceLogic.ACTION_TRIP_RESUME)))
                .addAction(act(R.drawable.ic_action_trip_stop, KeepAliveServiceLogic.TRIP_STOP_TITLE, service(3, KeepAliveServiceLogic.ACTION_TRIP_STOP)))
                .addAction(act(R.drawable.ic_action_trip_reset, KeepAliveServiceLogic.TRIP_RESET_TITLE, service(4, KeepAliveServiceLogic.ACTION_TRIP_RESET)))
        } else {
            builder.addAction(act(R.drawable.ic_action_disconnect, KeepAliveServiceLogic.ACTION_DISCONNECT_TITLE, service(1, KeepAliveServiceLogic.ACTION_DISCONNECT)))
        }
        return builder.build()
    }

    private fun fgTypeName(type: Int): String = when (type) {
        KeepAliveServiceLogic.TYPE_NONE -> "none"
        KeepAliveServiceLogic.TYPE_CONNECTED_DEVICE -> "connectedDevice"
        KeepAliveServiceLogic.TYPE_CONNECTED_DEVICE or KeepAliveServiceLogic.TYPE_LOCATION -> "connectedDevice|location"
        else -> type.toString()
    }

    companion object {
        private const val TAG = "ScooterKeepAlive"
        /** Definido por [AndroidSessionKeepAlive.start] (thread principal) e limpo em `stop()`. */
        @Volatile internal var disconnectHandler: (() -> Unit)? = null
        /** Viagem gravando (definido por [AndroidSessionKeepAlive.setTripRecording], thread principal). Só decide o tipo; nunca liga GPS. */
        @Volatile internal var tripRecording: Boolean = false
        /** Viagem pausada (S53): troca os botões da notificação. */
        @Volatile internal var tripPaused: Boolean = false
        @Volatile internal var tripActionHandler: ((TripNotificationAction) -> Unit)? = null
    }
}

/** Implementação Android de [SessionKeepAlive]: só criada com BLE real + TX (ADR 0007 §3). Chamada na thread principal. */
class AndroidSessionKeepAlive(private val context: Context) : SessionKeepAlive {
    private val intent get() = Intent(context, ScooterKeepAliveService::class.java)

    override fun start(onUserDisconnect: () -> Unit) {
        ScooterKeepAliveService.disconnectHandler = onUserDisconnect
        // API 31+: início em segundo plano pode ser recusado (ForegroundServiceStartNotAllowedException); volta ao comportamento anterior.
        runCatching { context.startForegroundService(intent) }
            .onFailure { Log.w("ScooterKeepAlive", "serviço em primeiro plano não iniciado: ${it.javaClass.simpleName}") }
    }

    override fun setTripRecording(recording: Boolean) {
        if (ScooterKeepAliveService.tripRecording == recording) return
        ScooterKeepAliveService.tripRecording = recording
        // Serviço ativo: nova promoção com o tipo certo (startForeground de novo). Fora disso, o flag vale no próximo start().
        if (ScooterKeepAliveService.disconnectHandler != null) {
            runCatching { context.startForegroundService(intent.setAction(KeepAliveServiceLogic.ACTION_REFRESH)) }
                .onFailure { Log.w("ScooterKeepAlive", "re-promoção não enviada: ${it.javaClass.simpleName}") }
        }
    }

    override fun setTripPaused(paused: Boolean) {
        if (ScooterKeepAliveService.tripPaused == paused) return
        ScooterKeepAliveService.tripPaused = paused
        if (ScooterKeepAliveService.disconnectHandler != null) {
            runCatching { context.startForegroundService(intent.setAction(KeepAliveServiceLogic.ACTION_REFRESH)) }
                .onFailure { Log.w("ScooterKeepAlive", "atualização da notificação não enviada: ${it.javaClass.simpleName}") }
        }
    }

    override fun setTripActionHandler(handler: ((TripNotificationAction) -> Unit)?) { ScooterKeepAliveService.tripActionHandler = handler }

    override fun stop() {
        ScooterKeepAliveService.disconnectHandler = null
        ScooterKeepAliveService.tripActionHandler = null
        ScooterKeepAliveService.tripPaused = false
        ScooterKeepAliveService.tripRecording = false
        context.stopService(intent)
    }
}
