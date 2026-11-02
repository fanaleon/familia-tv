package com.serchu.familiatv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Al prender la TV (o después de actualizar la app) deja el servicio corriendo sin abrir nada. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!Cfg.configured || !Device.isTv(context)) return
        try {
            ContextCompat.startForegroundService(context, Intent(context, RelayService::class.java))
        } catch (e: Exception) {
            // Si el sistema no deja arrancarlo ahora, arranca la próxima vez que se abra la app
        }
    }
}
