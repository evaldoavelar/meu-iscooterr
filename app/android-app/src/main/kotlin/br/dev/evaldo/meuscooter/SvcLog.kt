package br.dev.evaldo.meuscooter

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.PowerManager
import android.util.Log

/** Tag das linhas de diagnóstico do serviço em primeiro plano e do estado do sistema (S49, só debug). */
internal const val SVC_LOG_TAG = "IX7SVC"

private const val SVC_VALUE_MAX = 80

/**
 * Linha de log do serviço (S49): `evento k=v k=v`. Função pura. Valores nulos viram `-`; espaços/quebras viram `_`
 * e o valor é truncado a 80 caracteres (mensagens de exceção curtas). Nunca passe PIN, serial ou dado sensível.
 */
internal fun formatSvcLogLine(event: String, fields: List<Pair<String, Any?>> = emptyList()): String =
    buildString {
        append(event)
        for ((k, v) in fields) {
            append(' ').append(k).append('=')
            append(v?.toString()?.replace(Regex("\\s+"), "_")?.take(SVC_VALUE_MAX)?.ifEmpty { "-" } ?: "-")
        }
    }

/** Escritor das linhas `IX7SVC`: nada é feito fora de build depurável; a E/S é assíncrona (FileDebugLog). */
internal object SvcLog {
    fun isDebuggable(context: Context): Boolean = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    fun log(context: Context, event: String, fields: List<Pair<String, Any?>> = emptyList()) {
        if (!isDebuggable(context)) return
        runCatching {
            val line = formatSvcLogLine(event, fields)
            Log.i(SVC_LOG_TAG, line)
            FileDebugLog.shared(true, context.applicationContext.filesDir)?.append(SVC_LOG_TAG, line)
        }
    }

    /** Estado de energia/tela do sistema (sem permissão nova; leituras baratas e sem E/S de disco). */
    fun logSystemState(context: Context, reason: String) {
        if (!isDebuggable(context)) return
        runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val fields = mutableListOf<Pair<String, Any?>>(
                "reason" to reason,
                "ignoringBatteryOpt" to pm?.isIgnoringBatteryOptimizations(context.packageName),
                "interactive" to pm?.isInteractive,
                "deviceIdle" to pm?.isDeviceIdleMode,
                "powerSave" to pm?.isPowerSaveMode,
            )
            if (Build.VERSION.SDK_INT >= 28) {
                val bucket = (context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager)?.appStandbyBucket
                fields += "standbyBucket" to bucket
                fields += "backgroundRestricted" to (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.isBackgroundRestricted
            }
            log(context, "state", fields)
        }
    }
}
