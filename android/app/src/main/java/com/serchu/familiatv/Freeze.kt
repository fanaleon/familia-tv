package com.serchu.familiatv

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/** Ventana que se queda con TODAS las teclas del control mientras la tele está bloqueada. */
private class KeyEater(context: Context) : FrameLayout(context) {
    /** Se avisa cada vez que alguien toca un botón (el perro guardián viene a ladrar). */
    var onKey: (() -> Unit)? = null

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) onKey?.invoke()
        return true
    }
}

/**
 * Bloqueo de la tele (control parental): tapa toda la pantalla con una pantalla de descanso con
 * cuenta regresiva, silencia el audio y pausa lo que se esté reproduciendo.
 *  - Se desbloquea solo cuando se cumple el tiempo, o cuando llega la orden desde el móvil.
 *  - El fin del bloqueo se guarda en el equipo: si se corta internet o se reinicia la app, sigue.
 * Todos los métodos se llaman desde el hilo principal.
 */
class FreezeController(private val ctx: Context) {

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val main = Handler(Looper.getMainLooper())
    private val prefs = ctx.getSharedPreferences("freeze", Context.MODE_PRIVATE)

    private var view: FrameLayout? = null
    private var subtitleView: TextView? = null
    private var countdownView: TextView? = null
    private var footerView: TextView? = null
    private var pulse: ObjectAnimator? = null

    var running: Boolean = false
        private set
    private var deadline = 0L // hora del equipo en la que termina
    private var ticks = 0

    private val tick = object : Runnable {
        override fun run() {
            if (onTick()) main.postDelayed(this, 1_000)
        }
    }

    /** Al arrancar el servicio: si había un bloqueo en curso, lo retoma. */
    fun restore() {
        val until = prefs.getLong("until", 0L)
        if (until > System.currentTimeMillis()) {
            begin(until, prefs.getString("label", "") ?: "", prefs.getString("by", "") ?: "")
        } else if (until != 0L) {
            // Se venció mientras la app estaba cerrada: dejar el audio como estaba
            unmute()
            prefs.edit().clear().apply()
        }
    }

    /** Orden nueva: bloquear durante [endsInMs] milisegundos. */
    fun start(endsInMs: Long, label: String, by: String) {
        if (endsInMs <= 0L) {
            stop()
            return
        }
        val until = System.currentTimeMillis() + endsInMs
        prefs.edit().putLong("until", until).putString("label", label).putString("by", by).apply()
        begin(until, label, by)
    }

    /** Desbloquea (orden del móvil o fin del tiempo). */
    fun stop() {
        end()
        unmute()
        prefs.edit().clear().apply()
    }

    /** El servicio se apaga: saca la pantalla pero deja guardado el bloqueo para retomarlo. */
    fun release() {
        end()
    }

    private fun begin(until: Long, label: String, by: String) {
        deadline = until
        running = true
        ticks = 0
        if (view == null) show(by)
        subtitleView?.text = label
        footerView?.text = if (by.isNotBlank()) "Bloqueado por $by" else ""
        mute()
        pauseMedia()
        main.removeCallbacks(tick)
        if (onTick()) main.postDelayed(tick, 1_000)
    }

    private fun end() {
        running = false
        main.removeCallbacks(tick)
        pulse?.cancel()
        pulse = null
        view?.let {
            try { wm.removeView(it) } catch (e: Exception) { /* ya no estaba */ }
        }
        view = null
        subtitleView = null
        countdownView = null
        footerView = null
    }

    /** Devuelve false si el bloqueo terminó. */
    private fun onTick(): Boolean {
        val left = deadline - System.currentTimeMillis()
        if (left <= 0L) {
            stop()
            return false
        }
        countdownView?.text = format(left)
        ticks++
        if (ticks % 5 == 0) mute()          // por si alguien subió el volumen
        if (ticks % 15 == 0) pauseMedia()   // por si alguien le dio play a ciegas
        return true
    }

    private fun format(ms: Long): String {
        val total = (ms + 999) / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%02d:%02d", m, s)
    }

    private fun mute() {
        try {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
            prefs.edit().putBoolean("muted", true).apply()
        } catch (e: Exception) { /* algunas teles tienen el volumen fijo */ }
    }

    private fun unmute() {
        if (!prefs.getBoolean("muted", false)) return
        try {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
        } catch (e: Exception) { /* nada que hacer */ }
        prefs.edit().putBoolean("muted", false).apply()
    }

    private val remute = Runnable { if (running) mute() }

    /**
     * Ladrido del perro guardián. Con el volumen silenciado no se oiría (en las teles todos los sonidos
     * comparten el mismo volumen), así que se des-silencia un instante, se toma el foco de audio en
     * exclusiva (lo demás se pausa mientras suena) y se vuelve a silenciar.
     */
    private fun bark() {
        try {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
        } catch (e: Exception) { /* volumen fijo */ }
        Sounds.play(ctx, "guau", exclusive = true)
        main.removeCallbacks(remute)
        main.postDelayed(remute, 900)
    }

    private fun pauseMedia() {
        try {
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
        } catch (e: Exception) { /* sin reproductor activo */ }
    }

    @Suppress("DEPRECATION")
    private fun show(by: String) {
        if (!Settings.canDrawOverlays(ctx)) return // sin permiso solo se silencia y se pausa

        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

        // El contenido está pensado para ~480 dp de alto. Si esta tele tiene menos, todo se achica
        // proporcionalmente para que nunca quede cortado arriba o abajo.
        // Abajo se reserva un franja para el perro guardián, así que el contenido usa el resto.
        val screenH = ctx.resources.displayMetrics.heightPixels.toFloat()
        val dadZone = (screenH * DogView.ZONE).toInt()
        val scale = ((screenH - dadZone) / ctx.dp(480)).coerceIn(0.5f, 1f)
        fun sz(base: Float): Float = base * scale
        fun d(base: Int): Int = (ctx.dp(base) * scale).toInt()

        val root = KeyEater(ctx).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF05060F.toInt(), 0xFF120E33.toInt(), 0xFF1F1450.toInt())
            )
            isFocusable = true
            isFocusableInTouchMode = true
            alpha = 0f
        }

        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }

        val moon = TextView(ctx).apply {
            text = "🌙"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sz(64f))
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x1AFFFFFF)
                setStroke(maxOf(1, d(2)), 0x33FFFFFF)
            }
        }
        column.addView(moon, LinearLayout.LayoutParams(d(140), d(140)))

        column.addView(
            TextView(ctx).apply {
                text = "Hora de descansar"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, sz(42f))
                setTextColor(Pal.text)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = d(24) }
        )

        val subtitle = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sz(24f))
            setTextColor(Pal.muted)
            gravity = Gravity.CENTER
        }
        column.addView(subtitle, LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = d(6) })

        val countdown = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sz(72f))
            setTextColor(Pal.accentSoft)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        column.addView(countdown, LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = d(18) })

        val footer = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sz(18f))
            setTextColor(Pal.muted)
            gravity = Gravity.CENTER
        }
        column.addView(footer, LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = d(24) })

        root.addView(
            column,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                bottomMargin = dadZone
            }
        )

        // El perro guardián: se echa en su cucha y, si tocan un botón, viene a ladrar (máx. 3 veces por bloqueo)
        val dog = DogView(ctx)
        dog.onBark = { bark() }
        root.onKey = { dog.poke() }
        root.addView(
            dog,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        val type = if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.OPAQUE
        )
        try {
            wm.addView(root, params)
        } catch (e: Exception) {
            return
        }

        view = root
        subtitleView = subtitle
        countdownView = countdown
        footerView = footer
        root.post { root.requestFocus() }
        root.animate().alpha(1f).setDuration(450).start()

        pulse = ObjectAnimator.ofPropertyValuesHolder(
            moon,
            PropertyValuesHolder.ofFloat("scaleX", 1f, 1.07f),
            PropertyValuesHolder.ofFloat("scaleY", 1f, 1.07f)
        ).apply {
            duration = 2_400
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }
}
