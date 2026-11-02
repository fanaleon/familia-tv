package com.serchu.familiatv

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Conexión WebSocket con el relay que se reconecta sola (con espera creciente) y en cuanto
 * vuelve la red. La usan la TV (role=tv) y el móvil (role=phone&uid=...).
 * Los callbacks pueden llegar desde hilos de OkHttp: quien los reciba tiene que saltar al
 * hilo principal si toca vistas.
 */
class RelayLink(
    private val ctx: Context,
    private val query: String,
    private val onStateChange: (Boolean) -> Unit,
    private val onJson: (JSONObject) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private val client = Net.http.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var backoffMs = 2_000L
    @Volatile private var lastConnectAt = 0L

    @Volatile var connected: Boolean = false
        private set

    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private val reconnect = Runnable { connect() }

    fun start() {
        connect()
        watchNetwork()
    }

    fun stop() {
        main.removeCallbacksAndMessages(null)
        val s = socket
        socket = null
        s?.cancel()
        setConnected(false)
        netCallback?.let {
            try {
                (ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                    .unregisterNetworkCallback(it)
            } catch (e: Exception) { /* ya estaba dado de baja */ }
        }
        netCallback = null
    }

    /** Devuelve false si no hay conexión en este momento. */
    fun send(o: JSONObject): Boolean = socket?.send(o.toString()) ?: false

    private fun setConnected(v: Boolean) {
        if (connected != v) {
            connected = v
            onStateChange(v)
        }
    }

    private fun connect() {
        main.removeCallbacks(reconnect)
        socket?.let {
            socket = null
            it.cancel()
        }
        lastConnectAt = System.currentTimeMillis()
        try {
            val req = Request.Builder()
                .url(Cfg.wsUrl + "?" + query)
                .header("Authorization", "Bearer ${Cfg.token}")
                .build()
            socket = client.newWebSocket(req, listener)
        } catch (e: Exception) {
            setConnected(false)
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
        setConnected(false)
        main.post { scheduleReconnect() }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            backoffMs = 2_000L
            setConnected(true)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val o = try { JSONObject(text) } catch (e: Exception) { return }
            onJson(o)
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
                    if (!connected && !recentlyTried && socket != null) {
                        backoffMs = 2_000L
                        connect()
                    }
                }
            }
        }
        try {
            (ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                .registerDefaultNetworkCallback(cb)
            netCallback = cb
        } catch (e: Exception) { /* seguimos solo con el reintento por tiempo */ }
    }
}
