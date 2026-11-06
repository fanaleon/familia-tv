package com.serchu.familiatv

import android.animation.ValueAnimator
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.graphics.ColorUtils
import kotlin.math.PI
import kotlin.math.sin

/**
 * Un mensaje que llegó a la TV.
 * [uid] es quien lo mandó (para devolverle la respuesta); vacío en los mensajes de prueba.
 * [longStay] pide que dure más en pantalla; [needsReply] muestra los botones de respuesta;
 * [urgent] ocupa toda la pantalla, repite el sonido y no se va hasta que alguien responda.
 */
class Msg(
    val id: String,
    val uid: String,
    val from: String,
    val text: String,
    val ts: Long,
    val avatar: String? = null,
    val longStay: Boolean = false,
    val needsReply: Boolean = false,
    val color: String = "",
    val urgent: Boolean = false
)

object Notifs {
    const val CH_SERVICE = "relay"
    const val CH_MSG = "mensajes"

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, T("Conexión con la familia"), NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_MSG, T("Mensajes"), NotificationManager.IMPORTANCE_HIGH)
        )
    }

    /** Notificación común con sonido; al tocarla abre la app. */
    fun post(ctx: Context, notifId: Int, title: String, text: String, timeoutMs: Long = 0L) {
        ensureChannels(ctx)
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, CH_MSG)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .apply { if (timeoutMs > 0) setTimeoutAfter(timeoutMs) } // desaparece sola
            .build()
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(notifId, n)
    }
}

/** Velo oscuro sobre lo que se esté mirando y una luz suave del color de quien escribe detrás de la tarjeta. */
private class AuraScrim(context: Context, private val color: Int, private val dim: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        paint.shader = RadialGradient(
            w / 2f, h / 2f, maxOf(w, h) * 0.6f,
            intArrayOf(
                ColorUtils.setAlphaComponent(color, 0x66),
                ColorUtils.setAlphaComponent(color, 0x30),
                ColorUtils.setAlphaComponent(color, 0x0C),
                ColorUtils.setAlphaComponent(color, 0x00)
            ),
            floatArrayOf(0f, 0.35f, 0.7f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (dim != 0) canvas.drawColor(dim)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }
}

/** Ventana que además de dibujar atiende el botón Atrás del control cuando tiene el foco. */
private class KeyFrame(
    context: Context,
    private val onBack: () -> Unit,
    private val onConfirm: (() -> Unit)? = null // si se indica, OK / Enter del control también lo atienden
) : FrameLayout(context) {
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) onBack()
            return true
        }
        val confirm = onConfirm
        if (confirm != null && (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER ||
                event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER || event.keyCode == KeyEvent.KEYCODE_BUTTON_A)) {
            if (event.action == KeyEvent.ACTION_UP) confirm()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}

/**
 * Muestra los mensajes como una tarjeta arriba de la pantalla, encima de lo que se esté viendo.
 *  - Mensajes comunes: no toman el foco ni responden al control, no interrumpen.
 *  - Mensajes que piden respuesta: toman el foco del control y muestran OK / Voy / Ahora no.
 * Si no hay permiso de "mostrar sobre otras apps" cae a una notificación común.
 * Todos los métodos se llaman desde el hilo principal.
 */
class OverlayPresenter(
    private val ctx: Context,
    private val onReply: (Msg, String) -> Unit,
    private val onSeen: (Msg) -> Unit = {}
) {

    /** [intro] arranca las animaciones de entrada; [stop] frena las que se repiten (aura, ondas). */
    private class Built(
        val root: View,
        val bar: View,
        val buttons: LinearLayout?,
        val first: View?,
        val intro: Runnable,
        val stop: Runnable,
        val aura: View? = null
    )

    private val choices get() = listOf("ok" to "OK", "voy" to T("Voy"), "cinco_min" to T("5 minutos"), "ahora_no" to T("Ahora no"))

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<Msg>()
    private var current: Built? = null
    private var currentMsg: Msg? = null
    private var answered = false
    private var dismissing = false

    // Se acabó el tiempo: si pedía respuesta y nadie contestó, se le avisa a quien lo mandó
    private val autoHide = Runnable {
        val m = currentMsg
        if (m != null && m.needsReply && !answered) answer("sin_respuesta", null) else dismiss()
    }
    private val afterSent = Runnable { dismiss() }

    // Mientras un mensaje que pide respuesta siga sin contestar, repite el aviso sonoro
    private val repeatChime: Runnable = object : Runnable {
        override fun run() {
            chime(currentMsg?.urgent == true)
            main.postDelayed(this, if (currentMsg?.urgent == true) 10_000 else 20_000)
        }
    }

    fun show(m: Msg) {
        if (!Settings.canDrawOverlays(ctx)) {
            notifyFallback(m)
            return
        }
        queue.addLast(m)
        if (current == null) next()
    }

    fun clear() {
        main.removeCallbacks(autoHide)
        main.removeCallbacks(afterSent)
        main.removeCallbacks(repeatChime)
        queue.clear()
        current?.let {
            it.stop.run()
            try { wm.removeView(it.root) } catch (e: Exception) { /* ya no estaba */ }
            it.aura?.let { a -> try { wm.removeView(a) } catch (e: Exception) { /* ya no estaba */ } }
        }
        current = null
        currentMsg = null
        dismissing = false
    }

    private fun stayMillis(m: Msg): Long = when {
        m.urgent -> 290_000L
        m.needsReply -> 180_000L
        m.longStay -> (45_000L + m.text.length * 120L).coerceIn(45_000L, 120_000L)
        else -> (15_000L + m.text.length * 80L).coerceIn(15_000L, 45_000L)
    }

    private fun next() {
        val m = queue.removeFirstOrNull() ?: return
        val duration = stayMillis(m)
        val built = if (m.urgent) buildUrgent(m) else buildCard(m)
        try {
            built.aura?.let { wm.addView(it, auraParams()) }
            wm.addView(built.root, if (m.urgent) urgentParams() else params(m.needsReply))
        } catch (e: Exception) {
            // Sin permiso o sin ventana disponible: que al menos llegue como notificación
            built.aura?.let { a -> try { wm.removeView(a) } catch (x: Exception) { /* no llegó a agregarse */ } }
            notifyFallback(m)
            next()
            return
        }
        current = built
        currentMsg = m
        answered = false
        chime(m.urgent)
        if (m.needsReply) main.postDelayed(repeatChime, if (m.urgent) 10_000 else 20_000)
        if (m.needsReply && !m.urgent) onSeen(m) // "visto en la tele": se le avisa a quien lo mandó (el urgente avisa al cerrarlo)
        built.root.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
            .setDuration(520).setInterpolator(OvershootInterpolator(1.1f)).start()
        built.intro.run()
        built.bar.animate().scaleX(0f)
            .setDuration(duration).setInterpolator(LinearInterpolator()).start()
        built.first?.let { f -> built.root.post { f.requestFocus() } }
        main.postDelayed(autoHide, duration)
    }

    private fun dismiss() {
        val b = current ?: return
        if (dismissing) return
        dismissing = true
        main.removeCallbacks(autoHide)
        main.removeCallbacks(afterSent)
        main.removeCallbacks(repeatChime)
        b.stop.run()
        b.aura?.animate()?.alpha(0f)?.setDuration(320)?.start()
        b.root.animate().alpha(0f).translationY(-ctx.dp(40).toFloat()).scaleX(0.97f).scaleY(0.97f).setDuration(320)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                b.stop.run()
                try { wm.removeView(b.root) } catch (e: Exception) { /* ya no estaba */ }
                b.aura?.let { a -> try { wm.removeView(a) } catch (e: Exception) { /* ya no estaba */ } }
                if (current === b) {
                    current = null
                    currentMsg = null
                }
                dismissing = false
                next()
            }.start()
    }

    /** Una de las opciones se eligió (o se cerró / venció). [label] null = cerrar sin confirmación. */
    private fun answer(choice: String, label: String?) {
        val m = currentMsg ?: return
        val b = current ?: return
        if (answered || dismissing) return
        answered = true
        main.removeCallbacks(autoHide)
        main.removeCallbacks(repeatChime)
        if (!m.needsReply) {
            dismiss()
            return
        }
        onReply(m, choice)
        val row = b.buttons
        if (label == null || row == null) {
            dismiss()
            return
        }
        row.removeAllViews()
        val sent = TextView(ctx).apply {
            text = "✓  " + T("Respuesta enviada: %s", label)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            setTextColor(if (m.urgent) Color.WHITE else Pal.ok)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            scaleX = 0.8f
            scaleY = 0.8f
            alpha = 0f
        }
        row.addView(sent, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        sent.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(380).setInterpolator(OvershootInterpolator(1.8f)).start()
        b.stop.run()
        b.bar.animate().cancel()
        main.postDelayed(afterSent, 1_500)
    }

    @Suppress("DEPRECATION")
    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE

    /** Capa de toda la pantalla para el resplandor: no toma el foco ni responde al control. */
    private fun auraParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        overlayType(),
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    )

    /** Aviso urgente: toda la pantalla, con el foco del control y la tele sin apagarse. */
    private fun urgentParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        overlayType(),
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    )

    private fun params(focusable: Boolean): WindowManager.LayoutParams {
        val type = overlayType()
        val flags = if (focusable) {
            // Toma el foco del control para poder elegir una respuesta
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        } else {
            // Ni toma el foco ni responde al control: no interrumpe lo que se está mirando
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }
    }

    private fun buildCard(m: Msg): Built {
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val match = ViewGroup.LayoutParams.MATCH_PARENT

        // Avisos del sistema (los manda "Hey Family"): sin foto, con un ícono; el de vencimiento va en naranja
        val system = m.uid.isBlank() && (m.from == "Hey Family" || m.from == "Familia TV")
        // Color que eligió quien mandó el mensaje (los de la versión anterior no traen y quedan en violeta)
        val accent = if (system && m.text.startsWith("⏰")) Pal.warn else (Pal.byKey(m.color) ?: Pal.accent)
        val accentText = lighten(accent, 0.25f)
        val radius = ctx.dp(34)
        val cardW = (ctx.resources.displayMetrics.widthPixels * 0.66f).toInt().coerceIn(ctx.dp(560), ctx.dp(1000))
        val pulses = if (m.needsReply) ValueAnimator.INFINITE else 2   // ondas del avatar: sin parar si espera respuesta
        val running = mutableListOf<ValueAnimator>()
        // Velo oscuro + luz de color detrás de la tarjeta (más oscuro si pide respuesta)
        val aura = AuraScrim(ctx, accent, if (m.needsReply) 0x80000000.toInt() else 0x59000000).apply { alpha = 0f }

        val outer = KeyFrame(ctx) { answer("sin_respuesta", null) }.apply {
            setPadding(ctx.dp(48), ctx.dp(40), ctx.dp(48), ctx.dp(40))
            clipChildren = false
            clipToPadding = false
            alpha = 0f
            translationY = ctx.dp(70).toFloat()
            scaleX = 0.95f
            scaleY = 0.95f
        }
        val stack = FrameLayout(ctx).apply {
            clipChildren = false
            clipToPadding = false
        }

        // Aura suave alrededor de la tarjeta: dos halos que "respiran"
        fun halo(margin: Int, alpha: Int): View = View(ctx).apply {
            background = roundedBg(withAlpha(accent, alpha), radius + ctx.dp(margin))
        }
        // Empiezan sin tamaño y se ajustan a la tarjeta cuando ya se midió: si no, miden toda la pantalla
        val halo2 = halo(16, 0x1C)
        val halo1 = halo(7, 0x30)
        stack.addView(halo2, FrameLayout.LayoutParams(0, 0))
        stack.addView(halo1, FrameLayout.LayoutParams(0, 0))

        // Tarjeta: degradé oscuro teñido con el color elegido, borde de color, esquinas recortadas
        val tint = ColorUtils.blendARGB(0xFF181A38.toInt(), accent, 0.22f)
        val cardFrame = FrameLayout(ctx).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(withAlpha(tint, 0xFC), 0xFC1B1D3C.toInt(), 0xFC121429.toInt())
            ).apply {
                cornerRadius = radius.toFloat()
                setStroke(ctx.dp(2), withAlpha(accent, 0xAA))
            }
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, radius.toFloat())
                }
            }
        }

        // Resplandor del color en la esquina de arriba. Tiene que medir menos que el contenido:
        // si no, la tarjeta se estira para acomodarlo y queda mucho más alta que el mensaje.
        val glow = View(ctx).apply {
            background = GradientDrawable().apply {
                setGradientType(GradientDrawable.RADIAL_GRADIENT)
                setGradientRadius(ctx.dp(150).toFloat())
                setColors(intArrayOf(withAlpha(accent, 0x66), withAlpha(accent, 0x00)))
                setGradientCenter(0.5f, 0.5f)
            }
        }
        cardFrame.addView(
            glow,
            FrameLayout.LayoutParams(ctx.dp(300), ctx.dp(300)).apply { setMargins(-ctx.dp(110), -ctx.dp(150), 0, 0) }
        )

        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(ctx.dp(30), ctx.dp(26), ctx.dp(30), ctx.dp(24))
        }

        // Avatar (o ícono) con ondas que se expanden, como una llamada entrante
        val avatarBox = FrameLayout(ctx).apply {
            clipChildren = false
            clipToPadding = false
        }
        val rings = List(2) {
            View(ctx).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.TRANSPARENT)
                    setStroke(ctx.dp(3), accentText)
                }
                alpha = 0f
            }
        }
        rings.forEach { avatarBox.addView(it, FrameLayout.LayoutParams(match, match)) }
        val face: View = if (system) {
            TextView(ctx).apply {
                text = if (m.text.startsWith("⏰")) "⏰" else "📺"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 44f)
                gravity = Gravity.CENTER
                background = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(lighten(accent, 0.3f), accent, darken(accent, 0.3f))
                ).apply { shape = GradientDrawable.OVAL }
            }
        } else {
            ctx.avatarRing(m.from, m.avatar, 96, accent)
        }
        face.scaleX = 0f
        face.scaleY = 0f
        avatarBox.addView(face, FrameLayout.LayoutParams(match, match))

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        row.addView(avatarBox, LinearLayout.LayoutParams(ctx.dp(96), ctx.dp(96)))

        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val pill = TextView(ctx).apply {
            text = m.from
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTextColor(onColor(accent))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(ctx.dp(18), ctx.dp(6), ctx.dp(18), ctx.dp(6))
            background = gradientBg(ctx.dp(20), intArrayOf(accent, darken(accent, 0.25f)), GradientDrawable.Orientation.LEFT_RIGHT)
            alpha = 0f
            translationX = -ctx.dp(30).toFloat()
        }
        header.addView(pill, LinearLayout.LayoutParams(wrap, wrap))
        var replyChip: TextView? = null
        if (m.needsReply) {
            replyChip = TextView(ctx).apply {
                text = "↩  " + T("Espera tu respuesta")
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                setTextColor(accentText)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(ctx.dp(14), ctx.dp(5), ctx.dp(14), ctx.dp(5))
                background = roundedBg(withAlpha(accent, 0x26), ctx.dp(18), withAlpha(accent, 0x88), ctx.dp(1))
                alpha = 0f
            }
            header.addView(replyChip, LinearLayout.LayoutParams(wrap, wrap).apply { marginStart = ctx.dp(12) })
        }
        ageText(m.ts)?.let { age ->
            header.addView(
                TextView(ctx).apply {
                    text = age
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                    setTextColor(Pal.muted)
                },
                LinearLayout.LayoutParams(wrap, wrap).apply { marginStart = ctx.dp(14) }
            )
        }
        col.addView(header, LinearLayout.LayoutParams(wrap, wrap))

        val body = TextView(ctx).apply {
            text = hiddenFrom(m.text, 0) // se va escribiendo solo (ver typewrite)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 38f)
            setTextColor(Pal.text)
            setLineSpacing(0f, 1.12f)
            maxLines = 6
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, ctx.dp(12), 0, 0)
            alpha = 0f
            translationY = ctx.dp(14).toFloat()
        }
        col.addView(body)
        row.addView(col, LinearLayout.LayoutParams(0, wrap, 1f).apply { marginStart = ctx.dp(26) })
        content.addView(row, LinearLayout.LayoutParams(match, wrap))

        // Botones de respuesta (solo si el mensaje los pidió)
        var buttons: LinearLayout? = null
        var firstButton: View? = null
        if (m.needsReply) {
            val wrapper = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                alpha = 0f
                translationY = ctx.dp(10).toFloat()
            }
            val btnRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            val icons = mapOf("ok" to "✓", "voy" to "🏃", "cinco_min" to "⏱", "ahora_no" to "✋")
            choices.forEachIndexed { i, (choice, label) ->
                val b = ctx.tvButton((icons[choice] ?: "") + "  " + label, accent) { answer(choice, label) }
                if (i == 0) firstButton = b
                btnRow.addView(
                    b,
                    LinearLayout.LayoutParams(0, wrap, 1f).apply { if (i > 0) marginStart = ctx.dp(16) }
                )
            }
            wrapper.addView(btnRow, LinearLayout.LayoutParams(match, wrap))
            wrapper.addView(
                TextView(ctx).apply {
                    text = T("Elegí con ← → y confirmá con OK")
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    setTextColor(Pal.muted)
                    gravity = Gravity.CENTER
                },
                LinearLayout.LayoutParams(match, wrap).apply { topMargin = ctx.dp(12) }
            )
            content.addView(wrapper, LinearLayout.LayoutParams(match, wrap).apply { topMargin = ctx.dp(22) })
            buttons = wrapper
        }

        // Barra de tiempo pegada al borde de abajo: se va achicando hasta que el mensaje desaparece
        val bar = View(ctx).apply {
            background = gradientBg(0, intArrayOf(accent, accentText), GradientDrawable.Orientation.LEFT_RIGHT)
            pivotX = 0f
        }
        val track = FrameLayout(ctx).apply { setBackgroundColor(0x14FFFFFF) }
        track.addView(bar, FrameLayout.LayoutParams(match, match))
        content.addView(
            track,
            LinearLayout.LayoutParams(match, ctx.dp(6)).apply {
                setMargins(-ctx.dp(30), ctx.dp(20), -ctx.dp(30), -ctx.dp(12))
            }
        )
        cardFrame.addView(content, FrameLayout.LayoutParams(match, wrap))

        // Un destello que cruza la tarjeta al aparecer
        val sheen = View(ctx).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0x00FFFFFF, 0x2EFFFFFF, 0x00FFFFFF)
            )
            translationX = -ctx.dp(240).toFloat()
        }
        cardFrame.addView(sheen, FrameLayout.LayoutParams(ctx.dp(200), match))

        stack.addView(cardFrame, FrameLayout.LayoutParams(match, wrap))
        cardFrame.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            val w = r - l
            val h = b - t
            listOf(halo2 to 16, halo1 to 7).forEach { (v, mg) ->
                val px = ctx.dp(mg)
                val lp = v.layoutParams as FrameLayout.LayoutParams
                if (lp.width != w + 2 * px || lp.height != h + 2 * px) {
                    lp.width = w + 2 * px
                    lp.height = h + 2 * px
                    lp.setMargins(-px, -px, -px, -px)
                    v.layoutParams = lp
                }
            }
        }
        outer.addView(stack, FrameLayout.LayoutParams(cardW, wrap, Gravity.CENTER_HORIZONTAL))

        val intro = Runnable {
            val soft = DecelerateInterpolator()
            face.animate().scaleX(1f).scaleY(1f).setStartDelay(140).setDuration(560)
                .setInterpolator(OvershootInterpolator(1.7f)).start()
            pill.animate().alpha(1f).translationX(0f).setStartDelay(220).setDuration(400).setInterpolator(soft).start()
            replyChip?.animate()?.alpha(1f)?.setStartDelay(420)?.setDuration(400)?.start()
            body.animate().alpha(1f).translationY(0f).setStartDelay(300).setDuration(440).setInterpolator(soft).start()
            buttons?.animate()?.alpha(1f)?.translationY(0f)?.setStartDelay(520)?.setDuration(420)?.setInterpolator(soft)?.start()
            typewrite(body, m.text, running, 450L)

            // Resplandor de pantalla: entra y después respira
            aura.animate().alpha(1f).setDuration(600).start()
            ValueAnimator.ofFloat(0.55f, 1f).apply {
                duration = 2200
                startDelay = 700
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                addUpdateListener { aura.alpha = it.animatedValue as Float }
                start()
                running.add(this)
            }

            // Destello de lado a lado
            ValueAnimator.ofFloat(-ctx.dp(240).toFloat(), cardW + ctx.dp(40).toFloat()).apply {
                duration = 1100
                startDelay = 450
                interpolator = soft
                addUpdateListener { sheen.translationX = it.animatedValue as Float }
                start()
                running.add(this)
            }
            // Ondas alrededor del avatar
            rings.forEachIndexed { i, ring ->
                ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 1900
                    startDelay = 650L + i * 950L
                    repeatCount = pulses
                    interpolator = soft
                    addUpdateListener {
                        val f = it.animatedFraction
                        ring.scaleX = 1f + 0.55f * f
                        ring.scaleY = 1f + 0.55f * f
                        ring.alpha = 0.75f * (1f - f)
                    }
                    start()
                    running.add(this)
                }
            }
            // El aura respira
            ValueAnimator.ofFloat(0.5f, 1f).apply {
                duration = 2200
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                addUpdateListener {
                    val v = it.animatedValue as Float
                    halo1.alpha = v
                    halo2.alpha = 1.5f - v
                }
                start()
                running.add(this)
            }
            // "Espera tu respuesta" late suavemente
            replyChip?.let { chip ->
                ValueAnimator.ofFloat(1f, 0.55f).apply {
                    duration = 900
                    startDelay = 1000
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.REVERSE
                    addUpdateListener { chip.alpha = it.animatedValue as Float }
                    start()
                    running.add(this)
                }
            }
        }
        val stop = Runnable {
            running.forEach { it.cancel() }
            running.clear()
        }
        return Built(outer, bar, buttons, firstButton, intro, stop, aura)
    }

    /** El texto con lo que todavía no se escribió en transparente: el tamaño de la tarjeta no salta mientras aparece. */
    private fun hiddenFrom(full: String, shown: Int): CharSequence {
        val s = SpannableString(full)
        if (shown < full.length) s.setSpan(ForegroundColorSpan(Color.TRANSPARENT), shown, full.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return s
    }

    private fun typewrite(tv: TextView, full: String, running: MutableList<ValueAnimator>, delay: Long) {
        if (full.isEmpty()) return
        ValueAnimator.ofInt(0, full.length).apply {
            duration = (full.length * 28L).coerceIn(500L, 1600L)
            startDelay = delay
            interpolator = LinearInterpolator()
            addUpdateListener { tv.text = hiddenFrom(full, it.animatedValue as Int) }
            start()
            running.add(this)
        }
    }

    /**
     * Aviso urgente: toda la pantalla en rojo, avatar grande con ondas que no paran y el mensaje enorme, sin botones.
     * El sonido se repite cada 10 segundos hasta que alguien lo cierra con el control (eso avisa que ya lo vieron).
     */
    private fun buildUrgent(m: Msg): Built {
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val running = mutableListOf<ValueAnimator>()

        // Cerrarlo con el control (Atrás u OK) significa que ya lo vieron: se le avisa a quien lo mandó
        val outer = KeyFrame(ctx, onBack = { answer("visto", null) }, onConfirm = { answer("visto", null) }).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFFD0313F.toInt(), 0xFF8E1428.toInt(), 0xFF5E0B1B.toInt())
            )
            alpha = 0f
            scaleX = 1.06f
            scaleY = 1.06f
        }
        // Luz blanca que late detrás de todo
        val pulse = View(ctx).apply {
            background = GradientDrawable().apply {
                setGradientType(GradientDrawable.RADIAL_GRADIENT)
                setGradientRadius(ctx.dp(700).toFloat())
                setColors(intArrayOf(0x55FFFFFF, 0x00FFFFFF))
                setGradientCenter(0.5f, 0.45f)
            }
            alpha = 0f
        }
        outer.addView(pulse, FrameLayout.LayoutParams(match, match))

        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
            setPadding(ctx.dp(80), ctx.dp(30), ctx.dp(80), ctx.dp(40))
        }

        val badge = TextView(ctx).apply {
            text = "⚡  " + T("URGENTE")
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setTextColor(0xFF9B1224.toInt())
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.12f
            setPadding(ctx.dp(24), ctx.dp(8), ctx.dp(24), ctx.dp(8))
            background = roundedBg(Color.WHITE, ctx.dp(28))
            scaleX = 0f
            scaleY = 0f
        }
        col.addView(badge, LinearLayout.LayoutParams(wrap, wrap))

        val avatarBox = FrameLayout(ctx).apply {
            clipChildren = false
            clipToPadding = false
        }
        val rings = List(3) {
            View(ctx).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.TRANSPARENT)
                    setStroke(ctx.dp(4), Color.WHITE)
                }
                alpha = 0f
            }
        }
        rings.forEach { avatarBox.addView(it, FrameLayout.LayoutParams(match, match)) }
        val face = ctx.avatarRing(m.from, m.avatar, 200, Color.WHITE).apply {
            scaleX = 0f
            scaleY = 0f
        }
        avatarBox.addView(face, FrameLayout.LayoutParams(match, match))
        col.addView(avatarBox, LinearLayout.LayoutParams(ctx.dp(200), ctx.dp(200)).apply { topMargin = ctx.dp(26) })

        val who = TextView(ctx).apply {
            text = T("%s te necesita", m.from)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            setTextColor(0xE6FFFFFF.toInt())
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            alpha = 0f
            translationY = ctx.dp(14).toFloat()
        }
        col.addView(who, LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = ctx.dp(24) })

        val body = TextView(ctx).apply {
            text = hiddenFrom(m.text, 0)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 56f)
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.08f)
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
            alpha = 0f
            translationY = ctx.dp(14).toFloat()
        }
        col.addView(body, LinearLayout.LayoutParams(match, wrap).apply { topMargin = ctx.dp(8) })

        outer.addView(col, FrameLayout.LayoutParams(match, wrap, Gravity.CENTER))

        // Barra de tiempo abajo
        val bar = View(ctx).apply {
            setBackgroundColor(0xD9FFFFFF.toInt())
            pivotX = 0f
        }
        val track = FrameLayout(ctx).apply { setBackgroundColor(0x33FFFFFF) }
        track.addView(bar, FrameLayout.LayoutParams(match, match))
        outer.addView(track, FrameLayout.LayoutParams(match, ctx.dp(8), Gravity.BOTTOM))

        val intro = Runnable {
            val soft = DecelerateInterpolator()
            badge.animate().scaleX(1f).scaleY(1f).setStartDelay(100).setDuration(450).setInterpolator(OvershootInterpolator(2f)).start()
            face.animate().scaleX(1f).scaleY(1f).setStartDelay(200).setDuration(600).setInterpolator(OvershootInterpolator(1.8f)).start()
            who.animate().alpha(1f).translationY(0f).setStartDelay(350).setDuration(420).setInterpolator(soft).start()
            body.animate().alpha(1f).translationY(0f).setStartDelay(450).setDuration(460).setInterpolator(soft).start()
            typewrite(body, m.text, running, 650L)

            // La luz de atrás late sin parar
            ValueAnimator.ofFloat(0.25f, 1f).apply {
                duration = 1200
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                addUpdateListener { pulse.alpha = it.animatedValue as Float }
                start()
                running.add(this)
            }
            // Ondas del avatar, una tras otra
            rings.forEachIndexed { i, ring ->
                ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 1700
                    startDelay = 500L + i * 566L
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = soft
                    addUpdateListener {
                        val f = it.animatedFraction
                        ring.scaleX = 1f + 0.9f * f
                        ring.scaleY = 1f + 0.9f * f
                        ring.alpha = 0.8f * (1f - f)
                    }
                    start()
                    running.add(this)
                }
            }
            // El avatar "llama": un temblor corto cada tanto
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 3200
                startDelay = 1200
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    val f = it.animatedFraction
                    face.rotation = if (f < 0.7f) 0f else {
                        val k = (f - 0.7f) / 0.3f
                        (sin(k * PI * 4) * 6.0 * (1.0 - k)).toFloat()
                    }
                }
                start()
                running.add(this)
            }
        }
        val stop = Runnable {
            running.forEach { it.cancel() }
            running.clear()
        }
        return Built(outer, bar, null, null, intro, stop)
    }

    /** Si el mensaje llegó tarde (la TV estaba apagada), lo aclara. */
    private fun ageText(ts: Long): String? {
        val s = (System.currentTimeMillis() - ts) / 1000
        return when {
            s < 90 -> null
            s < 3_600 -> T("enviado hace %s min", s / 60)
            s < 86_400 -> T("enviado hace %s h", s / 3_600)
            else -> T("enviado hace más de un día")
        }
    }

    /** Aviso sonoro: el que esté elegido en la pantalla de inicio de la tele (ver Sounds.kt). */
    private fun chime(urgent: Boolean = false) {
        // En un aviso urgente lo que se esté mirando se pausa mientras suena
        Sounds.play(ctx, exclusive = urgent)
    }

    private fun notifyFallback(m: Msg) {
        Notifs.post(ctx, m.id.hashCode(), m.from, m.text)
    }
}
