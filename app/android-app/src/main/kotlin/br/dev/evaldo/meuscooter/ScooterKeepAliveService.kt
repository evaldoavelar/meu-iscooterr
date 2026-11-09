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
 * Serviço em primeiro plano `connectedDevice` (ADR 0007). Só mantém o processo fora do estado "em cache" (o sistema não
 * congela o app) para o heartbeat do `BleScooterTransport` continuar; NÃO toca no GATT e NÃO envia nada ao patinete.
 * Ação "Desconectar" e remoção da tarefa → o mesmo pedido de desconexão do usuário do menu do chip.
 */
class ScooterKeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground primeiro, sempre (prazo do sistema após startForegroundService), mesmo para a ação Desconectar.
        val promoted = runCatching {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else startForeground(NOTIFICATION_ID, buildNotification())
        }.onFailure { Log.w(TAG, "startForeground falhou: ${it.javaClass.simpleName}") }.isSuccess
        if (!promoted || intent?.action == ACTION_DISCONNECT) {
            if (promoted) disconnectHandler?.invoke()   // o ViewModel desconecta e chama stop()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    /** Removido dos recentes = sair: desconecta como o usuário (sem reconexão automática) e encerra. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        disconnectHandler?.invoke()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Conexão com o patinete", NotificationManager.IMPORTANCE_LOW))
        // Mesmo intent do ícone do lançador: traz a tarefa existente à frente (uma 2ª MainActivity criaria um 2º ViewModel/transporte).
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val open = PendingIntent.getActivity(this, 0, launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED), PendingIntent.FLAG_IMMUTABLE)
        val disconnect = PendingIntent.getService(this, 1, Intent(this, ScooterKeepAliveService::class.java).setAction(ACTION_DISCONNECT), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Meu iScooterr")
            .setContentText("Conectado ao patinete")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Desconectar", disconnect).build())
            .build()
    }

    companion object {
        private const val TAG = "ScooterKeepAlive"
        private const val CHANNEL_ID = "scooter_connection"
        private const val NOTIFICATION_ID = 7
        private const val ACTION_DISCONNECT = "br.dev.evaldo.meuscooter.action.DISCONNECT"
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
