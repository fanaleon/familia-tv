package com.serchu.familiatv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Al prender el equipo (o después de actualizar la app) deja corriendo el servicio que
 * corresponde, sin abrir nada: en la TV el que muestra mensajes, en el móvil el que avisa
 * las respuestas.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!Cfg.configured) return
        val target = if (Device.isTv(context)) RelayService::class.java else PhoneService::class.java
        try {
            ContextCompat.startForegroundService(context, Intent(context, target))
        } catch (e: Exception) {
            // Si el sistema no deja arrancarlo ahora, arranca la próxima vez que se abra la app
        }
    }
}
