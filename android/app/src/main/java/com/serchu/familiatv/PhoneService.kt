package com.serchu.familiatv

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import org.json.JSONObject

/**
 * Corre en el móvil: mantiene una conexión liviana con el relay para enterarse apenas
 * alguien contesta desde la tele, y lo avisa con una notificación.
 */
class PhoneService : Service() {

    companion object {
        private const val NOTIF_ID = 2
    }

    private lateinit var link: RelayLink
    private val seen = LinkedHashSet<String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifs.ensureChannels(this)
        goForeground(if (Cfg.configured) T("Conectando…") else T("App sin configurar"))
        link = RelayLink(
            applicationContext,
            "role=phone&uid=" + Profile.uid(applicationContext),
            onStateChange = { ok ->
                updateNotification(if (ok) T("Conectado — avisa cuando contesten") else T("Reconectando…"))
            },
            onJson = { o -> handle(o) }
        )
        if (Cfg.configured) link.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        link.stop()
        super.onDestroy()
    }

    /** Avisos del sistema: pago recibido, plan por vencer. */
    private fun handleAviso(o: JSONObject) {
        val aid = o.optString("aid")
        if (aid.isBlank()) return
        link.send(JSONObject().put("type", "ack").put("id", aid))
        val title = Lang.pick(o, "titulo", "i18n", o.optString("title", "Hey Family"))
        val text = Lang.pick(o, "texto", "i18n", o.optString("text"))
        Inbox.add(this, aid, title, text, o.optLong("ts"))          // queda en la bandeja de la app
        if (Inbox.markNotified(this, aid)) Notifs.post(this, aid.hashCode(), title, text)
    }

    private fun handle(o: JSONObject) {
        if (o.optString("type") == "aviso") {
            handleAviso(o)
            return
        }
        if (o.optString("type") != "reply") return
        val rid = o.optString("rid")
        if (rid.isBlank()) return

        // Confirmamos para que el relay no la vuelva a mandar
        link.send(JSONObject().put("type", "ack").put("id", rid))

        val fresh = synchronized(seen) {
            val added = seen.add(rid)
            if (seen.size > 100) seen.remove(seen.first())
            added
        }
        if (!fresh) return

        val re = o.optString("re")
        val quoted = if (re.isNotBlank()) T("A tu mensaje: «%s»", re) else T("Respuesta a tu mensaje")
        val (title, text) = when (o.optString("choice")) {
            "ok" -> T("Respondieron: OK") to quoted
            "voy" -> T("Respondieron: Voy") to quoted
            "ahora_no" -> T("Respondieron: Ahora no") to quoted
            "cinco_min" -> T("Respondieron: 5 minutos") to quoted
            "visto" -> T("Ya lo vieron en la tele") to quoted
            "sin_respuesta" -> T("Nadie respondió") to quoted
            else -> return
        }
        Inbox.add(this, "reply-$rid", title, text, System.currentTimeMillis(), notified = true)
        Notifs.post(this, rid.hashCode(), title, text, RelayState.MSG_TTL_MS)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, Notifs.CH_SERVICE)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("Hey Family")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun goForeground(text: String) {
        val n = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun updateNotification(text: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotification(text))
    }
}
