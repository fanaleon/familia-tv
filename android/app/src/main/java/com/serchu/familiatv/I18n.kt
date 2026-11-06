package com.serchu.familiatv

import android.content.Context
import org.json.JSONObject
import java.util.Locale

/**
 * Idioma de la app (español, inglés o portugués). Los textos del código están escritos en español:
 * [T] busca la traducción en [Strings] y, si no la encuentra, deja el español.
 * El idioma se elige al abrir la app por primera vez y se cambia desde el botón del globo.
 */
object Lang {
    val all = listOf("es" to "Español", "en" to "English", "pt" to "Português")

    @Volatile var code: String = "es"
        private set

    val locale: Locale get() = Locale(code)

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("lang", Context.MODE_PRIVATE)

    /** Idioma del sistema si la app lo tiene; si no, inglés. */
    private fun systemDefault(): String {
        val l = Locale.getDefault().language
        return if (all.any { it.first == l }) l else "en"
    }

    fun init(ctx: Context) {
        val saved = prefs(ctx).getString("code", null)
        activate(if (saved != null && all.any { it.first == saved }) saved else systemDefault())
    }

    /** ¿La persona ya eligió idioma en este equipo? */
    fun chosen(ctx: Context): Boolean = prefs(ctx).getString("code", null) != null

    fun set(ctx: Context, newCode: String) {
        if (all.none { it.first == newCode }) return
        prefs(ctx).edit().putString("code", newCode).apply()
        activate(newCode)
    }

    private fun activate(c: String) {
        code = c
        Locale.setDefault(Locale(c)) // relojes, fechas y formatos del sistema siguen el idioma elegido
    }

    fun nameOf(c: String): String = all.firstOrNull { it.first == c }?.second ?: c

    /** "5 de enero" / "January 5" / "5 de janeiro" */
    val dayMonthFmt: String get() = if (code == "en") "MMMM d" else "d 'de' MMMM"

    /** "viernes 6 de noviembre" / "Friday, November 6" / "sexta-feira, 6 de novembro" */
    val weekdayDateFmt: String
        get() = when (code) {
            "en" -> "EEEE, MMMM d"
            "pt" -> "EEEE, d 'de' MMMM"
            else -> "EEEE d 'de' MMMM"
        }

    fun t(es: String): String = if (code == "es") es else (Strings.map[es]?.get(code) ?: es)

    /**
     * Texto del servidor en el idioma elegido. El servidor manda las versiones en `o[key][código][field]`
     * (recibos: "textos", avisos y mensajes del sistema: "i18n"); si falta, se usa [fallback] o `o[field]`.
     */
    fun pick(o: JSONObject?, field: String, key: String = "textos", fallback: String? = null): String {
        if (o == null) return fallback.orEmpty()
        val v = o.optJSONObject(key)?.optJSONObject(code)?.optString(field).orEmpty()
        if (v.isNotBlank()) return v
        return fallback ?: o.optString(field)
    }

    /** Etiqueta del bloqueo: el servidor manda la estructura (`lk`) y cada app la escribe en su idioma. */
    fun freezeLabel(label: String, lk: JSONObject?): String {
        if (lk == null) return label
        return when (lk.optString("k")) {
            "until" -> T("La tele vuelve a las %s", "${lk.optInt("h")}:${lk.optInt("m").toString().padStart(2, '0')}")
            "rest" -> {
                val min = lk.optInt("min")
                if (min >= 60 && min % 60 == 0) T("Descanso de %s h", min / 60) else T("Descanso de %s min", min)
            }
            else -> label
        }
    }
}

/** Traduce un texto escrito en español; con argumentos, reemplaza los %s (o %d) en orden. */
fun T(es: String, vararg args: Any?): String {
    val s = Lang.t(es)
    return if (args.isEmpty()) s else String.format(Lang.locale, s, *args)
}
