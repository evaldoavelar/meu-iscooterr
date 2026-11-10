package br.dev.evaldo.meuscooter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
 * Serviço em primeiro plano `connectedDevice` (ADR 0007). Só mantém o processo fora do estado "em cache" (o sistema não
 * congela o app) para o heartbeat do `BleScooterTransport` continuar; NÃO toca no GATT e NÃO envia nada ao patinete.
 * Ação "Desconectar" e remoção da tarefa → o mesmo pedido de desconexão do usuário do menu do chip.
 */
class ScooterKeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground primeiro, sempre (prazo do sistema após startForegroundService), mesmo para a ação Desconectar.
        val promoted = runCatching {
            if (Build.VERSION.SDK_INT >= 29) startForeground(KeepAliveServiceLogic.NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else startForeground(KeepAliveServiceLogic.NOTIFICATION_ID, buildNotification())
        }.onFailure { Log.w(TAG, "startForeground falhou: ${it.javaClass.simpleName}", it) }.isSuccess

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

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(KeepAliveServiceLogic.CHANNEL_ID, "Conexão com o patinete", NotificationManager.IMPORTANCE_LOW))
        // Mesmo intent do ícone do lançador: traz a tarefa existente à frente (uma 2ª MainActivity criaria um 2º ViewModel/transporte).
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val open = PendingIntent.getActivity(this, 0, launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED), PendingIntent.FLAG_IMMUTABLE)
        val disconnect = PendingIntent.getService(this, 1, Intent(this, ScooterKeepAliveService::class.java).setAction(KeepAliveServiceLogic.ACTION_DISCONNECT), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, KeepAliveServiceLogic.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Meu iScooterr")
            .setContentText("Conectado ao patinete")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, KeepAliveServiceLogic.ACTION_DISCONNECT_TITLE, disconnect).build())
            .build()
    }

    companion object {
        private const val TAG = "ScooterKeepAlive"
        /** Definido por [AndroidSessionKeepAlive.start] (thread principal) e limpo em `stop()`. */
        @Volatile internal var disconnectHandler: (() -> Unit)? = null
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

    override fun stop() {
        ScooterKeepAliveService.disconnectHandler = null
        context.stopService(intent)
    }
}
