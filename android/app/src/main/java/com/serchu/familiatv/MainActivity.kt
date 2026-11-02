package com.serchu.familiatv

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException

/**
 * Un solo APK:
 *  - en el móvil: pantalla para escribir y mandar mensajes
 *  - en la TV: pantalla de estado (el servicio hace el trabajo de fondo)
 */
class MainActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private val match = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
    private val maxLen = 500
    private var isTv = false

    // Frases rápidas del móvil: tocás una y se copia al cuadro de texto (después podés editarla)
    private val quickPhrases = listOf(
        "Ya voy",
        "Llego en 10 minutos",
        "Vayan poniendo la mesa",
        "Los quiero mucho",
        "A dormir, que es tarde"
    )

    // móvil
    private lateinit var statusPill: TextView
    private lateinit var input: EditText
    private lateinit var counter: TextView
    private lateinit var sendBtn: TextView
    private lateinit var feedback: TextView
    private var sending = false

    // TV
    private lateinit var tvStatus: TextView
    private lateinit var tvLast: TextView
    private lateinit var tvPermBox: LinearLayout

    private val phonePoll: Runnable = object : Runnable {
        override fun run() {
            fetchStatus()
            main.postDelayed(this, 8_000)
        }
    }

    private val tvTick: Runnable = object : Runnable {
        override fun run() {
            refreshTv()
            main.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isTv = Device.isTv(this)
        if (isTv) {
            setContentView(buildTv())
            startRelay()
            askNotificationPermission()
        } else {
            setContentView(buildPhone())
        }
    }

    override fun onResume() {
        super.onResume()
        main.post(if (isTv) tvTick else phonePoll)
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(tvTick)
        main.removeCallbacks(phonePoll)
    }

    // ------------------------------------------------------------------ helpers de vista

    private fun label(text: String, sp: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun lp(w: Int, h: Int, top: Int = 0, bottom: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, h).apply {
            topMargin = dp(top)
            bottomMargin = dp(bottom)
        }

    // ------------------------------------------------------------------ MÓVIL

    private fun buildPhone(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(32), dp(20), dp(24))
        }

        root.addView(label("Familia TV", 30f, Pal.text, bold = true), lp(wrap, wrap))
        root.addView(label("Mandale un mensaje a la tele de casa", 15f, Pal.muted), lp(wrap, wrap, top = 2))

        statusPill = label("● Comprobando…", 14f, Pal.muted).apply {
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = roundedBg(Pal.card, dp(20))
        }
        root.addView(statusPill, lp(wrap, wrap, top = 16))

        if (!Cfg.configured) {
            root.addView(
                label("La app no está configurada: al compilar faltaron RELAY_URL y RELAY_TOKEN.", 14f, Pal.warn).apply {
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                    background = roundedBg(Pal.card, dp(16), Pal.warn, dp(1))
                },
                lp(match, wrap, top = 12)
            )
        }

        // Tarjeta con el cuadro de texto
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(Pal.card, dp(22))
            setPadding(dp(18), dp(14), dp(18), dp(10))
        }
        input = EditText(this).apply {
            hint = "Escribí tu mensaje…"
            setHintTextColor(Pal.muted)
            setTextColor(Pal.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(0, 0, 0, 0)
            minLines = 5
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf<InputFilter>(InputFilter.LengthFilter(maxLen))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    updateSendState()
                }
            })
        }
        counter = label("0/$maxLen", 12f, Pal.muted).apply { gravity = Gravity.END }
        card.addView(input, lp(match, wrap))
        card.addView(counter, lp(match, wrap))
        root.addView(card, lp(match, wrap, top = 18))

        // Frases rápidas
        root.addView(label("Frases rápidas", 13f, Pal.muted), lp(wrap, wrap, top = 18, bottom = 8))
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        quickPhrases.forEach { phrase ->
            val chip = label(phrase, 14f, Pal.text).apply {
                setPadding(dp(14), dp(9), dp(14), dp(9))
                background = withRipple(roundedBg(Pal.cardHi, dp(20)))
                isClickable = true
                setOnClickListener {
                    input.setText(phrase)
                    input.setSelection(input.text.length)
                    input.requestFocus()
                }
            }
            chips.addView(chip, lp(wrap, wrap).apply { rightMargin = dp(8) })
        }
        root.addView(
            HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(chips)
            },
            lp(match, wrap)
        )

        // Botón de enviar
        sendBtn = label("Enviar a la tele", 18f, Color.WHITE, bold = true).apply {
            gravity = Gravity.CENTER
            background = withRipple(roundedBg(Pal.accent, dp(18)))
            isClickable = true
            setOnClickListener { send() }
        }
        root.addView(sendBtn, lp(match, dp(58), top = 22))

        feedback = label("", 14f, Pal.muted).apply {
            gravity = Gravity.CENTER
            alpha = 0f
        }
        root.addView(feedback, lp(match, wrap, top = 14))

        updateSendState()

        return ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        }
    }

    private fun updateSendState() {
        counter.text = "${input.text.length}/$maxLen"
        val ready = input.text.toString().trim().isNotEmpty() && !sending
        sendBtn.alpha = if (ready) 1f else 0.45f
    }

    private fun showFeedback(text: String, color: Int) {
        feedback.animate().cancel()
        feedback.text = text
        feedback.setTextColor(color)
        feedback.alpha = 1f
        feedback.animate().alpha(0f).setStartDelay(4_000).setDuration(600).start()
    }

    private fun relayRequest(path: String): Request.Builder =
        Request.Builder()
            .url(Cfg.httpBase + path)
            .header("Authorization", "Bearer " + Cfg.token)

    private fun send() {
        val text = input.text.toString().trim()
        if (text.isEmpty() || sending) return
        if (!Cfg.configured) {
            showFeedback("Falta configurar RELAY_URL y RELAY_TOKEN.", Pal.bad)
            return
        }

        sending = true
        sendBtn.text = "Enviando…"
        updateSendState()

        val body = JSONObject()
            .put("from", Cfg.senderName)
            .put("text", text)
            .toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())

        Net.http.newCall(relayRequest("/send").post(body).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { sendDone(false, false, "No hay conexión con el servidor. Probá de nuevo.") }
            }

            override fun onResponse(call: Call, response: Response) {
                val raw = response.use { it.body?.string().orEmpty() }
                val code = response.code
                val delivered = runCatching { JSONObject(raw).optBoolean("delivered", false) }.getOrDefault(false)
                runOnUiThread {
                    when {
                        code in 200..299 -> sendDone(true, delivered, null)
                        code == 401 -> sendDone(false, false, "El código de acceso no coincide con el del servidor.")
                        else -> sendDone(false, false, "El servidor respondió con un error ($code).")
                    }
                }
            }
        })
    }

    private fun sendDone(ok: Boolean, delivered: Boolean, error: String?) {
        sending = false
        sendBtn.text = "Enviar a la tele"
        if (ok) {
            input.setText("")
            if (delivered) {
                showFeedback("Enviado. La tele lo muestra ahora.", Pal.ok)
            } else {
                showFeedback("Quedó en cola: se muestra apenas se prenda la tele.", Pal.warn)
            }
            fetchStatus()
        } else {
            showFeedback(error ?: "No se pudo enviar.", Pal.bad)
        }
        updateSendState()
    }

    private fun fetchStatus() {
        if (!Cfg.configured) {
            renderStatus(3, 0)
            return
        }
        Net.http.newCall(relayRequest("/status").build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { renderStatus(3, 0) }
            }

            override fun onResponse(call: Call, response: Response) {
                val raw = response.use { it.body?.string().orEmpty() }
                val okHttp = response.isSuccessful
                val o = runCatching { JSONObject(raw) }.getOrNull()
                runOnUiThread {
                    if (okHttp && o != null) {
                        renderStatus(if (o.optBoolean("tv")) 1 else 2, o.optInt("pendientes"))
                    } else {
                        renderStatus(3, 0)
                    }
                }
            }
        })
    }

    /** state: 0 comprobando, 1 TV conectada, 2 TV apagada, 3 sin servidor */
    private fun renderStatus(state: Int, pending: Int) {
        val (t, c) = when (state) {
            1 -> "● TV conectada" to Pal.ok
            2 -> (if (pending > 0) "● TV apagada — $pending en cola" else "● TV apagada o sin red") to Pal.warn
            3 -> "● Sin conexión con el servidor" to Pal.bad
            else -> "● Comprobando…" to Pal.muted
        }
        statusPill.text = t
        statusPill.setTextColor(c)
    }

    // ------------------------------------------------------------------ TV

    private fun buildTv(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(64), dp(32), dp(64), dp(32))
        }

        root.addView(label("Familia TV", 54f, Pal.text, bold = true), lp(wrap, wrap))
        root.addView(
            label("Los mensajes de la familia aparecen directo en tu pantalla", 22f, Pal.muted),
            lp(wrap, wrap, top = 6)
        )

        tvStatus = label("", 30f, Pal.muted)
        root.addView(tvStatus, lp(wrap, wrap, top = 36))

        tvLast = label("", 22f, Pal.muted).apply {
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        root.addView(tvLast, lp(wrap, wrap, top = 10))

        tvPermBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(Pal.card, dp(20), Pal.warn, dp(2))
            setPadding(dp(28), dp(20), dp(28), dp(20))
            visibility = View.GONE
        }
        tvPermBox.addView(
            label("Falta un permiso para mostrar los mensajes sobre lo que estás viendo.", 22f, Pal.warn, bold = true),
            lp(wrap, wrap)
        )
        tvPermBox.addView(label("Desde una PC con adb:", 18f, Pal.muted), lp(wrap, wrap, top = 8))
        tvPermBox.addView(
            label("adb shell appops set $packageName SYSTEM_ALERT_WINDOW allow", 18f, Pal.text).apply {
                typeface = Typeface.MONOSPACE
            },
            lp(wrap, wrap, top = 4)
        )
        tvPermBox.addView(tvButton("Abrir ajustes del permiso") { openOverlaySettings() }, lp(wrap, wrap, top = 16))
        root.addView(tvPermBox, lp(wrap, wrap, top = 28))

        val test = tvButton("Enviar mensaje de prueba") { sendTest() }
        root.addView(test, lp(wrap, wrap, top = 28))
        test.requestFocus()

        return root
    }

    private fun refreshTv() {
        val (t, c) = when {
            !Cfg.configured -> "App sin configurar (faltan RELAY_URL y RELAY_TOKEN)" to Pal.bad
            RelayState.connected -> "● Conectada — esperando mensajes" to Pal.ok
            else -> "● Conectando…" to Pal.warn
        }
        tvStatus.text = t
        tvStatus.setTextColor(c)

        tvLast.text = if (RelayState.lastTs > 0) {
            "Último mensaje: ${RelayState.lastFrom} — ${RelayState.lastText}"
        } else {
            ""
        }

        tvPermBox.visibility = if (Settings.canDrawOverlays(this)) View.GONE else View.VISIBLE
    }

    private fun startRelay() {
        try {
            ContextCompat.startForegroundService(this, Intent(this, RelayService::class.java))
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo iniciar el servicio.", Toast.LENGTH_LONG).show()
        }
    }

    private fun sendTest() {
        try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, RelayService::class.java).setAction(RelayService.ACTION_TEST)
            )
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo mostrar la prueba.", Toast.LENGTH_LONG).show()
        }
    }

    private fun openOverlaySettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Esta TV no tiene esa pantalla. Usá el comando adb de arriba.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }
}
