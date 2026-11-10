package br.dev.evaldo.meuscooter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.util.Log
import br.dev.evaldo.meuscooter.domain.SessionKeepAlive

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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground primeiro, sempre (prazo do sistema após startForegroundService), mesmo para a ação Desconectar.
        // ACTION_REFRESH (viagem começou/terminou com o serviço ativo) repete a promoção com o tipo certo (S42, ADR 0015).
        val locationGranted = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val result = KeepAliveServiceLogic.promote(Build.VERSION.SDK_INT, tripRecording, locationGranted) { type, locationActive ->
            val notification = buildNotification(locationActive)
            if (type != KeepAliveServiceLogic.TYPE_NONE) startForeground(KeepAliveServiceLogic.NOTIFICATION_ID, notification, type)
            else startForeground(KeepAliveServiceLogic.NOTIFICATION_ID, notification)
        }
        if (result.locationFellBack) Log.w(TAG, "tipo location recusado (SecurityException); seguindo só com connectedDevice")
        val promoted = result.promoted
        if (!promoted) Log.w(TAG, "startForeground falhou")

        val decision = KeepAliveServiceLogic.evaluateStart(intent?.action, promoted)
        if (decision.shouldDisconnect) disconnectHandler?.invoke()   // o ViewModel desconecta e chama stop()
        if (decision.shouldStopSelf) stopSelf()
        return START_NOT_STICKY
    }

    /** Removido dos recentes = sair: desconecta como o usuário (sem reconexão automática) e encerra (ADR 0007 §4). */
    override fun onTaskRemoved(rootIntent: Intent?) {
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
        val disconnect = PendingIntent.getService(this, 1, Intent(this, ScooterKeepAliveService::class.java).setAction(KeepAliveServiceLogic.ACTION_DISCONNECT), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, KeepAliveServiceLogic.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Meu iScooterr")
            .setContentText(if (recordingRoute) "Conectado ao patinete · gravando trajeto" else "Conectado ao patinete")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_action_disconnect), KeepAliveServiceLogic.ACTION_DISCONNECT_TITLE, disconnect).build())
            .build()
    }

    companion object {
        private const val TAG = "ScooterKeepAlive"
        /** Definido por [AndroidSessionKeepAlive.start] (thread principal) e limpo em `stop()`. */
        @Volatile internal var disconnectHandler: (() -> Unit)? = null
        /** Viagem gravando (definido por [AndroidSessionKeepAlive.setTripRecording], thread principal). Só decide o tipo; nunca liga GPS. */
        @Volatile internal var tripRecording: Boolean = false
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

    override fun stop() {
        ScooterKeepAliveService.disconnectHandler = null
        ScooterKeepAliveService.tripRecording = false
        context.stopService(intent)
    }
}
