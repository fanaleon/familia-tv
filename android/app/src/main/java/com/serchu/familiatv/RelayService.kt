package com.serchu.familiatv

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Corre siempre en la TV: mantiene abierta la conexión con el relay, reconecta sola
 * y muestra cada mensaje que llega.
 */
class RelayService : Service() {

    companion object {
        const val ACTION_TEST = "com.serchu.familiatv.TEST"
        private const val NOTIF_ID = 1
    }

    private val main = Handler(Looper.getMainLooper())
    private val client = Net.http.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var backoffMs = 2_000L
    @Volatile private var lastConnectAt = 0L

    private lateinit var presenter: OverlayPresenter
    private var wakeLock: PowerManager.WakeLock? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private val seen = LinkedHashSet<String>()
    private val reconnect = Runnable { connect() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        presenter = OverlayPresenter(applicationContext)
        Notifs.ensureChannels(this)
        goForeground(if (Cfg.configured) "Conectando…" else "App sin configurar")
        acquireWakeLock()
        if (Cfg.configured) connect()
        watchNetwork()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TEST) {
            main.post {
                presenter.show(
                    Msg(
                        "test-" + System.nanoTime(),
                        Cfg.senderName,
                        "Así se van a ver los mensajes. Todo funciona.",
                        System.currentTimeMillis()
                    )
                )
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        val s = socket
        socket = null
        s?.cancel()
        RelayState.connected = false
        netCallback?.let {
            try {
                (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                    .unregisterNetworkCallback(it)
            } catch (e: Exception) { /* ya estaba dado de baja */ }
        }
        wakeLock?.let { if (it.isHeld) it.release() }
        presenter.clear()
        super.onDestroy()
    }

    // ---------- conexión ----------

    private fun connect() {
        main.removeCallbacks(reconnect)
        socket?.let {
            socket = null
            it.cancel()
        }
        lastConnectAt = System.currentTimeMillis()
        try {
            val req = Request.Builder()
                .url(Cfg.wsUrl)
                .header("Authorization", "Bearer ${Cfg.token}")
                .build()
            socket = client.newWebSocket(req, listener)
        } catch (e: Exception) {
            RelayState.connected = false
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        main.removeCallbacks(reconnect)
        main.postDelayed(reconnect, backoffMs)
        backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
    }

    private fun connectionLost(ws: WebSocket) {
        if (ws !== socket) return // es de una conexión vieja que ya reemplazamos
        RelayState.connected = false
        updateNotification("Reconectando…")
        main.post { scheduleReconnect() }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            backoffMs = 2_000L
            RelayState.connected = true
            updateNotification("Conectada — esperando mensajes")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val o = try { JSONObject(text) } catch (e: Exception) { return }
            if (o.optString("type") != "msg") return

            val id = o.optString("id")
            val body = o.optString("text")
            if (id.isBlank() || body.isBlank()) return

            // Confirmamos recepción para que el relay no lo reenvíe
            webSocket.send(JSONObject().put("type", "ack").put("id", id).toString())

            val fresh = synchronized(seen) {
                val added = seen.add(id)
                if (seen.size > 100) seen.remove(seen.first())
                added
            }
            if (!fresh) return

            val msg = Msg(
                id,
                o.optString("from", "Familia").ifBlank { "Familia" },
                body,
                o.optLong("ts", System.currentTimeMillis())
            )
            main.post {
                RelayState.lastFrom = msg.from
                RelayState.lastText = msg.text
                RelayState.lastTs = msg.ts
                presenter.show(msg)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            connectionLost(webSocket)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            connectionLost(webSocket)
        }
    }

    /** Cuando vuelve el wifi (o la TV despierta del standby) reconecta sin esperar el backoff. */
    private fun watchNetwork() {
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                main.post {
                    val recentlyTried = System.currentTimeMillis() - lastConnectAt < 3_000
                    if (Cfg.configured && !RelayState.connected && !recentlyTried) {
                        backoffMs = 2_000L
                        connect()
                    }
                }
            }
        }
        try {
            (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                .registerDefaultNetworkCallback(cb)
            netCallback = cb
        } catch (e: Exception) { /* seguimos solo con el reintento por tiempo */ }
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
