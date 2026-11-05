package com.serchu.familiatv

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Sonidos de aviso. Se generan por código (no hay archivos de audio) y suenan por el mismo canal que
 * la música, así que siguen el volumen de la tele. Mientras suenan, le piden al sistema que baje un
 * poco lo que se esté reproduciendo. El elegido se guarda en la tele.
 * Todos los métodos se llaman desde el hilo principal.
 */
object Sounds {

    class Preset(val key: String, val label: String)

    val presets = listOf(
        Preset("campana", "Campana suave"),
        Preset("gota", "Gota"),
        Preset("silbido", "Silbido de tubería"),
        Preset("silencio", "Silencio")
    )

    private const val RATE = 22050

    private val main = Handler(Looper.getMainLooper())
    private val cache = HashMap<String, ShortArray>()
    private var focusRequest: AudioFocusRequest? = null

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("sound", Context.MODE_PRIVATE)

    fun currentKey(ctx: Context): String {
        val saved = prefs(ctx).getString("key", null)
        return presets.firstOrNull { it.key == saved }?.key ?: "campana"
    }

    fun label(ctx: Context): String = presets.first { it.key == currentKey(ctx) }.label

    /** Pasa al sonido siguiente de la lista (y vuelve al primero) y lo deja guardado. */
    fun next(ctx: Context) {
        val i = presets.indexOfFirst { it.key == currentKey(ctx) }
        val n = presets[(i + 1) % presets.size]
        prefs(ctx).edit().putString("key", n.key).apply()
    }

    /** Hace sonar el sonido elegido (o [key] si se indica). */
    fun play(ctx: Context, key: String = currentKey(ctx), exclusive: Boolean = false) {
        val data = cache.getOrPut(key) { build(key) }
        if (data.isEmpty()) return // "Silencio"

        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            takeFocus(am, exclusive)
        } catch (e: Exception) { /* sin foco igual suena */ }
        try {
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(data.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(data, 0, data.size)
            track.play()
            val ms = data.size * 1000L / RATE + 400L
            main.postDelayed({
                try { track.release() } catch (e: Exception) { /* ya estaba liberado */ }
                try { dropFocus(am) } catch (e: Exception) { /* nada que soltar */ }
            }, ms)
        } catch (e: Exception) { /* sin sonido */ }
    }

    @Suppress("DEPRECATION")
    private fun takeFocus(am: AudioManager, exclusive: Boolean) {
        if (Build.VERSION.SDK_INT >= 26) {
            val req = AudioFocusRequest.Builder(gain(exclusive))
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
            am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, gain(exclusive))
        }
    }

    /** En exclusiva, lo demás se pausa mientras suena; si no, solo baja un poco. */
    private fun gain(exclusive: Boolean): Int =
        if (exclusive) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
        else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK

    @Suppress("DEPRECATION")
    private fun dropFocus(am: AudioManager) {
        if (Build.VERSION.SDK_INT >= 26) {
            focusRequest?.let { am.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            am.abandonAudioFocus(null)
        }
    }

    // ------------------------------------------------------------ síntesis

    private fun build(key: String): ShortArray = when (key) {
        "campana" -> campana()
        "gota" -> gota()
        "silbido" -> silbido()
        "guau" -> guau()
        else -> ShortArray(0)
    }

    /**
     * Ladrido de perro (lo usa el perro guardián de la pantalla de bloqueo; no es un sonido de aviso).
     * Un tono grave que arranca más agudo y baja, con armónicos reforzados donde un perro tiene sus
     * "vocales" (que pasan de una "a" abierta a una "u") y un poco de aire al principio.
     */
    private fun guau(): ShortArray {
        val buf = FloatArray(RATE * 32 / 100)
        val rnd = java.util.Random(11)
        var ph = 0.0
        var peak = 0.0
        for (i in buf.indices) {
            val t = i.toDouble() / RATE
            val f0 = 330.0 - 120.0 * Math.min(1.0, t / 0.2) + 30.0 * Math.exp(-t / 0.02)
            ph += 2 * Math.PI * f0 / RATE
            val formant = 900.0 - 400.0 * Math.min(1.0, t / 0.2)
            var v = 0.0
            for (k in 1..14) {
                val fk = f0 * k
                val a = Math.exp(-Math.pow((fk - formant) / 450.0, 2.0)) +
                    0.55 * Math.exp(-Math.pow((fk - 2000.0) / 800.0, 2.0))
                v += a * Math.sin(k * ph) / Math.pow(k.toDouble(), 0.3)
            }
            val env = (1.0 - Math.exp(-t / 0.006)) * Math.exp(-t / 0.10)
            val noise = (rnd.nextDouble() * 2 - 1) * 0.5 * Math.exp(-t / 0.03)
            val x = (v + noise) * env
            buf[i] = x.toFloat()
            if (Math.abs(x) > peak) peak = Math.abs(x)
        }
        if (peak > 0.0) for (i in buf.indices) buf[i] = (buf[i] / peak * 0.95).toFloat()
        return toPcm(buf)
    }

    /** Convierte el mezclado (de -1 a 1) a audio de 16 bits, con tope para que nunca sature. */
    private fun toPcm(buf: FloatArray): ShortArray {
        val out = ShortArray(buf.size)
        for (i in buf.indices) {
            val v = buf[i].coerceIn(-1f, 1f)
            out[i] = (v * 0.85f * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    /** Una nota de campana: ataque suave, brillo que se apaga rápido y cola que se desvanece. */
    private fun addBell(buf: FloatArray, startMs: Int, freq: Double, tau: Double, gain: Double) {
        val start = RATE * startMs / 1000
        val len = (tau * 6.0 * RATE).toInt()
        for (i in 0 until len) {
            val idx = start + i
            if (idx >= buf.size) break
            val t = i.toDouble() / RATE
            val env = (1.0 - Math.exp(-t / 0.004)) * Math.exp(-t / tau)
            val wave = Math.sin(2 * Math.PI * freq * t) +
                0.30 * Math.sin(2 * Math.PI * 2 * freq * t) * Math.exp(-t / 0.12) +
                0.10 * Math.sin(2 * Math.PI * 3 * freq * t) * Math.exp(-t / 0.08)
            buf[idx] += (gain * env * wave).toFloat()
        }
    }

    /** "Ding-dong": dos notas descendentes (Mi y Do), redondas y sin filo. */
    private fun campana(): ShortArray {
        val buf = FloatArray(RATE * 1800 / 1000)
        addBell(buf, 0, 659.25, 0.25, 0.55)
        addBell(buf, 330, 523.25, 0.32, 0.55)
        return toPcm(buf)
    }

    /** Dos "gotas": un tono corto que sube un poquito, muy suave. */
    private fun gota(): ShortArray {
        val buf = FloatArray(RATE * 800 / 1000)
        for (startMs in intArrayOf(0, 260)) {
            val start = RATE * startMs / 1000
            val len = RATE * 260 / 1000
            var phase = 0.0
            for (i in 0 until len) {
                val idx = start + i
                if (idx >= buf.size) break
                val t = i.toDouble() / RATE
                val freq = 520.0 + 380.0 * Math.min(1.0, t / 0.11)
                phase += 2 * Math.PI * freq / RATE
                val env = (1.0 - Math.exp(-t / 0.003)) * Math.exp(-t / 0.06)
                buf[idx] += (0.7 * env * Math.sin(phase)).toFloat()
            }
        }
        return toPcm(buf)
    }

    /** El silbido descendente de la versión anterior (onda cuadrada, dos veces). */
    private fun silbido(): ShortArray {
        val sweep = RATE * 450 / 1000
        val gap = RATE * 120 / 1000
        val buf = FloatArray(sweep * 2 + gap)
        var phase = 0.0
        for (round in 0..1) {
            val base = round * (sweep + gap)
            for (i in 0 until sweep) {
                val p = i.toDouble() / sweep
                phase += 2 * Math.PI * (1400.0 - 1050.0 * p) / RATE
                val square = if (Math.sin(phase) >= 0) 1.0 else -1.0
                val fade = if (p > 0.85) (1.0 - p) / 0.15 else 1.0
                buf[base + i] = (square * fade * 0.5).toFloat()
            }
        }
        return toPcm(buf)
    }
}
