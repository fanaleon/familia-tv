package com.serchu.familiatv

import android.app.UiModeManager
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Estado compartido entre el servicio y la pantalla de la TV. */
object RelayState {
    @Volatile var connected: Boolean = false
    @Volatile var lastFrom: String = ""
    @Volatile var lastText: String = ""
    @Volatile var lastTs: Long = 0L
}

object Net {
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
}

object Device {
    fun isTv(ctx: Context): Boolean {
        val ui = ctx.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        return ui.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            ctx.packageManager.hasSystemFeature("android.software.leanback")
    }
}

/** Paleta de la app. */
object Pal {
    val bg = 0xFF0F1020.toInt()
    val card = 0xFF1B1D36.toInt()
    val cardHi = 0xFF2A2D55.toInt()
    val accent = 0xFF6C5CE7.toInt()
    val accentSoft = 0xFFA29BFE.toInt()
    val text = 0xFFF5F6FA.toInt()
    val muted = 0xFF9AA0C3.toInt()
    val ok = 0xFF2ED573.toInt()
    val warn = 0xFFFFB142.toInt()
    val bad = 0xFFFF6B81.toInt()

    /** Un color por persona, según el nombre de quien manda el mensaje. */
    val avatars = intArrayOf(
        0xFF6C5CE7.toInt(),
        0xFF00B894.toInt(),
        0xFFE17055.toInt(),
        0xFF0984E3.toInt(),
        0xFFE84393.toInt(),
        0xFFF0932B.toInt()
    )
}

fun Context.dp(v: Int): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

fun roundedBg(color: Int, radiusPx: Int, strokeColor: Int = 0, strokePx: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusPx.toFloat()
        if (strokePx > 0) setStroke(strokePx, strokeColor)
    }

fun withRipple(content: Drawable): Drawable =
    RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, null)

/** Botón pensado para control remoto: se ilumina cuando tiene el foco. */
fun Context.tvButton(label: String, onClick: () -> Unit): TextView {
    val ctx = this
    return TextView(ctx).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        setTextColor(Pal.text)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(ctx.dp(32), ctx.dp(16), ctx.dp(32), ctx.dp(16))
        isFocusable = true
        isClickable = true
        background = StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_focused),
                roundedBg(Pal.accent, ctx.dp(16), Pal.text, ctx.dp(3))
            )
            addState(intArrayOf(), roundedBg(Pal.cardHi, ctx.dp(16)))
        }
        setOnClickListener { onClick() }
    }
}
