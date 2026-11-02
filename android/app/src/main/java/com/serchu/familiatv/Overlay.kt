package com.serchu.familiatv

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat

class Msg(val id: String, val from: String, val text: String, val ts: Long)

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
}

/**
 * Muestra los mensajes como una tarjeta arriba de la pantalla, encima de lo que se esté viendo.
 * Si no hay permiso de "mostrar sobre otras apps" cae a una notificación común.
 * Todos los métodos se llaman desde el hilo principal.
 */
class OverlayPresenter(private val ctx: Context) {

    private class Built(val root: View, val bar: View)

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<Msg>()
    private var current: View? = null
    private val autoHide = Runnable { dismiss() }

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
        queue.clear()
        current?.let {
            try { wm.removeView(it) } catch (e: Exception) { /* ya no estaba */ }
        }
        current = null
    }

    private fun next() {
        val m = queue.removeFirstOrNull() ?: return
        val duration = (8_000L + m.text.length * 70L).coerceIn(8_000L, 30_000L)
        val built = buildView(m)
        try {
            wm.addView(built.root, params())
        } catch (e: Exception) {
            // Sin permiso o sin ventana disponible: que al menos llegue como notificación
            notifyFallback(m)
            next()
            return
        }
        current = built.root
        beep()
        built.root.animate().alpha(1f).translationY(0f)
            .setDuration(350).setInterpolator(DecelerateInterpolator()).start()
        built.bar.animate().scaleX(0f)
            .setDuration(duration).setInterpolator(LinearInterpolator()).start()
        main.postDelayed(autoHide, duration)
    }

    private fun dismiss() {
        val v = current ?: return
        main.removeCallbacks(autoHide)
        v.animate().alpha(0f).translationY(-ctx.dp(40).toFloat()).setDuration(300)
            .withEndAction {
                try { wm.removeView(v) } catch (e: Exception) { /* ya no estaba */ }
                current = null
                next()
            }.start()
    }

    @Suppress("DEPRECATION")
    private fun params(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            // Ni toma el foco ni responde al control: no interrumpe lo que se está mirando
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP }
    }

    private fun buildView(m: Msg): Built {
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val match = ViewGroup.LayoutParams.MATCH_PARENT

        val outer = FrameLayout(ctx).apply {
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

        val avatar = TextView(ctx).apply {
            text = m.from.trim().take(1).uppercase()
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Pal.avatars[(m.from.hashCode() and 0x7fffffff) % Pal.avatars.size])
            }
        }
        row.addView(avatar, LinearLayout.LayoutParams(ctx.dp(72), ctx.dp(72)))

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

        // Barrita que se va achicando: muestra cuánto falta para que desaparezca
        val bar = View(ctx).apply {
            background = roundedBg(Pal.accent, ctx.dp(3))
            pivotX = 0f
        }

        card.addView(row, LinearLayout.LayoutParams(match, wrap))
        card.addView(
            bar,
            LinearLayout.LayoutParams(match, ctx.dp(5)).apply { topMargin = ctx.dp(20) }
        )
        outer.addView(card, FrameLayout.LayoutParams(match, wrap))
        return Built(outer, bar)
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

    private fun beep() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val ringtone = RingtoneManager.getRingtone(ctx, uri)
            if (ringtone != null) {
                ringtone.play()
                return
            }
        } catch (e: Exception) { /* probamos con el tono simple */ }
        try {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90).startTone(ToneGenerator.TONE_PROP_ACK, 400)
        } catch (e: Exception) { /* sin sonido */ }
    }

    private fun notifyFallback(m: Msg) {
        Notifs.ensureChannels(ctx)
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, Notifs.CH_MSG)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(m.from)
            .setContentText(m.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(m.text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(m.id.hashCode(), n)
    }
}
