package com.serchu.familiatv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * El perro guardián de la pantalla de bloqueo: un personaje propio dibujado por código (sin imágenes).
 * Pasea por abajo, se echa a dormir en su cucha (al lado del cartel de "No molestar") y de vez en
 * cuando entreabre un ojo para espiar.
 * Si se toca cualquier botón del control ([poke]): se despierta, sale corriendo hasta la pantalla,
 * pega el salto contra el vidrio, ladra tres veces (suena [onBark] en cada ladrido), mueve la cola y
 * vuelve corriendo a la cucha.
 * Para que no se vuelva un juego, solo reacciona [MAX_RUNS] veces por bloqueo; después sigue dormido.
 * Ocupa toda la pantalla pero solo dibuja al perro; el resto es transparente.
 */
class DogView(context: Context) : View(context) {

    companion object {
        /** Parte del alto de la pantalla que se reserva abajo para el perro y su cucha. */
        const val ZONE = 0.24f

        /** Cuántas veces por bloqueo viene a ladrar. */
        const val MAX_RUNS = 3

        private val BODY = 0xFFC98B4F.toInt()
        private val BODY_FAR = 0xFFA67240.toInt()
        private val BROWN = 0xFF8A5530.toInt()
        private val CREAM = 0xFFF6E3C3.toInt()
        private val WHITE = 0xFFFFFFFF.toInt()
        private val DARK = 0xFF2A1A12.toInt()
        private val RED = 0xFFE0454F.toInt()
        private val GOLD = 0xFFFFC312.toInt()
        private val PINK = 0xFFFF8FA3.toInt()
    }

    /** Suena en el momento exacto de cada ladrido. */
    var onBark: (() -> Unit)? = null

    private enum class State { WALK, TO_HOUSE, SETTLE, SLEEP, WAKE, RUN, LEAP, BARK, DROP, WAG, RETURN }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()
    private val path = Path()

    // ---- medidas
    private var u0 = 1f      // "unidad" base en píxeles (la cucha y el cartel)
    private var du = 1f      // unidad del perro (un poco más grande)
    private var groundY = 0f
    private var signX = 0f
    private var houseX = 0f
    private var lyingX = 0f
    private var closeX = 0f
    private var placed = false

    // ---- estado
    private var state = State.WALK
    private var t = 0f
    private var clock = 0f
    private var last = 0L
    private var runsLeft = MAX_RUNS
    private var walkLeft = 6f
    private var pause = 0f
    private var targetX = 0f
    private var fromX = 0f
    private var runDur = 1f
    private var startLie = 1f
    private var peekT = -1f
    private var nextPeek = 4f
    private var phase = 0f
    private var barkIdx = 0

    // ---- pose de este cuadro
    private var curX = 0f
    private var dir = 1f          // 1: mira a la derecha, -1: a la izquierda
    private var lie = 0f          // 0 parado, 1 echado
    private var tail = 0f
    private var run = false
    private var moving = false
    private var headBob = 0f
    private var earFly = 0f
    private var tongue = 0f
    private var eye = 1f
    private var happy = false
    private var mark = false
    private var zzz = false
    private var mouth = 0f
    private var faceK = 0f        // 0: perfil, 1: cara pegada al vidrio
    private var sideMult = 1f
    private var hopY = 0f

    private val barkTimes = floatArrayOf(0.35f, 1.05f, 1.75f)
    private val pops = ArrayList<FloatArray>()     // momento y número de cada "¡GUAU!"
    private val ripples = ArrayList<FloatArray>()  // ondas de sonido

    /** Cualquier botón del control: si está dormido (o paseando) y le quedan salidas, viene a ladrar. */
    fun poke() {
        if (runsLeft <= 0) return
        if (state == State.WALK || state == State.TO_HOUSE || state == State.SETTLE || state == State.SLEEP) {
            runsLeft--
            startLie = lie
            go(State.WAKE)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        last = 0L
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val zone = h * ZONE
        u0 = zone / 122f
        du = u0 * 1.2f
        groundY = h - zone * 0.08f
        signX = w * 0.62f
        houseX = w * 0.86f
        lyingX = houseX + 10f * u0
        closeX = w / 2f
        if (!placed) {
            curX = -w * 0.05f // entra caminando desde la izquierda
            targetX = pickTarget()
            placed = true
        }
    }

    private fun pickTarget(): Float = width * (0.08f + Random.nextFloat() * 0.40f)

    private fun go(s: State) {
        if (s == State.BARK) barkIdx = 0
        if (s == State.WALK) walkLeft = 6f + Random.nextFloat() * 3f
        state = s
        t = 0f
    }

    private fun ease(x: Float): Float {
        val v = x.coerceIn(0f, 1f)
        return v * v * (3f - 2f * v)
    }

    private fun lerp(a: Float, b: Float, k: Float): Float = a + (b - a) * k

    // ------------------------------------------------------------ lógica

    private fun update(dt: Float) {
        clock += dt
        t += dt
        lie = 0f; tail = sin(clock * 7f) * 8f
        run = false; moving = false
        headBob = 0f; earFly = 0f; tongue = 0f; eye = 1f; happy = false
        mark = false; zzz = false; mouth = 0f; faceK = 0f; sideMult = 1f; hopY = 0f
        val walk = 48f * u0 * dt

        when (state) {
            State.WALK -> {
                walkLeft -= dt
                if (pause > 0f) {
                    pause -= dt
                    tail = sin(clock * 14f) * 22f // contento, olfateando
                } else if (abs(targetX - curX) <= walk) {
                    curX = targetX
                    pause = 1f + Random.nextFloat() * 1.5f
                    targetX = pickTarget()
                } else {
                    dir = if (targetX > curX) 1f else -1f
                    curX += dir * walk
                    moving = true
                }
                if (walkLeft <= 0f) go(State.TO_HOUSE)
            }

            State.TO_HOUSE -> {
                if (abs(lyingX - curX) <= walk) {
                    curX = lyingX
                    go(State.SETTLE)
                } else {
                    dir = if (lyingX > curX) 1f else -1f
                    curX += dir * walk
                    moving = true
                }
            }

            State.SETTLE -> {
                dir = -1f
                lie = ease(t / 1.1f)
                eye = 1f - ease((t - 0.6f) / 0.6f)
                tail = sin(clock * 5f) * 6f * (1f - lie)
                if (t >= 1.3f) go(State.SLEEP)
            }

            State.SLEEP -> {
                dir = -1f
                lie = 1f
                eye = 0f
                tail = 0f
                zzz = true
                hopY = sin(clock * 2f) * 0.4f // respira
                if (peekT >= 0f) {
                    peekT += dt
                    val p = peekT / 1.6f
                    val amt = min(p / 0.2f, (1f - p) / 0.2f).coerceIn(0f, 1f)
                    eye = 0.5f * amt // entreabre un ojo, de reojo
                    if (p >= 1f) {
                        peekT = -1f
                        nextPeek = 4f + Random.nextFloat() * 5f
                    }
                } else {
                    nextPeek -= dt
                    if (nextPeek <= 0f) peekT = 0f
                }
            }

            State.WAKE -> {
                lie = startLie * (1f - ease(t / 0.35f))
                mark = true
                dir = if (closeX > curX) 1f else -1f
                tail = sin(clock * 20f) * 20f
                if (t >= 0.5f) {
                    fromX = curX
                    runDur = max(0.7f, abs(closeX - curX) / (230f * u0))
                    go(State.RUN)
                }
            }

            State.RUN -> {
                val k = ease(t / runDur)
                curX = fromX + (closeX - fromX) * k
                run = true
                tongue = 1f
                earFly = -26f
                headBob = -1f
                tail = -5f
                sideMult = 1f + 0.5f * k // se viene acercando
                if (t >= runDur) {
                    curX = closeX
                    go(State.LEAP)
                }
            }

            State.LEAP -> {
                // Salta contra el vidrio: el perfil se achica y aparece la cara grande
                sideMult = 1.5f * (1f - ease(t / 0.3f))
                hopY = -sin(PI.toFloat() * min(t / 0.4f, 1f)) * 25f
                val k = min(t / 0.35f, 1f)
                faceK = ease(k) + 0.1f * sin(PI.toFloat() * k)
                if (t >= 0.4f) go(State.BARK)
            }

            State.BARK -> {
                faceK = 1f
                sideMult = 0f
                for (bt in barkTimes) {
                    val d = t - bt
                    val v = if (d < 0f) ease((d + 0.1f) / 0.1f) else 1f - d / 0.28f
                    mouth = max(mouth, v.coerceIn(0f, 1f))
                }
                if (barkIdx < barkTimes.size && t >= barkTimes[barkIdx]) {
                    pops.add(floatArrayOf(clock, barkIdx.toFloat()))
                    ripples.add(floatArrayOf(clock))
                    barkIdx++
                    onBark?.invoke()
                }
                if (t >= 2.4f) go(State.DROP)
            }

            State.DROP -> {
                faceK = 1f - ease(t / 0.3f)
                sideMult = 1.5f * ease(t / 0.3f)
                dir = if (houseX > curX) 1f else -1f
                if (t >= 0.35f) go(State.WAG)
            }

            State.WAG -> {
                sideMult = 1.5f
                happy = true
                tongue = 1f
                tail = sin(t * 28f) * 38f // mueve la cola
                hopY = -abs(sin(t * 9f)) * 2.5f
                if (t >= 1.4f) {
                    fromX = curX
                    runDur = max(0.8f, abs(lyingX - curX) / (210f * u0))
                    go(State.RETURN)
                }
            }

            State.RETURN -> {
                val k = ease(t / runDur)
                curX = fromX + (lyingX - fromX) * k
                dir = if (lyingX > fromX) 1f else -1f
                run = true
                tongue = 1f
                earFly = -22f
                headBob = -1f
                tail = -5f
                sideMult = 1f + 0.5f * (1f - ease(t / 0.6f)) // se aleja
                if (t >= runDur) {
                    curX = lyingX
                    go(State.SETTLE)
                }
            }
        }
        if (moving) phase += dt * 9f
        if (run) phase += dt * 20f
    }

    // ------------------------------------------------------------ dibujo

    override fun onDraw(c: Canvas) {
        if (width == 0 || height == 0) return
        val now = System.nanoTime()
        val dt = if (last == 0L) 0.016f else min(0.05f, (now - last) / 1_000_000_000f)
        last = now
        update(dt)

        // Piso
        fill.color = 0x17FFFFFF
        c.drawRect(width * 0.04f, groundY, width * 0.96f, groundY + max(1f, u0), fill)

        drawHouse(c)
        drawSign(c)

        // Con la cara pegada al vidrio se oscurece el fondo
        if (faceK > 0.01f) {
            fill.color = withAlpha(0xFF05060F.toInt(), (120 * faceK.coerceIn(0f, 1f)).toInt())
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        }

        if (sideMult > 0.02f) drawDogSide(c)
        if (faceK > 0.01f) drawFace(c)
        drawEffects(c)
        postInvalidateOnAnimation()
    }

    private fun circ(c: Canvas, x: Float, y: Float, r: Float, col: Int) {
        fill.color = col
        c.drawCircle(x, y, r, fill)
    }

    private fun rr(c: Canvas, x: Float, y: Float, w: Float, h: Float, r: Float, col: Int) {
        fill.color = col
        rect.set(x, y, x + w, y + h)
        c.drawRoundRect(rect, r, r, fill)
    }

    private fun line(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, w: Float, col: Int) {
        stroke.color = col
        stroke.strokeWidth = w
        c.drawLine(x1, y1, x2, y2, stroke)
    }

    private fun oval(c: Canvas, cx: Float, cy: Float, rx: Float, ry: Float, rot: Float, col: Int) {
        fill.color = col
        c.save()
        c.rotate(rot, cx, cy)
        rect.set(cx - rx, cy - ry, cx + rx, cy + ry)
        c.drawOval(rect, fill)
        c.restore()
    }

    private fun arc(c: Canvas, cx: Float, cy: Float, r: Float, a0: Float, sweep: Float, w: Float, col: Int) {
        stroke.color = col
        stroke.strokeWidth = w
        rect.set(cx - r, cy - r, cx + r, cy + r)
        c.drawArc(rect, a0, sweep, false, stroke)
    }

    private fun drawSign(c: Canvas) {
        val u = u0
        rr(c, signX - 3.5f * u, groundY - 112f * u, 7f * u, 112f * u, 2f * u, 0xFF8D6E4A.toInt())
        val x = signX + 3.5f * u
        val y = groundY - 108f * u
        fill.color = 0xFF2A2D55.toInt()
        rect.set(x, y, x + 88f * u, y + 48f * u)
        c.drawRoundRect(rect, 6f * u, 6f * u, fill)
        stroke.color = Pal.accentSoft
        stroke.strokeWidth = 1.6f * u
        c.drawRoundRect(rect, 6f * u, 6f * u, stroke)
        txt.color = Pal.text
        txt.alpha = 255
        txt.textSize = 17f * u
        c.drawText("SHHH…", x + 44f * u, y + 22f * u, txt)
        txt.color = Pal.muted
        txt.textSize = 9.5f * u
        c.drawText("NO MOLESTAR", x + 44f * u, y + 38f * u, txt)
    }

    private fun drawHouse(c: Canvas) {
        val u = u0
        val x = houseX
        val gy = groundY
        // Frente de madera con el techo a dos aguas
        path.reset()
        path.moveTo(x - 54f * u, gy)
        path.lineTo(x - 54f * u, gy - 50f * u)
        path.lineTo(x, gy - 90f * u)
        path.lineTo(x + 54f * u, gy - 50f * u)
        path.lineTo(x + 54f * u, gy)
        path.close()
        fill.color = 0xFFB5793F.toInt()
        c.drawPath(path, fill)
        // Tablas
        for (i in 1..4) {
            val xx = x - 54f * u + i * 21.6f * u
            val top = gy - (50f + 40f * (1f - abs(xx - x) / (54f * u))) * u * 0.98f
            line(c, xx, gy, xx, top, 1.2f * u, 0x21000000)
        }
        // Puerta en arco
        fill.color = 0xFF22150E.toInt()
        path.reset()
        path.moveTo(x - 20f * u, gy)
        path.lineTo(x - 20f * u, gy - 38f * u)
        rect.set(x - 20f * u, gy - 58f * u, x + 20f * u, gy - 18f * u)
        path.arcTo(rect, 180f, 180f)
        path.lineTo(x + 20f * u, gy)
        path.close()
        c.drawPath(path, fill)
        // Techo
        val roof = 0xFF8E3B2E.toInt()
        line(c, x - 62f * u, gy - 44f * u, x, gy - 94f * u, 8f * u, roof)
        line(c, x, gy - 94f * u, x + 62f * u, gy - 44f * u, 8f * u, roof)
        // Huellita
        oval(c, x, gy - 70f * u, 3.4f * u, 2.8f * u, 0f, CREAM)
        circ(c, x - 5f * u, gy - 72f * u, 1.3f * u, CREAM)
        circ(c, x - 2f * u, gy - 75f * u, 1.3f * u, CREAM)
        circ(c, x + 2f * u, gy - 75f * u, 1.3f * u, CREAM)
        circ(c, x + 5f * u, gy - 72f * u, 1.3f * u, CREAM)
    }

    /** Perro de perfil (mirando a la derecha; con dir = -1 se espeja). */
    private fun drawDogSide(c: Canvas) {
        val m = du * sideMult
        c.save()
        c.translate(curX, groundY + hopY * m)
        c.scale(m * dir, m)

        oval(c, 0f, 0f, 40f, 5f, 0f, 0x54000000)

        val by = lerp(-30f, -15f, lie)
        val ry = lerp(15f, 11f, lie)

        // Cola
        c.save()
        c.translate(-28f, lerp(-33f, -15f, lie))
        c.rotate(lerp(-15f, -80f, lie) + tail)
        line(c, 0f, 0f, -3.5f, -22f, 5.5f, BODY)
        circ(c, -3.5f, -22f, 3.4f, WHITE)
        c.restore()

        // Patas (las del lado lejano van más oscuras y se dibujan primero)
        val amp = if (run) 15f else if (moving) 8f else 0f
        val hipY = lerp(-22f, -10f, lie)
        val footY = -2.5f
        fun leg(hx: Float, fx0: Float, off: Float, col: Int, fwd: Float) {
            val sw = sin(phase + off) * amp
            val lift = if (amp > 0f) max(0f, cos(phase + off)) * (if (run) 6f else 3f) else 0f
            val fx = fx0 + sw + lie * fwd
            val fy = footY - lift
            line(c, hx, hipY, fx, fy, 7f, col)
            line(c, lerp(hx, fx, 0.55f), lerp(hipY, fy, 0.55f), fx, fy, 7f, WHITE)
            oval(c, fx + 2f, fy, 5.2f, 3f, 0f, WHITE)
        }
        leg(13f, 15f, 0.6f, BODY_FAR, 12f)
        leg(-29f, -29f, PI.toFloat() + 0.6f, BODY_FAR, 6f)

        // Cuerpo
        oval(c, 0f, by, 30f, ry, 0f, BODY)
        oval(c, 2f, by + ry * 0.55f, 22f, 7f, 0f, CREAM)
        oval(c, -6f, by - ry * 0.4f, 18f, 7.5f, 0f, BROWN)
        circ(c, 24f, by + 2f, lerp(11f, 9f, lie), WHITE)

        leg(20f, 22f, 0f, BODY, 10f)
        leg(-22f, -22f, PI.toFloat(), BODY, 4f)

        // Collar con chapita
        line(c, 26f, by - 10f, 31f, by + 7f, 4.6f, RED)
        circ(c, 31.5f, by + 9f, 2.6f, GOLD)

        // Cabeza
        val hx = lerp(37f, 39f, lie)
        val hy = lerp(-48f, -19f, lie) + headBob
        circ(c, hx, hy, 13f, BODY)
        oval(c, hx + 3f, hy + 6f, 11f, 6f, 0f, CREAM)
        rr(c, hx + 4f, hy - 1f, 17f, 11f, 5.5f, CREAM)
        oval(c, hx + 22f, hy + 0.5f, 3.8f, 3f, 0f, DARK)
        circ(c, hx + 21f, hy - 0.6f, 0.9f, WHITE)
        line(c, hx + 8f, hy + 8.5f, hx + 19f, hy + 7.5f, 1.2f, DARK)
        if (tongue > 0f) oval(c, hx + 16f, hy + 10.5f, 3f, 4.5f * tongue, 0f, PINK)

        // Ojo
        val ex = hx + 9f
        val ey = hy - 4f
        if (happy) {
            arc(c, ex, ey + 1.5f, 3.2f, 200f, 140f, 1.6f, DARK)
        } else if (eye < 0.12f) {
            arc(c, ex, ey - 1.2f, 3.2f, 27f, 126f, 1.5f, DARK)
        } else {
            circ(c, ex, ey, 2.7f, DARK)
            circ(c, ex + 0.8f, ey - 0.8f, 0.8f, WHITE)
            if (eye < 0.95f) { // párpado
                val lid = (1f - eye) * 6.6f
                fill.color = BODY
                c.drawRect(ex - 3.4f, ey - 3.6f, ex + 3.4f, ey - 3.6f + lid, fill)
                line(c, ex - 3.2f, ey - 3.4f + lid, ex + 3.2f, ey - 3.4f + lid, 1f, DARK)
            }
        }
        circ(c, ex + 1f, ey - 6.4f, if (mark) 2.9f else 2.1f, CREAM) // cejita

        // Oreja caída
        val ear = lerp(12f, 5f, lie) + earFly
        oval(c, hx - 5f, hy + 5f, 5.6f, 11.5f, ear, BROWN)
        c.restore()

        // Signos que no se espejan
        c.save()
        c.translate(curX, groundY + hopY * m)
        c.scale(m, m)
        if (mark) {
            txt.color = Pal.warn
            txt.alpha = 255
            txt.textSize = 26f
            c.drawText("!", dir * (hx + 2f), hy - 20f, txt)
        }
        if (zzz) {
            for (i in 0 until 3) {
                val age = ((clock / 3f) + i / 3f) % 1f
                txt.color = 0xFFC9CCFF.toInt()
                txt.alpha = (sin(age * PI.toFloat()) * 255f).toInt().coerceIn(0, 255)
                txt.textSize = 7f + age * 9f
                c.drawText("z", dir * (hx + 6f + age * 10f), hy - 18f - age * 26f, txt)
            }
            txt.alpha = 255
        }
        c.restore()
    }

    /** La cara de frente, pegada al vidrio, ladrando. */
    private fun drawFace(c: Canvas) {
        val k = faceK.coerceAtLeast(0f)
        val uF = height / 150f * k
        val cx = lerp(curX, width / 2f, k.coerceIn(0f, 1f))
        val cy = lerp(groundY - 40f * du, height * 0.5f, k.coerceIn(0f, 1f))
        val s = uF * (1f + 0.07f * mouth)
        c.save()
        c.translate(cx, cy - mouth * 3f * uF)
        c.scale(s, s)
        val fl = mouth * 10f

        oval(c, -38f, -2f, 12f, 26f, 18f + fl, BROWN)       // orejas
        oval(c, 38f, -2f, 12f, 26f, -18f - fl, BROWN)
        oval(c, 0f, 58f, 34f, 22f, 0f, BODY)                // pecho
        oval(c, 0f, 62f, 18f, 16f, 0f, WHITE)
        path.reset()                                        // collar
        path.moveTo(-28f, 36f)
        path.quadTo(0f, 58f, 28f, 36f)
        stroke.color = RED
        stroke.strokeWidth = 7f
        c.drawPath(path, stroke)
        circ(c, 0f, 54f, 4.2f, GOLD)

        circ(c, 0f, 0f, 38f, BODY)                          // cabeza
        oval(c, 0f, -6f, 7f, 32f, 0f, WHITE)                // franja blanca
        oval(c, -22f, -18f, 9f, 7f, 0f, BROWN)              // mancha
        oval(c, 0f, 14f, 19f, 15f, 0f, CREAM)               // hocico

        for (sx in intArrayOf(-1, 1)) {                     // ojos y cejitas
            circ(c, sx * 16f, -9f, 5.2f, DARK)
            circ(c, sx * 16f + 1.6f, -10.8f, 1.7f, WHITE)
            circ(c, sx * 16f, -18f, 3.3f, CREAM)
        }

        if (mouth > 0.05f) {                                // boca abierta ladrando
            oval(c, 0f, 23f + 7f * mouth, 12f, 3f + 10f * mouth, 0f, 0xFF5A1F26.toInt())
            oval(c, 0f, 27f + 9f * mouth, 7f, 4f + 4f * mouth, 0f, PINK)
            fill.color = WHITE
            path.reset()
            path.moveTo(-8f, 22f); path.lineTo(-5f, 22f); path.lineTo(-6.5f, 27f); path.close()
            c.drawPath(path, fill)
            path.reset()
            path.moveTo(8f, 22f); path.lineTo(5f, 22f); path.lineTo(6.5f, 27f); path.close()
            c.drawPath(path, fill)
        } else {
            line(c, 0f, 20f, 0f, 25f, 1.5f, DARK)
            arc(c, -5f, 25f, 5f, 0f, 180f, 1.5f, DARK)
            arc(c, 5f, 25f, 5f, 0f, 180f, 1.5f, DARK)
        }
        oval(c, 0f, 9f, 8f, 5.6f, 0f, DARK)                 // nariz
        circ(c, -2.4f, 7.2f, 1.8f, 0xB3FFFFFF.toInt())
        c.restore()
    }

    /** Ondas de sonido y los "¡GUAU!" de cada ladrido. */
    private fun drawEffects(c: Canvas) {
        val h = height.toFloat()
        val rit = ripples.iterator()
        while (rit.hasNext()) {
            val r = rit.next()
            val age = clock - r[0]
            if (age > 0.6f) {
                rit.remove()
                continue
            }
            val k = age / 0.6f
            stroke.color = withAlpha(0xFFFFFFFF.toInt(), ((1f - k) * 170).toInt())
            stroke.strokeWidth = max(2f, h * 0.006f)
            c.drawCircle(width / 2f, h * 0.55f, h * (0.12f + k * 0.38f), stroke)
        }
        val pit = pops.iterator()
        while (pit.hasNext()) {
            val p = pit.next()
            val age = clock - p[0]
            if (age > 0.7f) {
                pit.remove()
                continue
            }
            val left = p[1].toInt() % 2 == 0
            val pop = 0.7f + 0.5f * min(1f, age / 0.12f)
            txt.color = Pal.warn
            txt.alpha = ((1f - age / 0.7f).coerceIn(0f, 1f) * 255f).toInt()
            txt.textSize = h * 0.1f * pop
            c.save()
            c.translate(width * (if (left) 0.22f else 0.78f), h * 0.22f)
            c.rotate(if (left) -9f else 9f)
            c.drawText("¡GUAU!", 0f, 0f, txt)
            c.restore()
            txt.alpha = 255
        }
    }
}
