package com.serchu.familiatv

import android.app.UiModeManager
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.TextView
import okhttp3.OkHttpClient
import java.util.UUID
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

/**
 * Quién es el dueño de este móvil: un id propio (no cambia nunca), su nombre y su foto
 * (ya achicada, en base64). Se guardan solo en el teléfono.
 */
object Profile {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("profile", Context.MODE_PRIVATE)

    fun uid(ctx: Context): String {
        val p = prefs(ctx)
        val saved = p.getString("uid", null)
        if (saved != null) return saved
        val fresh = UUID.randomUUID().toString()
        p.edit().putString("uid", fresh).apply()
        return fresh
    }

    fun name(ctx: Context): String =
        prefs(ctx).getString("name", null)?.takeIf { it.isNotBlank() } ?: Cfg.senderName

    fun setName(ctx: Context, name: String) {
        prefs(ctx).edit().putString("name", name.trim()).apply()
    }

    fun avatar(ctx: Context): String? = prefs(ctx).getString("avatar", null)

    fun setAvatar(ctx: Context, base64: String?) {
        prefs(ctx).edit().putString("avatar", base64).apply()
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

/**
 * Círculo con la foto de la persona; si no tiene foto (o no se puede leer), la inicial
 * del nombre sobre un color propio. El tamaño lo pone quien lo agrega a su layout.
 */
fun Context.avatarView(name: String, base64: String?, sizeDp: Int): View {
    val bmp = base64?.let {
        try {
            val bytes = Base64.decode(it, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            null
        }
    }
    if (bmp != null) {
        return ImageView(this).apply {
            setImageBitmap(bmp)
            scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            clipToOutline = true
        }
    }
    return TextView(this).apply {
        text = name.trim().take(1).uppercase()
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeDp * 0.42f)
        setTextColor(Color.WHITE)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Pal.avatars[(name.hashCode() and 0x7fffffff) % Pal.avatars.size])
        }
    }
}
