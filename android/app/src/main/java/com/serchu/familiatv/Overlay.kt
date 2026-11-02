package com.serchu.familiatv

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat

/**
 * Un mensaje que llegó a la TV.
 * [uid] es quien lo mandó (para devolverle la respuesta); vacío en los mensajes de prueba.
 * [longStay] pide que dure más en pantalla; [needsReply] muestra los botones de respuesta.
 */
class Msg(
    val id: String,
    val uid: String,
    val from: String,
    val text: String,
    val ts: Long,
    val avatar: String? = null,
    val longStay: Boolean = false,
    val needsReply: Boolean = false
)

object Notifs {
    const val CH_SERVICE = "relay"
    const val CH_MSG = "mensajes"

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "Conexión con la familia", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_MSG, "Mensajes", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    /** Notificación común con sonido; al tocarla abre la app. */
    fun post(ctx: Context, notifId: Int, title: String, text: String) {
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
            .build()
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(notifId, n)
    }
}

/** Ventana que además de dibujar atiende el botón Atrás del control cuando tiene el foco. */
private class KeyFrame(context: Context, private val onBack: () -> Unit) : FrameLayout(context) {
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) onBack()
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
    private val onReply: (Msg, String) -> Unit
) {

    private class Built(val root: View, val bar: View, val buttons: LinearLayout?, val first: View?)

    private val choices = listOf("ok" to "OK", "voy" to "Voy", "ahora_no" to "Ahora no")

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
            chime()
            main.postDelayed(this, 20_000)
        }
    }
    private var focusRequest: AudioFocusRequest? = null

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
            try { wm.removeView(it.root) } catch (e: Exception) { /* ya no estaba */ }
        }
        current = null
        currentMsg = null
        dismissing = false
    }

    private fun stayMillis(m: Msg): Long = when {
        m.needsReply -> 180_000L
        m.longStay -> (45_000L + m.text.length * 120L).coerceIn(45_000L, 120_000L)
        else -> (15_000L + m.text.length * 80L).coerceIn(15_000L, 45_000L)
    }

    private fun next() {
        val m = queue.removeFirstOrNull() ?: return
        val duration = stayMillis(m)
        val built = buildCard(m)
        try {
            wm.addView(built.root, params(m.needsReply))
        } catch (e: Exception) {
            // Sin permiso o sin ventana disponible: que al menos llegue como notificación
            notifyFallback(m)
            next()
            return
        }
        current = built
        currentMsg = m
        answered = false
        chime()
        if (m.needsReply) main.postDelayed(repeatChime, 20_000)
        built.root.animate().alpha(1f).translationY(0f)
            .setDuration(350).setInterpolator(DecelerateInterpolator()).start()
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
        b.root.animate().alpha(0f).translationY(-ctx.dp(40).toFloat()).setDuration(300)
            .withEndAction {
                try { wm.removeView(b.root) } catch (e: Exception) { /* ya no estaba */ }
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
        row.addView(
            TextView(ctx).apply {
                text = "Respuesta enviada: $label"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                setTextColor(Pal.ok)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        b.bar.animate().cancel()
        main.postDelayed(afterSent, 1_500)
    }

    @Suppress("DEPRECATION")
    private fun params(focusable: Boolean): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }
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
        ).apply { gravity = Gravity.TOP }
    }

    private fun buildCard(m: Msg): Built {
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val match = ViewGroup.LayoutParams.MATCH_PARENT

        val outer = KeyFrame(ctx) { answer("sin_respuesta", null) }.apply {
            setPadding(ctx.dp(48), ctx.dp(32), ctx.dp(48), 0)
            alpha = 0f
            translationY = -ctx.dp(60).toFloat()
        }

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(0xF2161830.toInt(), ctx.dp(28), 0x556C5CE7, ctx.dp(2))
            setPadding(ctx.dp(28), ctx.dp(26), ctx.dp(28), ctx.dp(22))
        }

        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(ctx.avatarView(m.from, m.avatar, 72), LinearLayout.LayoutParams(ctx.dp(72), ctx.dp(72)))

        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(ctx).apply {
            text = m.from
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setTextColor(Pal.accentSoft)
            typeface = Typeface.DEFAULT_BOLD
        })
        col.addView(TextView(ctx).apply {
            text = m.text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 38f)
            setTextColor(Pal.text)
            setLineSpacing(0f, 1.1f)
            maxLines = 6
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, ctx.dp(4), 0, 0)
        })
        ageText(m.ts)?.let { age ->
            col.addView(TextView(ctx).apply {
                text = age
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setTextColor(Pal.muted)
                setPadding(0, ctx.dp(8), 0, 0)
            })
        }
        row.addView(
            col,
            LinearLayout.LayoutParams(0, wrap, 1f).apply { marginStart = ctx.dp(22) }
        )
        card.addView(row, LinearLayout.LayoutParams(match, wrap))

        // Botones de respuesta (solo si el mensaje los pidió)
        var buttons: LinearLayout? = null
        var firstButton: View? = null
        if (m.needsReply) {
            val btnRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            choices.forEachIndexed { i, (choice, label) ->
                val b = ctx.tvButton(label) { answer(choice, label) }
                if (i == 0) firstButton = b
                btnRow.addView(
                    b,
                    LinearLayout.LayoutParams(0, wrap, 1f).apply { if (i > 0) marginStart = ctx.dp(16) }
                )
            }
            card.addView(btnRow, LinearLayout.LayoutParams(match, wrap).apply { topMargin = ctx.dp(22) })
            buttons = btnRow
        }

        // Barrita que se va achicando: muestra cuánto falta para que desaparezca
        val bar = View(ctx).apply {
            background = roundedBg(Pal.accent, ctx.dp(3))
            pivotX = 0f
        }
        card.addView(
            bar,
            LinearLayout.LayoutParams(match, ctx.dp(5)).apply { topMargin = ctx.dp(20) }
        )

        outer.addView(card, FrameLayout.LayoutParams(match, wrap))
        return Built(outer, bar, buttons, firstButton)
    }

    /** Si el mensaje llegó tarde (la TV estaba apagada), lo aclara. */
    private fun ageText(ts: Long): String? {
        val s = (System.currentTimeMillis() - ts) / 1000
        return when {
            s < 90 -> null
            s < 3_600 -> "enviado hace ${s / 60} min"
            s < 86_400 -> "enviado hace ${s / 3_600} h"
            else -> "enviado hace más de un día"
        }
    }

    /**
     * Aviso sonoro pensado para cuando la tele está con música o un video y nadie mira la pantalla:
     * tres pitidos por el mismo canal que la música (sigue el volumen de la tele) y, mientras
     * suenan, le pide al sistema que baje un poco lo que se esté reproduciendo.
     */
    private fun chime() {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            takeFocus(am)
        } catch (e: Exception) { /* sin foco igual suena */ }
        try {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 250)
            main.postDelayed({ tone.startTone(ToneGenerator.TONE_PROP_BEEP, 250) }, 400)
            main.postDelayed({ tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 450) }, 800)
            main.postDelayed({
                tone.release()
                try { dropFocus(am) } catch (e: Exception) { /* nada que soltar */ }
            }, 1_800)
        } catch (e: Exception) { /* sin sonido */ }
    }

    @Suppress("DEPRECATION")
    private fun takeFocus(am: AudioManager) {
        if (Build.VERSION.SDK_INT >= 26) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .build()
            focusRequest = req
            am.requestAudioFocus(req)
        } else {
            am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        }
    }

    @Suppress("DEPRECATION")
    private fun dropFocus(am: AudioManager) {
        if (Build.VERSION.SDK_INT >= 26) {
            focusRequest?.let { am.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            am.abandonAudioFocus(null)
        }
    }

    private fun notifyFallback(m: Msg) {
        Notifs.post(ctx, m.id.hashCode(), m.from, m.text)
    }
}
