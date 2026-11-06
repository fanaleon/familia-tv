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
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * El guardia de seguridad de la pantalla de bloqueo: un muñequito con gorra y uniforme, dibujado
 * por código (sin imágenes), que patrulla por abajo, se apoya en el cartel de "No molestar", se
 * cruza de brazos y se duerme (entreabriendo un ojo de vez en cuando para espiar).
 * Si se toca cualquier botón del control ([poke]): se despierta, se acerca a la pantalla, mira
 * para los dos lados, golpea el vidrio tres veces (suena [onKnock]) y mueve el dedo diciendo
 * que no. Después vuelve al cartel y se duerme otra vez.
 * Para que no se vuelva un juego, solo reacciona [MAX_RUNS] veces por bloqueo; después sigue dormido.
 * Ocupa toda la pantalla pero solo dibuja al guardia; el resto es transparente.
 */
class GuardView(context: Context) : View(context) {

    companion object {
        /** Parte del alto de la pantalla que se reserva abajo para el guardia patrullando. */
        const val ZONE = 0.24f

        /** Cuántas veces por bloqueo viene a golpear la pantalla. */
        const val MAX_RUNS = 3

        private val SKIN = 0xFFF1BE96.toInt()
        private val SKIN_DARK = 0xFFD99C73.toInt()
        private val HAIR = 0xFF2B1B14.toInt()
        private val PANTS = 0xFF1F2A44.toInt()
        private val SHIRT = 0xFF5B86B5.toInt()
        private val CAP = 0xFF1B2A4A.toInt()
        private val CAP_DARK = 0xFF121C33.toInt()
        private val GOLD = 0xFFF2C14E.toInt()
        private val TIE = 0xFFC8423F.toInt()
        private val BELT = 0xFF17171B.toInt()
        private val SHOE = 0xFF1B1B1F.toInt()
        private val BROW = 0xFF3A2418.toInt()
    }

    /** Suena cada vez que el guardia golpea la pantalla. */
    var onKnock: (() -> Unit)? = null

    private enum class State { WALK, TO_SIGN, SETTLE, SLEEP, STARTLE, APPROACH, LOOK, KNOCK, WAG, RETREAT }
    private enum class Arms { SWING, CROSSED, KNOCK, WAG }

    private val shirt = SHIRT
    private val sleeve = darken(SHIRT, 0.18f)
    private val sleeveHi = darken(SHIRT, 0.10f)

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
    private val shape = Path()

    // ---- medidas (se calculan al conocer el tamaño de la pantalla)
    private var u0 = 1f          // tamaño de una "unidad" del muñeco paseando, en píxeles
    private var groundY = 0f
    private var signX = 0f
    private var leanX = 0f
    private var uClose = 1f      // tamaño de la unidad cuando está pegado al vidrio
    private var closeX = 0f
    private var closeY = 0f
    private var placed = false

    // ---- estado
    private var state = State.WALK
    private var t = 0f
    private var clock = 0f
    private var last = 0L
    private var runsLeft = MAX_RUNS
    private var walkLeft = 7f
    private var pause = 0f
    private var targetX = 0f
    private var fromX = 0f
    private var knockIdx = 0
    private var startleTilt = 0f
    private var peekT = -1f
    private var nextPeek = 5f
    private var peekLeft = true
    private var walkPhase = 0f

    // ---- pose de este cuadro
    private var curX = 0f
    private var curY = 0f
    private var curU = 1f
    private var tilt = 0f
    private var headTilt = 0f
    private var headTurn = 0f
    private var eyeL = 1f
    private var eyeR = 1f
    private var arms = Arms.SWING
    private var wag = 0f
    private var fistZ = 0f
    private var brow = 0f
    private var hop = 0f
    private var mark = false
    private var zzz = false
    private var mouth = 0
    private var moving = false

    /** Golpes en el vidrio: x, y y el momento en que pasaron (para dibujar la onda). */
    private val ripples = ArrayList<FloatArray>()

    private val knockTimes = floatArrayOf(0.35f, 0.95f, 1.55f)

    /** Cualquier botón del control: si está dormido (o patrullando) y le quedan salidas, se despierta. */
    fun poke() {
        if (runsLeft <= 0) return
        if (state == State.WALK || state == State.TO_SIGN || state == State.SETTLE || state == State.SLEEP) {
            runsLeft--
            startleTilt = tilt
            go(State.STARTLE)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        last = 0L
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val zone = h * ZONE
        u0 = zone / 122f
        groundY = h - zone * 0.08f
        signX = w * 0.74f
        leanX = signX - 31f * u0
        uClose = h / 70f
        closeX = w / 2f
        closeY = h * 0.42f + 92f * uClose
        if (!placed) {
            curX = -w * 0.05f // entra caminando desde la izquierda
            targetX = pickTarget()
            placed = true
        }
    }

    private fun pickTarget(): Float = width * (0.08f + Random.nextFloat() * 0.42f)

    private fun go(s: State) {
        if (s == State.APPROACH) fromX = curX
        if (s == State.KNOCK) knockIdx = 0
        if (s == State.WALK) walkLeft = 7f + Random.nextFloat() * 3f
        state = s
        t = 0f
    }

    private fun ease(x: Float): Float {
        val v = x.coerceIn(0f, 1f)
        return v * v * (3f - 2f * v)
    }

    // ------------------------------------------------------------ lógica

    private fun update(dt: Float) {
        clock += dt
        t += dt
        tilt = 0f; headTilt = 0f; headTurn = 0f
        eyeL = 1f; eyeR = 1f
        arms = Arms.SWING; wag = 0f; fistZ = 0f; brow = 0f; hop = 0f
        mark = false; zzz = false; mouth = 0; moving = false
        curY = groundY; curU = u0
        val step = 62f * u0 * dt

        when (state) {
            State.WALK -> {
                walkLeft -= dt
                if (pause > 0f) {
                    pause -= dt
                    headTurn = sin(clock * 1.6f) * 0.7f // mira para los costados
                } else if (abs(targetX - curX) <= step) {
                    curX = targetX
                    pause = 1f + Random.nextFloat() * 1.5f
                    targetX = pickTarget()
                } else {
                    curX += if (targetX > curX) step else -step
                    moving = true
                }
                if (walkLeft <= 0f) go(State.TO_SIGN)
            }

            State.TO_SIGN -> {
                if (abs(leanX - curX) <= step) {
                    curX = leanX
                    go(State.SETTLE)
                } else {
                    curX += if (leanX > curX) step else -step
                    moving = true
                }
            }

            State.SETTLE -> {
                val k = ease(t / 1.4f)
                tilt = 9f * k
                headTilt = 8f * k
                if (t > 0.6f) arms = Arms.CROSSED
                val closing = ease((t - 0.5f) / 0.8f)
                eyeL = 1f - closing
                eyeR = 1f - closing
                if (t >= 1.4f) go(State.SLEEP)
            }

            State.SLEEP -> {
                tilt = 9f
                headTilt = 10f + sin(clock * 1.3f) * 1.4f
                arms = Arms.CROSSED
                zzz = true
                eyeL = 0f; eyeR = 0f
                hop = sin(clock * 2f) * 0.5f // respira
                if (peekT >= 0f) {
                    peekT += dt
                    val p = peekT / 1.6f
                    val amt = min(p / 0.2f, (1f - p) / 0.2f).coerceIn(0f, 1f)
                    if (peekLeft) eyeL = 0.5f * amt else eyeR = 0.5f * amt
                    headTilt -= 5f * amt
                    if (p >= 1f) {
                        peekT = -1f
                        nextPeek = 4f + Random.nextFloat() * 5f
                        peekLeft = !peekLeft
                    }
                } else {
                    nextPeek -= dt
                    if (nextPeek <= 0f) peekT = 0f
                }
            }

            State.STARTLE -> {
                tilt = startleTilt * (1f - ease(t / 0.2f))
                hop = -sin(PI.toFloat() * min(t / 0.45f, 1f)) * 12f
                mark = true
                brow = -0.5f
                if (t >= 0.55f) go(State.APPROACH)
            }

            State.APPROACH -> {
                val k = ease(t / 1.3f)
                curX = fromX + (closeX - fromX) * k
                curY = groundY + (closeY - groundY) * k
                curU = u0 * (uClose / u0).pow(k)
                moving = k < 0.9f
                brow = 0.2f
                if (t >= 1.3f) go(State.LOOK)
            }

            State.LOOK -> {
                closeUp()
                headTurn = when {
                    t < 0.35f -> -ease(t / 0.35f)
                    t < 0.9f -> -1f
                    t < 1.3f -> -1f + 2f * ease((t - 0.9f) / 0.4f)
                    t < 1.8f -> 1f
                    else -> 1f - ease((t - 1.8f) / 0.3f)
                }
                brow = 0.45f
                if (t >= 2.1f) go(State.KNOCK)
            }

            State.KNOCK -> {
                closeUp()
                arms = Arms.KNOCK
                brow = 0.45f
                for (kt in knockTimes) {
                    val d = t - kt
                    val v = if (d < 0f) 1f + d / 0.18f else 1f - d / 0.25f
                    fistZ = max(fistZ, v.coerceIn(0f, 1f))
                }
                if (knockIdx < knockTimes.size && t >= knockTimes[knockIdx]) {
                    knockIdx++
                    ripples.add(floatArrayOf(curX + 20f * curU, curY - 70f * curU, clock))
                    onKnock?.invoke()
                }
                if (t >= 2.0f) go(State.WAG)
            }

            State.WAG -> {
                closeUp()
                arms = Arms.WAG
                val inOut = min(1f, min(t / 0.3f, (2.7f - t) / 0.3f)).coerceIn(0f, 1f)
                wag = sin(t * 10f) * 25f * inOut
                headTurn = sin(t * 5f) * 0.35f * inOut
                brow = 1f
                mouth = 1
                if (t >= 2.7f) go(State.RETREAT)
            }

            State.RETREAT -> {
                val k = ease(t / 1.4f)
                val toX = width * 0.5f
                curX = closeX + (toX - closeX) * k
                curY = closeY + (groundY - closeY) * k
                curU = uClose * (u0 / uClose).pow(k)
                moving = k > 0.1f && k < 0.95f
                if (t >= 1.4f) {
                    curX = toX
                    go(State.TO_SIGN)
                }
            }
        }
        if (moving) walkPhase += dt * 9f
    }

    private fun closeUp() {
        curX = closeX; curY = closeY; curU = uClose
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

        drawSign(c)

        // Cuanto más cerca del vidrio, más se oscurece el fondo
        val closeAmt = (ln(curU / u0) / ln(uClose / u0)).coerceIn(0f, 1f)
        if (closeAmt > 0.01f) {
            fill.color = withAlpha(0xFF05060F.toInt(), (120 * closeAmt).toInt())
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        }

        drawGuard(c)
        drawRipples(c)
        postInvalidateOnAnimation()
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
        c.drawText(T("SHHH…"), x + 44f * u, y + 22f * u, txt)
        txt.color = Pal.muted
        txt.textSize = 9.5f * u
        c.drawText(T("NO MOLESTAR"), x + 44f * u, y + 38f * u, txt)
    }

    private fun drawRipples(c: Canvas) {
        val it = ripples.iterator()
        while (it.hasNext()) {
            val r = it.next()
            val age = clock - r[2]
            if (age > 0.5f) {
                it.remove()
                continue
            }
            val k = age / 0.5f
            stroke.color = withAlpha(0xFFFFFFFF.toInt(), ((1f - k) * 190).toInt())
            stroke.strokeWidth = max(2f, 3f * u0)
            c.drawCircle(r[0], r[1], (20f + k * 70f) * curU, stroke)
        }
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

    private fun drawGuard(c: Canvas) {
        c.save()
        c.translate(curX, curY + hop * curU)
        c.scale(curU, curU)

        // Sombra en el piso
        oval(c, 0f, 0f, 22f, 4.2f, 0f, 0x54000000)

        c.rotate(tilt)

        // Piernas
        val sw = if (moving) sin(walkPhase) else 0f
        val lx = -7f + sw * 9f
        val rx = 7f - sw * 9f
        val ll = if (moving) max(0f, sw) * 3f else 0f
        val rl = if (moving) max(0f, -sw) * 3f else 0f
        line(c, -6f, -40f, lx, -4f - ll, 9f, PANTS)
        line(c, 6f, -40f, rx, -4f - rl, 9f, PANTS)
        rr(c, lx - 6.5f, -6f - ll, 13f, 6f, 3f, SHOE)
        rr(c, rx - 6.5f, -6f - rl, 13f, 6f, 3f, SHOE)

        // Cuerpo con uniforme: camisa, corbata, chapa dorada, hombreras, radio y cinturón con hebilla
        rr(c, -17f, -81f, 34f, 45f, 11f, shirt)
        rr(c, -13f, -78f, 10f, 38f, 5f, 0x1AFFFFFF)
        shape.reset()
        shape.moveTo(-3f, -80f); shape.lineTo(3f, -80f); shape.lineTo(2.6f, -58f); shape.lineTo(0f, -53f); shape.lineTo(-2.6f, -58f)
        shape.close()
        fill.color = TIE
        c.drawPath(shape, fill)
        rr(c, -15f, -81f, 9f, 4.5f, 2f, CAP)                       // hombreras
        rr(c, 6f, -81f, 9f, 4.5f, 2f, CAP)
        rr(c, 10f, -78f, 5f, 9f, 1.6f, 0xFF2B2E36.toInt())         // radio en el hombro
        line(c, 13f, -78f, 13f, -88f, 1.2f, 0xFF2B2E36.toInt())
        circ(c, 12.5f, -75.5f, 0.9f, 0xFF6CE08A.toInt())
        circ(c, -9f, -73f, 3f, GOLD)                               // chapa
        circ(c, -9f, -73f, 1.4f, 0xFFFFE9A8.toInt())
        rr(c, -17f, -44f, 34f, 4.5f, 2f, BELT)
        rr(c, -3.5f, -44.6f, 7f, 5.6f, 1.4f, GOLD)
        rr(c, -1.6f, -43.2f, 3.2f, 2.8f, 0.8f, BELT)

        // Brazos
        val swing = if (moving) cos(walkPhase) * 6f else 0f
        if (arms != Arms.CROSSED) {
            line(c, -17f, -74f, -21f - swing * 0.2f, -54f, 8f, sleeve)
            line(c, -21f - swing * 0.2f, -54f, -22f + swing * 0.5f, -45f, 7f, sleeve)
            circ(c, -22f + swing * 0.5f, -42f, 4.6f, SKIN)
        }
        when (arms) {
            Arms.SWING -> {
                line(c, 17f, -74f, 21f - swing * 0.2f, -54f, 8f, sleeve)
                line(c, 21f - swing * 0.2f, -54f, 22f - swing * 0.5f, -45f, 7f, sleeve)
                circ(c, 22f - swing * 0.5f, -42f, 4.6f, SKIN)
            }
            Arms.CROSSED -> {
                line(c, -17f, -74f, -20f, -62f, 8f, sleeve)
                line(c, 17f, -74f, 20f, -60f, 8f, sleeve)
                line(c, -20f, -62f, 12f, -66f, 8f, sleeveHi)
                line(c, 20f, -60f, -12f, -64f, 8f, sleeve)
                circ(c, 12f, -66.5f, 4.6f, SKIN)
                circ(c, -12f, -64.5f, 4.6f, SKIN)
            }
            else -> Unit
        }

        drawHead(c)

        if (arms == Arms.KNOCK) {
            val r = 5f + 3f * fistZ
            val fx = 22f
            val fy = -68f - 3f * fistZ
            line(c, 17f, -74f, fx - 2f, fy + 6f, 8f, sleeve)
            circ(c, fx, fy, r, SKIN)
            for (i in -1..1) {
                line(c, fx - r * 0.6f, fy + i * r * 0.4f, fx + r * 0.2f, fy + i * r * 0.4f, 1f, SKIN_DARK)
            }
        }
        if (arms == Arms.WAG) {
            line(c, 17f, -74f, 29f, -62f, 8f, sleeve)
            line(c, 29f, -62f, 29f, -76f, 7f, sleeve)
            circ(c, 29f, -80f, 5.8f, SKIN)
            circ(c, 24.2f, -78f, 2.4f, SKIN)
            c.save()
            c.rotate(wag, 29f, -82f) // el dedo índice se mueve de lado a lado
            line(c, 29f, -82f, 29f, -96f, 3.6f, SKIN)
            c.restore()
            circ(c, 32.6f, -80f, 2.2f, SKIN_DARK)
            circ(c, 32f, -77f, 2f, SKIN_DARK)
        }

        if (mark) {
            txt.color = Pal.warn
            txt.alpha = 255
            txt.textSize = 24f
            c.drawText("!", 24f, -97f, txt)
        }
        if (zzz) {
            for (i in 0 until 3) {
                val age = ((clock / 3f) + i / 3f) % 1f
                txt.color = 0xFFC9CCFF.toInt()
                txt.alpha = (sin(age * PI.toFloat()) * 255f).toInt().coerceIn(0, 255)
                txt.textSize = 7f + age * 9f
                c.drawText("z", 20f + age * 10f, -106f - age * 26f, txt)
            }
            txt.alpha = 255
        }
        c.restore()
    }

    private fun drawHead(c: Canvas) {
        c.save()
        c.rotate(headTilt, 0f, -80f)
        val hx = headTurn * 1.5f
        val fx = headTurn * 5f

        rr(c, -4.5f, -84f, 9f, 8f, 2f, SKIN_DARK)                   // cuello
        circ(c, hx - 16f, -93f, 3.2f, SKIN)                         // orejas
        circ(c, hx + 16f, -93f, 3.2f, SKIN)
        circ(c, hx, -94f, 16f, SKIN)                                // cara

        fill.color = HAIR                                           // pelo (casi todo queda bajo la gorra)
        rect.set(hx - 16.8f, -94f - 16.8f, hx + 16.8f, -94f + 16.8f)
        c.drawArc(rect, 200f, 140f, false, fill)

        val oy = -97.8f - 1.4f * brow                               // cejas (debajo de la visera)
        val iy = -97.8f + 2.6f * brow
        line(c, fx - 10f, oy, fx - 3f, iy, 1.7f, HAIR)
        line(c, fx + 3f, iy, fx + 10f, oy, 1.7f, HAIR)

        eye(c, fx - 6.5f, -92f, eyeL, headTurn)
        eye(c, fx + 6.5f, -92f, eyeR, headTurn)

        circ(c, fx, -87f, 2.4f, SKIN_DARK)                          // nariz
        circ(c, fx * 0.7f - 10f, -85f, 2.6f, 0x47FF7878)            // cachetes
        circ(c, fx * 0.7f + 10f, -85f, 2.6f, 0x47FF7878)
        if (mouth == 1) {
            rr(c, fx - 2.8f, -82f, 5.6f, 3f, 1.4f, 0xFF7A2F2F.toInt())
        } else {                                                    // sonrisa tranquila
            stroke.color = BROW
            stroke.strokeWidth = 1.2f
            rect.set(fx - 3.4f, -85f, fx + 3.4f, -80f)
            c.drawArc(rect, 25f, 130f, false, stroke)
        }

        // Gorra: copa, banda, chapa dorada y visera (siguen el giro de la cabeza)
        val cx = hx * 1.3f
        fill.color = CAP
        rect.set(cx - 17.8f, -114f, cx + 17.8f, -90f)
        c.drawArc(rect, 180f, 180f, true, fill)
        rr(c, cx - 17.8f, -103.4f, 35.6f, 3.6f, 1.6f, CAP_DARK)
        rr(c, cx - 14f + fx * 0.5f, -103f, 28f, 3.8f, 1.9f, CAP_DARK)
        circ(c, fx * 0.8f, -108.5f, 2.8f, GOLD)
        circ(c, fx * 0.8f, -108.5f, 1.2f, 0xFFFFE9A8.toInt())
        c.restore()
    }

    private fun eye(c: Canvas, x: Float, y: Float, open: Float, turn: Float) {
        if (open < 0.12f) { // cerrado: una curvita
            stroke.color = BROW
            stroke.strokeWidth = 1.3f
            rect.set(x - 3.2f, y - 1.2f - 3.2f, x + 3.2f, y - 1.2f + 3.2f)
            c.drawArc(rect, 27f, 126f, false, stroke)
            return
        }
        shape.reset()
        rect.set(x - 3.7f, y - 3.9f * open, x + 3.7f, y + 3.9f * open)
        shape.addOval(rect, Path.Direction.CW)
        fill.color = 0xFFFFFFFF.toInt()
        c.drawPath(shape, fill)
        c.save()
        c.clipPath(shape)
        val px = x + turn * 1.6f
        val py = y + (1f - open) * 1.2f
        circ(c, px, py, 2f, 0xFF2A1A12.toInt())
        circ(c, px + 0.6f, py - 0.6f, 0.6f, 0xFFFFFFFF.toInt())
        c.restore()
        if (open < 0.95f) { // párpado
            val lid = (1f - open) * 4.2f
            fill.color = SKIN
            c.drawRect(x - 4.2f, y - 4.4f, x + 4.2f, y - 4.4f + lid, fill)
            line(c, x - 3.6f, y - 3.9f + lid, x + 3.6f, y - 3.9f + lid, 1f, BROW)
        }
    }
}
