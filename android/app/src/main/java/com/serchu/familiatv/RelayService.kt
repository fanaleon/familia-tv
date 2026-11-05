package com.serchu.familiatv

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import org.json.JSONObject

/**
 * Corre siempre en la TV: mantiene la conexión con el relay, muestra cada mensaje que llega
 * y manda al relay la respuesta que se elige con el control.
 */
class RelayService : Service() {

    companion object {
        const val ACTION_TEST = "com.serchu.familiatv.TEST"
        const val ACTION_TEST_REPLY = "com.serchu.familiatv.TEST_REPLY"
        const val ACTION_TEST_FREEZE = "com.serchu.familiatv.TEST_FREEZE"
        private const val NOTIF_ID = 1
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var presenter: OverlayPresenter
    private lateinit var freeze: FreezeController
    private lateinit var link: RelayLink
    private var wakeLock: PowerManager.WakeLock? = null
    private val seen = LinkedHashSet<String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        presenter = OverlayPresenter(applicationContext) { msg, choice -> sendReply(msg, choice) }
        freeze = FreezeController(applicationContext)
        link = RelayLink(
            applicationContext,
            "role=tv",
            onStateChange = { ok ->
                RelayState.connected = ok
                updateNotification(if (ok) "Conectada — esperando mensajes" else "Reconectando…")
            },
            onJson = { o -> handle(o) }
        )
        Notifs.ensureChannels(this)
        goForeground(if (Cfg.configured) "Conectando…" else "App sin configurar")
        acquireWakeLock()
        freeze.restore() // si había un bloqueo en curso (reinicio de la app), lo retoma
        if (Cfg.configured) link.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TEST -> showTest(false)
            ACTION_TEST_REPLY -> showTest(true)
            // La prueba nunca pisa un bloqueo real: si ya hay uno en curso, no hace nada
            ACTION_TEST_FREEZE -> main.post { if (!freeze.running) freeze.start(45_000L, "Prueba de bloqueo", Cfg.senderName) }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        link.stop()
        RelayState.connected = false
        wakeLock?.let { if (it.isHeld) it.release() }
        presenter.clear()
        freeze.release()
        super.onDestroy()
    }

    // ---------- mensajes ----------

    private fun handle(o: JSONObject) {
        when (o.optString("type")) {
            "freeze" -> {
                val endsIn = o.optLong("endsIn")
                val label = o.optString("label")
                val by = o.optString("by")
                main.post { freeze.start(endsIn, label, by) }
                return
            }
            "unfreeze" -> {
                main.post { freeze.stop() }
                return
            }
            "msg" -> { /* sigue abajo */ }
            else -> return
        }
        val id = o.optString("id")
        val body = o.optString("text")
        if (id.isBlank() || body.isBlank()) return

        // Confirmamos recepción para que el relay no lo reenvíe
        link.send(JSONObject().put("type", "ack").put("id", id))

        val fresh = synchronized(seen) {
            val added = seen.add(id)
            if (seen.size > 100) seen.remove(seen.first())
            added
        }
        if (!fresh) return

        val msg = Msg(
            id = id,
            uid = o.optString("uid"),
            from = o.optString("from", "Familia").ifBlank { "Familia" },
            text = body,
            ts = o.optLong("ts", System.currentTimeMillis()),
            avatar = o.optString("avatar").takeIf { it.isNotBlank() },
            longStay = o.optString("duration") == "larga",
            needsReply = o.optBoolean("reply", false),
            color = o.optString("color")
        )
        main.post {
            RelayState.lastFrom = msg.from
            RelayState.lastText = msg.text
            RelayState.lastTs = msg.ts
            presenter.show(msg)
        }
    }

    private fun sendReply(msg: Msg, choice: String) {
        if (msg.uid.isBlank()) return // mensaje de prueba o de la versión anterior de la app
        link.send(
            JSONObject()
                .put("type", "reply")
                .put("to", msg.uid)
                .put("id", msg.id)
                .put("choice", choice)
                .put("re", msg.text.take(60))
        )
    }

    private fun showTest(withReply: Boolean) {
        val m = Msg(
            id = "test-" + System.nanoTime(),
            uid = "",
            from = Cfg.senderName,
            text = if (withReply) {
                "¿Vienen a cenar? Probá los botones con el control."
            } else {
                "Así se van a ver los mensajes. Todo funciona."
            },
            ts = System.currentTimeMillis(),
            needsReply = withReply,
            color = Profile.color(applicationContext)
        )
        main.post { presenter.show(m) }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "familiatv:relay").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    // ---------- notificación fija del servicio ----------

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, Notifs.CH_SERVICE)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("Familia TV")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
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
