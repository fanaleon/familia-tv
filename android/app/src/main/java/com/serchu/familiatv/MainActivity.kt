package com.serchu.familiatv

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Un solo APK:
 *  - en el móvil: perfil (foto, nombre, color), mensajes y control de la tele (bloqueo)
 *  - en la TV: pantalla de inicio (el servicio hace el trabajo de fondo)
 */
class MainActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private val match = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
    private val maxLen = 500
    private val reqPhoto = 1001
    private val reqAuth = 1002
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
    private lateinit var profileAvatarHolder: FrameLayout
    private lateinit var profileName: TextView
    private lateinit var input: EditText
    private lateinit var counter: TextView
    private lateinit var durationChip: TextView
    private lateinit var replyChip: TextView
    private lateinit var replyHint: TextView
    private lateinit var colorRow: LinearLayout
    private lateinit var sendBtn: TextView
    private lateinit var feedback: TextView
    private lateinit var controlIdle: LinearLayout
    private lateinit var controlActive: LinearLayout
    private lateinit var frozenCountdown: TextView
    private lateinit var frozenSub: TextView
    private var sending = false
    private var longDuration = false
    private var wantReply = false

    // bloqueo de la tele (lo que sabemos desde el móvil)
    private var frozenUntil = 0L // en SystemClock.elapsedRealtime()
    private var frozenLabel = ""
    private var frozenBy = ""
    private var frozenWhere = ""   // "2 de 3 TV" cuando se mira "Todas" y hay varias TV

    // varias TV: lista que informa el servidor y destino elegido (null = todas)
    private class TvInfo(val id: String, val name: String, val online: Boolean, val pending: Int, val freeze: JSONObject?)
    private var tvList: List<TvInfo> = emptyList()
    private var tvTarget: String? = null
    private lateinit var tvPicker: HorizontalScrollView
    private lateinit var tvPickerRow: LinearLayout
    private var sessionPin: String? = null // el PIN de padres, solo mientras la app está abierta
    private var pendingAuth: (() -> Unit)? = null

    // TV
    private lateinit var tvStatus: TextView
    private lateinit var tvDot: View
    private lateinit var tvDotBg: GradientDrawable
    private lateinit var tvLast: TextView
    private lateinit var tvPermBox: LinearLayout
    private var tvPulse: ObjectAnimator? = null
    private lateinit var tvRootFrame: FrameLayout
    private var tvHeader: List<View> = emptyList()
    private lateinit var planBtn: TextView
    private var tvTicks = 0

    // pantalla de pago (Solana Pay)
    private var payOverlay: FrameLayout? = null
    private var payQr: ImageView? = null
    private var payAmount: TextView? = null
    private var payStatus: TextView? = null
    private var payId: String? = null
    private var payExpiresAt = 0L // en SystemClock.elapsedRealtime()
    private var payDone = false
    private var payProduct = "plan"   // "plan" o "extra" (+3 TV)
    private var payMethod = "usdc"
    private var payWhat: TextView? = null
    private var planCache: JSONObject? = null

    private val phonePoll: Runnable = object : Runnable {
        override fun run() {
            fetchStatus()
            main.postDelayed(this, 8_000)
        }
    }

    private val phoneTick: Runnable = object : Runnable {
        override fun run() {
            if (frozenUntil != 0L) {
                if (frozenUntil <= SystemClock.elapsedRealtime()) {
                    frozenUntil = 0L
                    renderControl()
                } else {
                    updateCountdown()
                }
            }
            main.postDelayed(this, 1_000)
        }
    }

    private val payPoll: Runnable = object : Runnable {
        override fun run() {
            pollPay()
            main.postDelayed(this, 3_000)
        }
    }

    private val tvTick: Runnable = object : Runnable {
        override fun run() {
            refreshTv()
            if (tvTicks++ % 60 == 0) fetchPlan()
            main.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isTv = Device.isTv(this)
        if (isTv) {
            setContentView(buildTv())
            startService(RelayService::class.java)
        } else {
            setContentView(buildPhone())
            startService(PhoneService::class.java)
        }
        askNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        if (isTv) {
            tvTicks = 0
            main.post(tvTick)
        } else {
            main.post(phonePoll)
            main.post(phoneTick)
        }
    }

    override fun onPause() {
        super.onPause()
        sessionPin = null
        main.removeCallbacks(payPoll)
        main.removeCallbacks(tvTick)
        main.removeCallbacks(phonePoll)
        main.removeCallbacks(phoneTick)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (payOverlay != null) closePay() else super.onBackPressed()
    }

    override fun onDestroy() {
        tvPulse?.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ helpers

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

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    /** Tarjeta tipo vidrio. [tint] tiñe el borde. */
    private fun glassCard(padH: Int = 18, padV: Int = 16, tint: Int = 0): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glassBg(dp(24), dp(1), tint)
            setPadding(dp(padH), dp(padV), dp(padH), dp(padV))
        }

    /** Color elegido para los mensajes (azul, rosa, verde o amarillo). */
    private fun accentColor(): Int = Pal.byKey(Profile.color(this)) ?: Pal.accent

    /** Estilo de las "pastillas" de opciones: llena con el color elegido si está activa. */
    private fun styleChip(chip: TextView, selected: Boolean) {
        val accent = accentColor()
        if (selected) {
            chip.background = withRipple(gradientBg(dp(20), intArrayOf(accent, darken(accent, 0.3f))))
            chip.setTextColor(onColor(accent))
        } else {
            chip.background = withRipple(roundedBg(0x1FFFFFFF, dp(20), 0x2AFFFFFF, dp(1)))
            chip.setTextColor(Pal.text)
        }
    }

    private fun chip(text: String, sp: Float = 14f): TextView =
        TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(10))
            isClickable = true
            pressable()
        }

    /** Deja corriendo el servicio de fondo que corresponde (TV: recibe mensajes; móvil: recibe respuestas). */
    private fun startService(service: Class<*>) {
        if (!Cfg.configured) return
        try {
            ContextCompat.startForegroundService(this, Intent(this, service))
        } catch (e: Exception) {
            toast("No se pudo iniciar el servicio en segundo plano.")
        }
    }

    private fun askNotificationPermission() {
        if (!isTv && Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun formatLeft(ms: Long): String {
        val total = (ms + 999) / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%02d:%02d", m, s)
    }

    // ------------------------------------------------------------------ MÓVIL

    private fun buildPhone(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(30), dp(18), dp(28))
        }

        // Encabezado: logo + nombre
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val logo = TextView(this).apply {
            text = "📺"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            gravity = Gravity.CENTER
            background = gradientBg(dp(16), intArrayOf(0xFF9A8BFF.toInt(), 0xFF3A2AB5.toInt()))
        }
        header.addView(logo, LinearLayout.LayoutParams(dp(50), dp(50)))
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(label("Familia TV", 26f, Pal.text, bold = true), lp(wrap, wrap))
        titles.addView(label("Mensajes para la tele de casa", 13f, Pal.muted), lp(wrap, wrap))
        header.addView(titles, LinearLayout.LayoutParams(0, wrap, 1f).apply { marginStart = dp(14) })
        root.addView(header, lp(match, wrap))

        statusPill = label("●  Comprobando…", 13f, Pal.muted).apply {
            setPadding(dp(14), dp(7), dp(14), dp(7))
            maxLines = 1
            background = roundedBg(0x1FFFFFFF, dp(20), 0x2AFFFFFF, dp(1))
        }
        root.addView(statusPill, lp(wrap, wrap, top = 16))

        // Destino: solo aparece cuando hay 2 o más TV vinculadas
        tvTarget = Profile.tvTarget(this)
        tvPickerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        tvPicker = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            addView(tvPickerRow, FrameLayout.LayoutParams(wrap, wrap))
        }
        root.addView(tvPicker, lp(match, wrap, top = 10))

        if (!Cfg.configured) {
            root.addView(
                label("La app no está configurada: al compilar faltaron RELAY_URL y RELAY_TOKEN.", 14f, Pal.warn).apply {
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                    background = roundedBg(Pal.card, dp(16), Pal.warn, dp(1))
                },
                lp(match, wrap, top = 12)
            )
        }

        // Perfil: así te ven en la tele
        val profile = glassCard(padH = 16, padV = 12)
        profile.orientation = LinearLayout.HORIZONTAL
        profile.gravity = Gravity.CENTER_VERTICAL
        profileAvatarHolder = FrameLayout(this).apply {
            isClickable = true
            pressable()
            setOnClickListener { pickPhoto() }
        }
        profileName = label("", 19f, Pal.text, bold = true)
        val nameCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            setOnClickListener { editName() }
        }
        nameCol.addView(profileName, lp(wrap, wrap))
        nameCol.addView(label("Tocá la foto o el nombre para cambiarlos", 12f, Pal.muted), lp(wrap, wrap, top = 2))
        profile.addView(profileAvatarHolder, LinearLayout.LayoutParams(dp(64), dp(64)))
        profile.addView(nameCol, LinearLayout.LayoutParams(0, wrap, 1f).apply { marginStart = dp(14) })
        profile.addView(label("✎", 20f, Pal.muted))
        root.addView(profile, lp(match, wrap, top = 16))

        // Mensaje: texto, opciones y color
        val composer = glassCard(padH = 18, padV = 14)
        input = EditText(this).apply {
            hint = "Escribí tu mensaje…"
            setHintTextColor(Pal.muted)
            setTextColor(Pal.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(0, 0, 0, 0)
            minLines = 4
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
        composer.addView(input, lp(match, wrap))
        composer.addView(counter, lp(match, wrap))

        composer.addView(
            View(this).apply { setBackgroundColor(0x1FFFFFFF) },
            lp(match, dp(1), top = 8, bottom = 12)
        )

        durationChip = chip("").apply {
            setOnClickListener {
                longDuration = !longDuration
                renderOptions()
            }
        }
        replyChip = chip("").apply {
            setOnClickListener {
                wantReply = !wantReply
                renderOptions()
            }
        }
        val options = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        options.addView(durationChip, lp(wrap, wrap).apply { rightMargin = dp(8) })
        options.addView(replyChip, lp(wrap, wrap))
        composer.addView(options, lp(match, wrap))

        replyHint = label(
            "Con respuesta, el mensaje toma el control de la tele hasta que alguien elija OK, Voy o Ahora no.",
            12f,
            Pal.muted
        )
        composer.addView(replyHint, lp(match, wrap, top = 8))

        composer.addView(label("Color del mensaje", 12f, Pal.muted), lp(wrap, wrap, top = 14, bottom = 8))
        colorRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        composer.addView(colorRow, lp(match, wrap))
        root.addView(composer, lp(match, wrap, top = 14))

        // Frases rápidas
        root.addView(label("Frases rápidas", 12f, Pal.muted), lp(wrap, wrap, top = 18, bottom = 8))
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        quickPhrases.forEach { phrase ->
            val c = chip(phrase).apply {
                background = withRipple(roundedBg(0x1FFFFFFF, dp(20), 0x2AFFFFFF, dp(1)))
                setTextColor(Pal.text)
                setOnClickListener {
                    input.setText(phrase)
                    input.setSelection(input.text.length)
                    input.requestFocus()
                }
            }
            chips.addView(c, lp(wrap, wrap).apply { rightMargin = dp(8) })
        }
        root.addView(
            HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(chips)
            },
            lp(match, wrap)
        )

        // Botón de enviar
        sendBtn = label("", 18f, Color.WHITE, bold = true).apply {
            gravity = Gravity.CENTER
            isClickable = true
            pressable()
            setOnClickListener { send() }
        }
        root.addView(sendBtn, lp(match, dp(60), top = 20))

        feedback = label("", 14f, Pal.muted).apply {
            gravity = Gravity.CENTER
            alpha = 0f
        }
        root.addView(feedback, lp(match, wrap, top = 14))

        // Control de la tele
        root.addView(buildControlCard(), lp(match, wrap, top = 18))

        renderProfile()
        renderOptions()
        renderColors()
        renderControl()
        updateSendState()

        // Entrada suave: cada bloque aparece un poquito después del anterior
        for (i in 0 until root.childCount) {
            val v = root.getChildAt(i)
            if (v === feedback) continue
            v.alpha = 0f
            v.translationY = dp(18).toFloat()
            v.animate().alpha(1f).translationY(0f).setStartDelay(60L * i).setDuration(380).start()
        }

        return ScrollView(this).apply {
            isFillViewport = true
            background = appBackground()
            addView(root)
        }
    }

    private fun buildControlCard(): View {
        val card = glassCard(padH = 18, padV = 16, tint = 0xFF5B8CFF.toInt())
        card.addView(label("🌙  Control de la tele", 18f, Pal.text, bold = true), lp(wrap, wrap))
        card.addView(
            label("Bloqueá la tele cuando haya que descansar. Te pide huella, patrón o PIN.", 13f, Pal.muted),
            lp(match, wrap, top = 4, bottom = 14)
        )

        // Tele libre: opciones para bloquear
        controlIdle = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val c15 = chip("15 minutos").apply { setOnClickListener { askControl("freeze", "minutes", 15) } }
        val c60 = chip("1 hora").apply { setOnClickListener { askControl("freeze", "minutes", 60) } }
        styleControlChip(c15)
        styleControlChip(c60)
        top.addView(c15, LinearLayout.LayoutParams(0, wrap, 1f).apply { rightMargin = dp(8) })
        top.addView(c60, LinearLayout.LayoutParams(0, wrap, 1f))
        controlIdle.addView(top, lp(match, wrap))
        val night = chip("", 16f).apply {
            background = withRipple(gradientBg(dp(20), intArrayOf(0xFF5B8CFF.toInt(), 0xFF3A2AB5.toInt())))
            setTextColor(Color.WHITE)
        }
        fun refreshNight() {
            val (h, m) = Profile.untilTime(this)
            night.text = "🌙  Hasta las ${untilLabel(h, m)}"
            night.setOnClickListener { askControl("freeze", "until", 0, h, m) }
        }
        refreshNight()
        controlIdle.addView(night, lp(match, wrap, top = 8))
        val pick = chip("⏰  Elegir otra hora…", 14f)
        styleControlChip(pick)
        pick.setOnClickListener {
            val (h, m) = Profile.untilTime(this)
            android.app.TimePickerDialog(this, { _, hh, mm ->
                Profile.setUntilTime(this, hh, mm)
                refreshNight()
                askControl("freeze", "until", 0, hh, mm)
            }, h, m, true).apply {
                setTitle("La tele vuelve a las…")
            }.show()
        }
        controlIdle.addView(pick, lp(match, wrap, top = 8))
        card.addView(controlIdle, lp(match, wrap))

        // Tele bloqueada: cuenta regresiva y botón para desbloquear
        controlActive = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        controlActive.addView(label("La tele está bloqueada", 13f, Pal.muted), lp(wrap, wrap))
        frozenCountdown = label("", 44f, Pal.accentSoft, bold = true).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        controlActive.addView(frozenCountdown, lp(wrap, wrap, top = 2))
        frozenSub = label("", 13f, Pal.muted).apply { gravity = Gravity.CENTER }
        controlActive.addView(frozenSub, lp(match, wrap, top = 2))
        val unlock = chip("Desbloquear ahora", 16f).apply {
            setOnClickListener { askControl("unfreeze", "", 0) }
            background = withRipple(gradientBg(dp(20), intArrayOf(0xFF2ED573.toInt(), 0xFF00A86B.toInt())))
            setTextColor(0xFF08210F.toInt())
        }
        controlActive.addView(unlock, lp(match, wrap, top = 14))
        card.addView(controlActive, lp(match, wrap))
        return card
    }

    private fun untilLabel(h: Int, m: Int): String = "$h:${m.toString().padStart(2, '0')}"

    private fun styleControlChip(c: TextView) {
        c.background = withRipple(roundedBg(0x265B8CFF, dp(20), 0x665B8CFF, dp(1)))
        c.setTextColor(Pal.text)
    }

    private fun renderProfile() {
        val name = Profile.name(this)
        profileName.text = name
        profileAvatarHolder.removeAllViews()
        profileAvatarHolder.addView(
            avatarRing(name, Profile.avatar(this), 64, accentColor()),
            FrameLayout.LayoutParams(dp(64), dp(64))
        )
    }

    private fun renderOptions() {
        durationChip.text = if (longDuration) "⏱  Dura: larga" else "⏱  Dura: normal"
        styleChip(durationChip, longDuration && !wantReply)
        durationChip.alpha = if (wantReply) 0.4f else 1f

        replyChip.text = if (wantReply) "↩  Pedir respuesta: sí" else "↩  Pedir respuesta: no"
        styleChip(replyChip, wantReply)
        replyHint.visibility = if (wantReply) View.VISIBLE else View.GONE
    }

    private fun renderColors() {
        colorRow.removeAllViews()
        val selected = Profile.color(this)
        Pal.choices.forEach { (key, name, color) ->
            val on = key == selected
            val dot = TextView(this).apply {
                text = if (on) "✓" else ""
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setTextColor(onColor(color))
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                contentDescription = name
                isClickable = true
                scaleX = if (on) 1.08f else 0.92f
                scaleY = if (on) 1.08f else 0.92f
                val fill = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(lighten(color, 0.25f), color)
                )
                fill.shape = GradientDrawable.OVAL
                if (on) fill.setStroke(dp(3), Pal.text)
                background = fill
                pressable()
                setOnClickListener {
                    Profile.setColor(this@MainActivity, key)
                    renderColors()
                    renderProfile()
                    renderOptions()
                    updateSendState()
                }
            }
            colorRow.addView(dot, lp(dp(46), dp(46)).apply { rightMargin = dp(14) })
        }
    }

    private fun updateSendState() {
        counter.text = "${input.text.length}/$maxLen"
        val ready = input.text.toString().trim().isNotEmpty() && !sending
        val accent = accentColor()
        sendBtn.background = withRipple(gradientBg(dp(20), intArrayOf(accent, darken(accent, 0.35f))))
        sendBtn.setTextColor(onColor(accent))
        if (!sending) sendBtn.text = "Enviar a la tele  ➤"
        sendBtn.alpha = if (ready) 1f else 0.45f
    }

    private fun showFeedback(text: String, color: Int) {
        feedback.animate().cancel()
        feedback.text = text
        feedback.setTextColor(color)
        feedback.alpha = 1f
        feedback.animate().alpha(0f).setStartDelay(4_000).setDuration(600).start()
    }

    // ---- perfil: nombre y foto ----

    private fun editName() {
        val field = EditText(this).apply {
            setText(Profile.name(this@MainActivity))
            setSelection(text.length)
            filters = arrayOf<InputFilter>(InputFilter.LengthFilter(30))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        AlertDialog.Builder(this)
            .setTitle("Tu nombre en la tele")
            .setView(field)
            .setPositiveButton("Guardar") { _, _ ->
                val v = field.text.toString().trim()
                if (v.isNotEmpty()) {
                    Profile.setName(this, v)
                    renderProfile()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    @Suppress("DEPRECATION")
    private fun pickPhoto() {
        try {
            val pick = Intent(Intent.ACTION_GET_CONTENT)
                .setType("image/*")
                .addCategory(Intent.CATEGORY_OPENABLE)
            startActivityForResult(pick, reqPhoto)
        } catch (e: Exception) {
            toast("No se pudo abrir la galería.")
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqAuth) {
            val next = pendingAuth
            pendingAuth = null
            if (resultCode == RESULT_OK) next?.invoke()
            return
        }
        if (requestCode != reqPhoto || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val b64 = loadAvatar(uri)
        if (b64 == null) {
            toast("No se pudo usar esa foto.")
        } else {
            Profile.setAvatar(this, b64)
            renderProfile()
        }
    }

    /** Recorta la foto en cuadrado, la achica a 128 px y la devuelve en base64 (pesa unos 5 KB). */
    private fun loadAvatar(uri: Uri): String? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 256 && bounds.outHeight / (sample * 2) >= 256) sample *= 2

            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: return null
            val rotation = contentResolver.openInputStream(uri)?.use { exifRotation(it) } ?: 0

            val side = minOf(bmp.width, bmp.height)
            val matrix = Matrix().apply {
                postRotate(rotation.toFloat())
                postScale(128f / side, 128f / side)
            }
            val square = Bitmap.createBitmap(
                bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side, matrix, true
            )
            val out = ByteArrayOutputStream()
            square.compress(Bitmap.CompressFormat.JPEG, 80, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }

    private fun exifRotation(stream: InputStream): Int = try {
        when (ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    } catch (e: Exception) {
        0
    }

    // ---- enviar y consultar ----

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

        val askedReply = wantReply
        val json = JSONObject()
            .put("uid", Profile.uid(this))
            .put("from", Profile.name(this))
            .put("text", text)
            .put("duration", if (longDuration) "larga" else "normal")
            .put("color", Profile.color(this))
            .put("reply", askedReply)
        target()?.let { json.put("tv", it) }
        Profile.avatar(this)?.let { json.put("avatar", it) }
        val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

        Net.http.newCall(relayRequest("/send").post(body).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { sendDone(false, false, askedReply, "No hay conexión con el servidor. Probá de nuevo.") }
            }

            override fun onResponse(call: Call, response: Response) {
                val raw = response.use { it.body?.string().orEmpty() }
                val code = response.code
                val o = runCatching { JSONObject(raw) }.getOrNull()
                val delivered = o?.optBoolean("delivered", false) ?: false
                val total = o?.optInt("total", 1) ?: 1
                val entregadas = o?.optInt("entregadas", if (delivered) 1 else 0) ?: 0
                runOnUiThread {
                    when {
                        code in 200..299 -> sendDone(true, delivered, askedReply, null, total, entregadas)
                        code == 409 -> sendDone(false, false, askedReply, "Todavía no hay ninguna TV vinculada. Abrí Familia TV en la tele.")
                        code == 404 -> {
                            sendDone(false, false, askedReply, "Esa TV ya no está vinculada.")
                            fetchStatus()
                        }
                        code == 401 -> sendDone(false, false, askedReply, "El código de acceso no coincide con el del servidor.")
                        code == 402 -> sendDone(false, false, askedReply, "El plan venció. Activalo desde la tele (botón Plan).")
                        else -> sendDone(false, false, askedReply, "El servidor respondió con un error ($code).")
                    }
                }
            }
        })
    }

    private fun sendDone(ok: Boolean, delivered: Boolean, askedReply: Boolean, error: String?, total: Int = 1, entregadas: Int = 0) {
        sending = false
        if (ok) {
            input.setText("")
            wantReply = false // para no tomar el control de la tele sin querer en el próximo mensaje
            renderOptions()
            when {
                total > 1 && entregadas == total ->
                    showFeedback("Enviado a las $total TV." + if (askedReply) " Te aviso cuando respondan." else "", Pal.ok)
                total > 1 && entregadas > 0 ->
                    showFeedback("Enviado a $entregadas de $total TV. En las apagadas se muestra apenas se prendan.", Pal.warn)
                total > 1 -> showFeedback("Quedó en cola: se muestra apenas se prendan las TV.", Pal.warn)
                delivered && askedReply -> showFeedback("Enviado. Te aviso cuando respondan.", Pal.ok)
                delivered -> showFeedback("Enviado. La tele lo muestra ahora.", Pal.ok)
                else -> showFeedback("Quedó en cola: se muestra apenas se prenda la tele.", Pal.warn)
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
                        val arr = o.optJSONArray("tvs")
                        if (arr != null) {
                            tvList = (0 until arr.length()).mapNotNull { i ->
                                val t = arr.optJSONObject(i) ?: return@mapNotNull null
                                TvInfo(t.optString("id"), t.optString("name"), t.optBoolean("online"), t.optInt("pendientes"), t.optJSONObject("bloqueo"))
                            }
                            renderTvState()
                        } else {
                            renderStatus(if (o.optBoolean("tv")) 1 else 2, o.optInt("pendientes"))
                            applyFreeze(o.optJSONObject("bloqueo"))
                        }
                    } else {
                        renderStatus(3, 0)
                    }
                }
            }
        })
    }

    // ---- varias TV ----

    /** TV elegida como destino, o null = todas (también cuando la elegida ya no existe o hay una sola). */
    private fun target(): String? = tvTarget?.takeIf { id -> tvList.size >= 2 && tvList.any { it.id == id } }

    private fun selectedTv(): TvInfo? = target()?.let { id -> tvList.firstOrNull { it.id == id } }

    /** Redibuja el selector, el estado y la tarjeta de bloqueo con lo último que dijo el servidor. */
    private fun renderTvState() {
        renderPicker()
        val sel = selectedTv()
        val list = if (sel != null) listOf(sel) else tvList
        val online = list.count { it.online }
        val pending = list.sumOf { it.pending }
        val multi = tvList.size > 1
        val (t, c) = when {
            list.isEmpty() -> "●  Ninguna TV vinculada todavía" to Pal.warn
            online == list.size && list.size == 1 ->
                (if (multi) "●  ${list[0].name} conectada" else "●  TV conectada") to Pal.ok
            online == list.size -> "●  ${list.size} TV conectadas" to Pal.ok
            online == 0 -> {
                val who = if (list.size == 1) (if (multi) "${list[0].name} apagada" else "TV apagada") else "Las ${list.size} TV apagadas"
                (if (pending > 0) "●  $who · $pending en cola" else "●  $who o sin red") to Pal.warn
            }
            else -> "●  $online de ${list.size} TV conectadas" to Pal.warn
        }
        statusPill.text = t
        statusPill.setTextColor(c)

        // Bloqueo: el de la TV elegida; con "Todas", el primero que haya (y cuántas están bloqueadas)
        val frozen = list.filter { it.freeze != null }
        frozenWhere = if (sel == null && multi && frozen.isNotEmpty()) "${frozen.size} de ${list.size} TV" else ""
        applyFreeze(frozen.firstOrNull()?.freeze)
    }

    private fun renderPicker() {
        if (tvList.size < 2) {
            tvPicker.visibility = View.GONE
            return
        }
        tvPicker.visibility = View.VISIBLE
        tvPickerRow.removeAllViews()
        tvPickerRow.addView(label("Para:", 13f, Pal.muted), lp(wrap, wrap).apply { rightMargin = dp(10) })
        val selected = target()
        fun add(text: String, id: String?, tv: TvInfo?) {
            val c = chip(text, 13f)
            styleChip(c, id == selected)
            if (tv != null && !tv.online) c.alpha = 0.6f
            c.setOnClickListener {
                tvTarget = id
                Profile.setTvTarget(this, id)
                renderTvState()
            }
            if (tv != null) {
                c.setOnLongClickListener {
                    manageTv(tv)
                    true
                }
            }
            tvPickerRow.addView(c, lp(wrap, wrap).apply { rightMargin = dp(8) })
        }
        add("Todas", null, null)
        tvList.forEach { add(it.name, it.id, it) }
    }

    /** Mantener apretada una TV: cambiarle el nombre o quitarla (libera su lugar). */
    private fun manageTv(tv: TvInfo) {
        AlertDialog.Builder(this)
            .setTitle(tv.name)
            .setItems(arrayOf("Cambiar nombre", "Quitar esta TV")) { _, which ->
                if (which == 0) renameTv(tv) else forgetTv(tv)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun renameTv(tv: TvInfo) {
        val field = EditText(this).apply {
            setText(tv.name)
            setSelection(text.length)
            filters = arrayOf<InputFilter>(InputFilter.LengthFilter(24))
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle("Nombre de la TV")
            .setView(field)
            .setPositiveButton("Guardar") { _, _ ->
                val n = field.text.toString().trim()
                if (n.isNotEmpty()) postTv("/tv/rename", JSONObject().put("id", tv.id).put("name", n)) { code, _ ->
                    if (code in 200..299) fetchStatus() else showFeedback("No se pudo cambiar el nombre ($code).", Pal.bad)
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun forgetTv(tv: TvInfo) {
        AlertDialog.Builder(this)
            .setTitle("¿Quitar «${tv.name}»?")
            .setMessage("Se libera su lugar en el plan. Si esa TV vuelve a abrir la app, se vincula de nuevo.")
            .setPositiveButton("Quitar") { _, _ ->
                postTv("/tv/forget", JSONObject().put("id", tv.id)) { code, o ->
                    when {
                        code in 200..299 -> {
                            if (tvTarget == tv.id) {
                                tvTarget = null
                                Profile.setTvTarget(this, null)
                            }
                            fetchStatus()
                            showFeedback("TV quitada.", Pal.ok)
                        }
                        code == 409 -> showFeedback("Esa TV está prendida: apagala primero (si no, se vuelve a vincular sola).", Pal.warn)
                        else -> showFeedback("No se pudo quitar la TV ($code ${o?.optString("error").orEmpty()}).", Pal.bad)
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun postTv(path: String, json: JSONObject, done: (Int, JSONObject?) -> Unit) {
        val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        Net.http.newCall(relayRequest(path).post(body).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { showFeedback("No hay conexión con el servidor. Probá de nuevo.", Pal.bad) }
            }

            override fun onResponse(call: Call, response: Response) {
                val raw = response.use { it.body?.string().orEmpty() }
                val code = response.code
                val o = runCatching { JSONObject(raw) }.getOrNull()
                runOnUiThread { done(code, o) }
            }
        })
    }

    /** state: 0 comprobando, 1 TV conectada, 2 TV apagada, 3 sin servidor */
    private fun renderStatus(state: Int, pending: Int) {
        val (t, c) = when (state) {
            1 -> "●  TV conectada" to Pal.ok
            2 -> (if (pending > 0) "●  TV apagada · $pending en cola" else "●  TV apagada o sin red") to Pal.warn
            3 -> "●  Sin conexión con el servidor" to Pal.bad
            else -> "●  Comprobando…" to Pal.muted
        }
        statusPill.text = t
        statusPill.setTextColor(c)
    }

    // ---- control de la tele (bloqueo) ----

    /** Pide que el que está tocando sea el dueño del móvil: huella, cara, patrón o PIN del teléfono. */
    @Suppress("DEPRECATION")
    private fun authenticate(onOk: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 30) {
            val allowed = BiometricManager.Authenticators.BIOMETRIC_WEAK or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
            val bm = getSystemService(BiometricManager::class.java)
            if (bm != null && bm.canAuthenticate(allowed) == BiometricManager.BIOMETRIC_SUCCESS) {
                val prompt = BiometricPrompt.Builder(this)
                    .setTitle("Confirmá que sos vos")
                    .setSubtitle("Huella, cara, patrón o PIN de tu móvil")
                    .setAllowedAuthenticators(allowed)
                    .build()
                prompt.authenticate(
                    CancellationSignal(),
                    mainExecutor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                            onOk()
                        }
                    }
                )
                return
            }
            onOk() // el móvil no tiene bloqueo de pantalla: se sigue (el PIN del servidor, si hay, igual protege)
            return
        }
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val intent = if (km.isDeviceSecure) {
            km.createConfirmDeviceCredentialIntent("Confirmá que sos vos", "Para bloquear o desbloquear la tele")
        } else {
            null
        }
        if (intent == null) {
            onOk()
            return
        }
        pendingAuth = onOk
        startActivityForResult(intent, reqAuth)
    }

    /** Primero se confirma la identidad en el móvil; recién ahí se manda la orden. */
    private fun askControl(action: String, mode: String, minutes: Int, hour: Int = 7, minute: Int = 0) {
        if (!Cfg.configured) {
            showFeedback("Falta configurar RELAY_URL y RELAY_TOKEN.", Pal.bad)
            return
        }
        authenticate { sendControl(action, mode, minutes, sessionPin, hour, minute) }
    }

    private fun sendControl(action: String, mode: String, minutes: Int, pin: String?, hour: Int = 7, minute: Int = 0) {
        val json = JSONObject()
            .put("action", action)
            .put("from", Profile.name(this))
        target()?.let { json.put("tv", it) }
        if (action == "freeze") {
            json.put("mode", mode)
            if (mode == "until") json.put("hour", hour).put("minute", minute) else json.put("minutes", minutes)
        }
        if (pin != null) json.put("pin", pin)
        val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

        Net.http.newCall(relayRequest("/control").post(body).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { showFeedback("No hay conexión con el servidor. Probá de nuevo.", Pal.bad) }
            }

            override fun onResponse(call: Call, response: Response) {
                val raw = response.use { it.body?.string().orEmpty() }
                val code = response.code
                val o = runCatching { JSONObject(raw) }.getOrNull()
                runOnUiThread { controlDone(code, o, action, mode, minutes, pin, hour, minute) }
            }
        })
    }

    private fun controlDone(code: Int, o: JSONObject?, action: String, mode: String, minutes: Int, pin: String?, hour: Int = 7, minute: Int = 0) {
        val error = o?.optString("error").orEmpty()
        when {
            code in 200..299 -> {
                if (pin != null) sessionPin = pin
                val delivered = o?.optBoolean("delivered", false) ?: false
                val total = o?.optInt("total", 1) ?: 1
                val entregadas = o?.optInt("entregadas", if (delivered) 1 else 0) ?: 0
                val who = if (total > 1) "las $total TV" else selectedTv()?.let { "«${it.name}»" } ?: "la tele"
                if (action == "unfreeze") {
                    showFeedback(
                        when {
                            delivered -> "${who.replaceFirstChar { it.uppercase() }} ${if (total > 1) "desbloqueadas" else "desbloqueada"}."
                            total > 1 -> "Desbloqueo anotado: ${total - entregadas} de $total TV están apagadas."
                            else -> "Desbloqueo anotado: la tele está apagada."
                        },
                        Pal.ok
                    )
                } else {
                    showFeedback(
                        when {
                            delivered -> "${who.replaceFirstChar { it.uppercase() }} ${if (total > 1) "bloqueadas" else "bloqueada"}."
                            total > 1 -> "Bloqueadas $entregadas de $total TV. Las apagadas se bloquean cuando se prendan."
                            else -> "Anotado. La tele está apagada: se bloquea cuando se prenda."
                        },
                        if (delivered) Pal.ok else Pal.warn
                    )
                }
                fetchStatus() // el estado por TV lo manda el servidor
            }
            code == 409 -> showFeedback("Todavía no hay ninguna TV vinculada. Abrí Familia TV en la tele.", Pal.bad)
            code == 404 -> {
                showFeedback("Esa TV ya no está vinculada.", Pal.bad)
                fetchStatus()
            }
            code == 401 -> showFeedback("El código de acceso no coincide con el del servidor.", Pal.bad)
            code == 402 -> showFeedback("El plan venció. Activalo desde la tele (botón Plan).", Pal.bad)
            code == 403 && (error == "pin_requerido" || error == "pin_incorrecto") -> {
                sessionPin = null
                askPin(if (error == "pin_incorrecto") "PIN incorrecto. Probá de nuevo." else "Escribí el PIN de padres.") { p ->
                    sendControl(action, mode, minutes, p, hour, minute)
                }
            }
            code == 429 -> {
                val mins = ((o?.optInt("reintentar_en") ?: 600) + 59) / 60
                showFeedback("Demasiados intentos. Probá de nuevo en $mins min.", Pal.bad)
            }
            else -> showFeedback("No se pudo completar ($code).", Pal.bad)
        }
    }

    private fun askPin(message: String, onPin: (String) -> Unit) {
        val field = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf<InputFilter>(InputFilter.LengthFilter(8))
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        }
        AlertDialog.Builder(this)
            .setTitle("PIN de padres")
            .setMessage(message)
            .setView(field)
            .setPositiveButton("Confirmar") { _, _ ->
                val p = field.text.toString().trim()
                if (p.isNotEmpty()) onPin(p)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Guarda lo que el servidor dice del bloqueo (null = libre) y actualiza la tarjeta. */
    private fun applyFreeze(b: JSONObject?) {
        if (b == null) {
            frozenUntil = 0L
        } else {
            val left = b.optLong("hasta") - b.optLong("ahora")
            frozenUntil = if (left > 0L) SystemClock.elapsedRealtime() + left else 0L
            frozenLabel = b.optString("label")
            frozenBy = b.optString("by")
        }
        renderControl()
    }

    private fun renderControl() {
        val frozen = frozenUntil > SystemClock.elapsedRealtime()
        controlIdle.visibility = if (frozen) View.GONE else View.VISIBLE
        controlActive.visibility = if (frozen) View.VISIBLE else View.GONE
        if (frozen) {
            frozenSub.text = listOf(frozenLabel, if (frozenBy.isNotBlank()) "por $frozenBy" else "", frozenWhere)
                .filter { it.isNotBlank() }.joinToString(" · ")
            updateCountdown()
        }
    }

    private fun updateCountdown() {
        frozenCountdown.text = formatLeft(frozenUntil - SystemClock.elapsedRealtime())
    }

    // ------------------------------------------------------------------ TV

    private fun buildTv(): View {
        val rootFrame = FrameLayout(this).apply { background = appBackground() }
        tvRootFrame = rootFrame

        // Centro: logo, nombre y estado. Compacto para que entre en cualquier tele.
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(64), dp(20), dp(64), dp(170)) // abajo queda lugar para los botones
        }

        val logo = TextView(this).apply {
            text = "\uD83D\uDCFA"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 44f)
            gravity = Gravity.CENTER
            background = gradientBg(dp(26), intArrayOf(0xFF9A8BFF.toInt(), 0xFF3A2AB5.toInt()))
        }
        root.addView(logo, LinearLayout.LayoutParams(dp(84), dp(84)))

        val titleView = label("Familia TV", 42f, Pal.text, bold = true)
        val subtitleView = label("Los mensajes de la familia aparecen directo en tu pantalla", 20f, Pal.muted)
        root.addView(titleView, lp(wrap, wrap, top = 12))
        root.addView(subtitleView, lp(wrap, wrap, top = 2))
        // Si falta el permiso, el aviso necesita lugar: el logo y la frase se esconden hasta que se dé
        tvHeader = listOf(logo, subtitleView)

        // Estado de la conexión, con un puntito que late
        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = glassBg(dp(32), dp(1))
            setPadding(dp(26), dp(10), dp(30), dp(10))
        }
        tvDotBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Pal.muted)
        }
        tvDot = View(this).apply { background = tvDotBg }
        statusCard.addView(tvDot, LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginEnd = dp(14) })
        tvStatus = label("", 24f, Pal.text)
        statusCard.addView(tvStatus, LinearLayout.LayoutParams(wrap, wrap))
        root.addView(statusCard, lp(wrap, wrap, top = 22))
        tvPulse = ObjectAnimator.ofFloat(tvDot, "alpha", 1f, 0.3f).apply {
            duration = 1_100
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }

        tvLast = label("", 18f, Pal.muted).apply {
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        root.addView(tvLast, lp(wrap, wrap, top = 10))

        tvPermBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(Pal.card, dp(20), Pal.warn, dp(2))
            setPadding(dp(24), dp(12), dp(24), dp(12))
            visibility = View.GONE
        }
        tvPermBox.addView(
            label("Falta un permiso para mostrar los mensajes sobre lo que estás viendo.", 18f, Pal.warn, bold = true),
            lp(wrap, wrap)
        )
        tvPermBox.addView(
            label("Tocá el botón y activá \"Mostrar sobre otras apps\" para Familia TV.", 15f, Pal.text),
            lp(wrap, wrap, top = 4)
        )
        tvPermBox.addView(
            label("Con una PC: adb shell appops set $packageName SYSTEM_ALERT_WINDOW allow", 12f, Pal.muted).apply {
                typeface = Typeface.MONOSPACE
            },
            lp(wrap, wrap, top = 2)
        )
        tvPermBox.addView(tvButton("Abrir ajustes del permiso") { openOverlaySettings() }, lp(wrap, wrap, top = 8))
        root.addView(tvPermBox, lp(wrap, wrap, top = 10))

        rootFrame.addView(root, FrameLayout.LayoutParams(match, match))

        // Botones: anclados abajo, aparte del resto para que nada los achique
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val soundBtn = tvButton("") { }
        soundBtn.text = soundButtonText()
        soundBtn.setOnClickListener {
            Sounds.next(this)
            soundBtn.text = soundButtonText()
            Sounds.play(this) // se escucha cómo suena el que quedó elegido
        }
        planBtn = tvButton("\uD83D\uDCB3  Plan") { openPay() }
        val extras = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        extras.addView(soundBtn, lp(wrap, wrap).apply { rightMargin = dp(14) })
        extras.addView(planBtn, lp(wrap, wrap))
        bottom.addView(extras, lp(wrap, wrap, bottom = 14))

        val tests = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val test = tvButton("Probar mensaje") { sendTest(RelayService.ACTION_TEST) }
        val testReply = tvButton("Probar con respuesta") { sendTest(RelayService.ACTION_TEST_REPLY) }
        val testFreeze = tvButton("Probar bloqueo (45 s)") { sendTest(RelayService.ACTION_TEST_FREEZE) }
        tests.addView(test, lp(wrap, wrap).apply { rightMargin = dp(14) })
        tests.addView(testReply, lp(wrap, wrap).apply { rightMargin = dp(14) })
        tests.addView(testFreeze, lp(wrap, wrap))
        bottom.addView(tests, lp(wrap, wrap))
        rootFrame.addView(
            bottom,
            FrameLayout.LayoutParams(wrap, wrap, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = dp(40)
            }
        )
        test.requestFocus()

        // Reloj y fecha, arriba a la derecha
        val clockBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        clockBox.addView(
            TextClock(this).apply {
                format12Hour = "HH:mm"
                format24Hour = "HH:mm"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 38f)
                setTextColor(Pal.text)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.END
            },
            LinearLayout.LayoutParams(wrap, wrap)
        )
        clockBox.addView(
            TextClock(this).apply {
                format12Hour = "EEEE d 'de' MMMM"
                format24Hour = "EEEE d 'de' MMMM"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setTextColor(Pal.muted)
                gravity = Gravity.END
            },
            LinearLayout.LayoutParams(wrap, wrap)
        )
        rootFrame.addView(
            clockBox,
            FrameLayout.LayoutParams(wrap, wrap, Gravity.TOP or Gravity.END).apply {
                topMargin = dp(28)
                rightMargin = dp(56)
            }
        )

        return rootFrame
    }

    // ---------------------------------------------------------- plan y pago (TV)

    /** Pregunta al servidor cómo está el plan y lo muestra en el botón. */
    private fun fetchPlan() {
        if (!Cfg.configured) return
        Net.http.newCall(relayRequest("/pay/status").build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}

            override fun onResponse(call: Call, response: Response) {
                val raw = response.use { it.body?.string().orEmpty() }
                val plan = runCatching { JSONObject(raw).optJSONObject("plan") }.getOrNull() ?: return
                runOnUiThread { showPlan(plan) }
            }
        })
    }

    private fun showPlan(plan: JSONObject) {
        val hasta = if (plan.isNull("hasta")) 0L else plan.optLong("hasta", 0L)
        val fecha = if (hasta > 0) SimpleDateFormat("d/M", Locale("es")).format(Date(hasta)) else ""
        val usd = plan.optInt("precio_usd", 5)
        val days = if (hasta > 0) ((hasta - System.currentTimeMillis()) / 86_400_000L).toInt() + 1 else 0
        planCache = plan
        val cap = plan.optInt("capacidad", 0)
        val tvsTxt = if (cap > 0) " · ${plan.optInt("tvs_usadas")}/$cap TV" else ""
        planBtn.text = when {
            RelayState.limit -> "\uD83D\uDCB3  Sumar TV (+${plan.optInt("tvs_por_grupo", 3)} por ${plan.optInt("precio_extra_usd", 8)} USD)"
            plan.optString("estado") == "activo" -> "\uD83D\uDCB3  Plan activo hasta $fecha$tvsTxt"
            plan.optString("estado") == "prueba" -> "\uD83D\uDCB3  Prueba: $days ${if (days == 1) "día" else "días"}$tvsTxt"
            plan.optString("estado") == "vencido" -> "\uD83D\uDCB3  Plan vencido: activar"
            else -> "\uD83D\uDCB3  Plan ($usd USDC)"
        }
    }

    private fun qrDrawable(text: String): BitmapDrawable {
        val hints = mapOf<EncodeHintType, Any>(
            EncodeHintType.MARGIN to 1,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M
        )
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
        val w = m.width
        val h = m.height
        val px = IntArray(w * h) { i -> if (m.get(i % w, i / w)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        val bmp = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        return BitmapDrawable(resources, bmp).apply { isFilterBitmap = false } // módulos nítidos al agrandar
    }

    private fun payButton(text: String, onClick: () -> Unit): TextView =
        tvButton(text, onClick = onClick).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }

    private fun openPay() {
        if (payOverlay != null) return
        if (!Cfg.configured) {
            toast("Falta configurar RELAY_URL y RELAY_TOKEN.")
            return
        }
        payDone = false
        payProduct = if (RelayState.limit) "extra" else "plan"
        payMethod = "usdc"
        val overlay = FrameLayout(this).apply {
            background = appBackground()
            isClickable = true
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(56), dp(24), dp(56), dp(24))
        }

        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(label("\uD83D\uDCB3  Tu plan", 34f, Pal.text, bold = true), lp(wrap, wrap))
        left.addView(
            label("Escaneá el QR con tu billetera de Solana (Phantom, Solflare…)", 18f, Pal.muted),
            lp(wrap, wrap, top = 6)
        )
        val plan = planCache
        val base = plan?.optInt("tvs_incluidas", 3) ?: 3
        val pack = plan?.optInt("tvs_por_grupo", 3) ?: 3
        val usdPlan = plan?.optInt("precio_usd", 5) ?: 5
        val usdExtra = plan?.optInt("precio_extra_usd", 8) ?: 8

        // Qué se compra: el plan (hasta $base TV) o un grupo de TV más
        val what = label("", 20f, Pal.text, bold = true)
        left.addView(what, lp(wrap, wrap, top = 12))
        val products = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val planBtn2 = payButton("Plan: hasta $base TV · $usdPlan USD") { payProduct = "plan"; createPay(payMethod) }
        val extraBtn = payButton("+$pack TV · $usdExtra USD") { payProduct = "extra"; createPay(payMethod) }
        products.addView(planBtn2, lp(wrap, wrap).apply { rightMargin = dp(12) })
        products.addView(extraBtn, lp(wrap, wrap))
        left.addView(products, lp(wrap, wrap, top = 8))

        val amount = label("", 30f, Pal.accentSoft, bold = true)
        left.addView(amount, lp(wrap, wrap, top = 12))
        val status = label("Preparando el pago…", 20f, Pal.text).apply { maxLines = 6 }
        left.addView(status, lp(wrap, wrap, top = 6))

        val methods = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val usdcBtn = payButton("Pagar USDC") { createPay("usdc") }
        val solBtn = payButton("Pagar SOL") { createPay("sol") }
        methods.addView(usdcBtn, lp(wrap, wrap).apply { rightMargin = dp(12) })
        methods.addView(solBtn, lp(wrap, wrap))
        methods.addView(payButton("Cerrar") { closePay() }, lp(wrap, wrap).apply { marginStart = dp(12) })
        left.addView(methods, lp(wrap, wrap, top = 12))
        row.addView(left, LinearLayout.LayoutParams(0, wrap, 1f))

        // El QR va sobre blanco, grande y con margen, para que cualquier cámara lo lea
        val qrSize = minOf(dp(300), resources.displayMetrics.heightPixels - dp(120))
        val card = FrameLayout(this).apply {
            background = roundedBg(0xFFFFFFFF.toInt(), dp(20))
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        val qr = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        card.addView(qr, FrameLayout.LayoutParams(qrSize, qrSize))
        row.addView(card, LinearLayout.LayoutParams(wrap, wrap).apply { marginStart = dp(32) })

        overlay.addView(row, FrameLayout.LayoutParams(match, match))
        // Lo de atrás se oculta para que el control remoto no pueda mover el foco hasta allá
        for (i in 0 until tvRootFrame.childCount) tvRootFrame.getChildAt(i).visibility = View.INVISIBLE
        tvRootFrame.addView(overlay, FrameLayout.LayoutParams(match, match))
        payOverlay = overlay
        payQr = qr
        payAmount = amount
        payStatus = status
        payWhat = what
        usdcBtn.requestFocus()
        createPay("usdc")
    }

    private fun closePay() {
        main.removeCallbacks(payPoll)
        payOverlay?.let { tvRootFrame.removeView(it) }
        for (i in 0 until tvRootFrame.childCount) tvRootFrame.getChildAt(i).visibility = View.VISIBLE
        payOverlay = null
        payQr = null
        payAmount = null
        payStatus = null
        payWhat = null
        payId = null
        planBtn.requestFocus()
        fetchPlan()
    }

    /** Pide un QR nuevo al servidor para pagar con [metodo] ("usdc" o "sol"). */
    private fun createPay(metodo: String) {
        main.removeCallbacks(payPoll)
        payMethod = metodo
        val p = planCache
        payWhat?.text = if (payProduct == "extra") {
            "Sumar ${p?.optInt("tvs_por_grupo", 3) ?: 3} TV más (hoy ${p?.optInt("tvs_usadas") ?: 0} de ${p?.optInt("capacidad") ?: 3})"
        } else {
            "Plan de ${p?.optInt("dias", 30) ?: 30} días · hasta ${p?.optInt("tvs_incluidas", 3) ?: 3} TV"
        }
        payId = null
        payDone = false
        payQr?.setImageDrawable(null)
        payAmount?.text = ""
        payStatus?.setTextColor(Pal.text)
        payStatus?.text = "Preparando el pago…"
        val body = JSONObject().put("metodo", metodo).put("producto", payProduct).toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        Net.http.newCall(relayRequest("/pay/create").post(body).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { payMessage("No hay conexión con el servidor. Probá de nuevo.", Pal.bad) }
            }

            override fun onResponse(call: Call, response: Response) {
                val raw = response.use { it.body?.string().orEmpty() }
                val code = response.code
                val o = runCatching { JSONObject(raw) }.getOrNull()
                runOnUiThread {
                    val pago = o?.optJSONObject("pago")
                    if (code in 200..299 && pago != null) {
                        showPayRequest(pago)
                    } else {
                        when (o?.optString("error")) {
                            "precio_no_disponible" -> payMessage("No pude averiguar el precio de SOL. Probá con USDC.", Pal.bad)
                            "pagos_desactivados" -> payMessage("Los pagos todavía no están configurados.", Pal.bad)
                            else -> payMessage("No se pudo generar el pago ($code).", Pal.bad)
                        }
                    }
                }
            }
        })
    }

    private fun payMessage(text: String, color: Int) {
        payStatus?.setTextColor(color)
        payStatus?.text = text
    }

    private fun showPayRequest(pago: JSONObject) {
        if (payOverlay == null) return
        payId = pago.optString("id")
        payExpiresAt = SystemClock.elapsedRealtime() + pago.optLong("vence_en", 0L)
        payQr?.setImageDrawable(qrDrawable(pago.optString("url")))
        val moneda = pago.optString("moneda")
        payAmount?.text = "Pagá ${pago.optString("monto")} $moneda"
        payMessage("Esperando el pago…", Pal.text)
        main.postDelayed(payPoll, 3_000)
    }

    private fun pollPay() {
        val id = payId ?: return
        if (payOverlay == null || payDone) return
        Net.http.newCall(relayRequest("/pay/status?id=$id").build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}

            override fun onResponse(call: Call, response: Response) {
                val raw = response.use { it.body?.string().orEmpty() }
                val o = runCatching { JSONObject(raw) }.getOrNull() ?: return
                runOnUiThread { onPayStatus(id, o) }
            }
        })
    }

    private fun onPayStatus(id: String, o: JSONObject) {
        if (payOverlay == null || id != payId || payDone) return
        o.optJSONObject("plan")?.let { showPlan(it) }
        when (o.optJSONObject("pago")?.optString("estado")) {
            "pagado" -> {
                payDone = true
                main.removeCallbacks(payPoll)
                val plan = o.optJSONObject("plan")
                val hasta = plan?.optLong("hasta", 0L) ?: 0L
                val fecha = if (hasta > 0) SimpleDateFormat("d 'de' MMMM", Locale("es")).format(Date(hasta)) else ""
                payQr?.setImageDrawable(null)
                payAmount?.text = "✅  ¡Pago recibido!"
                val recibo = o.optJSONObject("pago")?.optJSONObject("recibo")?.optString("texto").orEmpty()
                if (recibo.isNotBlank()) {
                    payMessage(recibo, Pal.ok)
                } else if (o.optJSONObject("pago")?.optString("producto") == "extra") {
                    payMessage("Gracias. Ya podés vincular hasta ${plan?.optInt("capacidad") ?: 0} TV: prendé la nueva y abrí Familia TV.", Pal.ok)
                } else {
                    payMessage("Gracias. Tu plan está activo hasta el $fecha.", Pal.ok)
                }
            }
            "vencido" -> {
                main.removeCallbacks(payPoll)
                payQr?.setImageDrawable(null)
                payMessage("Este QR venció. Tocá USDC o SOL para generar otro.", Pal.warn)
            }
            else -> {
                val left = ((payExpiresAt - SystemClock.elapsedRealtime()) / 1000).coerceAtLeast(0)
                payStatus?.text = "Esperando el pago… (el QR vence en ${left / 60}:${(left % 60).toString().padStart(2, '0')})"
            }
        }
    }

    private fun soundButtonText(): String = "\uD83D\uDD14  Sonido: ${Sounds.label(this)}"

    private fun refreshTv() {
        val (t, c) = when {
            !Cfg.configured -> "App sin configurar (faltan RELAY_URL y RELAY_TOKEN)" to Pal.bad
            RelayState.limit -> "Esta TV no entra: el plan permite ${RelayState.capacity} TV. Tocá el botón de abajo para sumar más." to Pal.warn
            RelayState.connected && RelayState.tvName.isNotBlank() -> "Conectada como «${RelayState.tvName}» — esperando mensajes" to Pal.ok
            RelayState.connected -> "Conectada — esperando mensajes" to Pal.ok
            else -> "Conectando…" to Pal.warn
        }
        tvStatus.text = t
        tvDotBg.setColor(c)

        tvLast.text = if (RelayState.lastTs > 0) {
            "Último mensaje: ${RelayState.lastFrom} — ${RelayState.lastText}"
        } else {
            ""
        }

        val missing = !Settings.canDrawOverlays(this)
        tvPermBox.visibility = if (missing) View.VISIBLE else View.GONE
        for (v in tvHeader) v.visibility = if (missing) View.GONE else View.VISIBLE
        tvLast.visibility = if (missing) View.GONE else View.VISIBLE
    }

    private fun sendTest(action: String) {
        try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, RelayService::class.java).setAction(action)
            )
        } catch (e: Exception) {
            toast("No se pudo mostrar la prueba.")
        }
    }

    private fun openOverlaySettings() {
        val attempts = listOf(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        )
        for (intent in attempts) {
            try {
                startActivity(intent)
                return
            } catch (e: Exception) { /* se prueba con la siguiente pantalla */ }
        }
        toast("Esta TV no tiene esa pantalla. Usá el comando adb de arriba.")
    }
}
