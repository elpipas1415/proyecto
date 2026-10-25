package com.gyrowheel

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Paleta vintage: verde carreras, crema, latón
private val VERDE = 0xFF1F3B30.toInt()
private val VERDE_OSCURO = 0xFF162B22.toInt()
private val CREMA = 0xFFEADFC0.toInt()
private val LATON = 0xFFB8923A.toInt()
private val LATON_OSCURO = 0xFF7A5F22.toInt()
private val TINTA = 0xFF2B2118.toInt()
private val TINTA_SUAVE = 0x992B2118.toInt()
private val ROJO = 0xFFB3342A.toInt()
private val VERDE_ACEL = 0xFF3E6B4A.toInt()

class MainActivity : Activity(), SensorEventListener {
    private lateinit var sm: SensorManager
    private var dash: DashView? = null
    private var addr: String? = null
    @Volatile private var running = false
    private var raw = 0f
    private var offset = 0f
    private var smooth = 0f
    private var hasReading = false
    private var range = 180f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Orientación bloqueada en horizontal (no gira al mover el celular)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        showConnect()
    }

    private fun showConnect() {
        val prefs = getSharedPreferences("gw", MODE_PRIVATE)
        val serif = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(VERDE)
            setPadding(80, 30, 80, 30)
        }
        val title = TextView(this).apply {
            text = "Gyro Wheel"; textSize = 34f; setTextColor(CREMA); gravity = Gravity.CENTER
            typeface = serif
        }
        val info = TextView(this).apply {
            text = "Escribe la IP que muestra el programa del PC"
            setTextColor(CREMA); gravity = Gravity.CENTER
        }
        val ip = EditText(this).apply {
            setText(prefs.getString("ip", ""))
            hint = "192.168.1.20"
            setTextColor(CREMA); setHintTextColor(Color.GRAY); gravity = Gravity.CENTER
            setSingleLine()
        }
        val go = Button(this).apply {
            text = "Conectar"
            setOnClickListener {
                val a = ip.text.toString().trim()
                if (a.isNotEmpty()) {
                    prefs.edit().putString("ip", a).apply()
                    addr = a
                    startDash()
                }
            }
        }
        box.addView(title); box.addView(info); box.addView(ip); box.addView(go)
        setContentView(box)
    }

    private fun startDash() {
        val d = DashView(this)
        d.onCenter = { offset = raw; smooth = 0f }
        d.onRange = {
            range = when (range) { 120f -> 180f; 180f -> 270f; 270f -> 360f; else -> 120f }
            d.rangeDeg = range
        }
        d.rangeDeg = range
        dash = d
        setContentView(d)
        startSensorsAndSending()
    }

    private fun startSensorsAndSending() {
        val d = dash ?: return
        val target = addr ?: return
        val s = sm.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (s == null) { d.status = "Este celular no tiene sensor de movimiento"; return }
        sm.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
        running = true
        Thread {
            try {
                val sock = DatagramSocket()
                val ipAddr = InetAddress.getByName(target)
                d.status = "Enviando a $target"
                while (running) {
                    val msg = String.format(
                        Locale.US, "%.4f,%.3f,%.3f,%d,%d",
                        d.steer, d.gas, d.brake, if (d.up) 1 else 0, if (d.down) 1 else 0
                    )
                    val b = msg.toByteArray()
                    sock.send(DatagramPacket(b, b.size, ipAddr, 5005))
                    d.postInvalidate()
                    Thread.sleep(16)
                }
                sock.close()
            } catch (e: Exception) {
                d.status = "Error: ${e.message}"
                d.postInvalidate()
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        if (dash != null && !running) startSensorsAndSending()
    }

    override fun onPause() {
        super.onPause()
        running = false
        sm.unregisterListener(this)
    }

    @Suppress("DEPRECATION")
    override fun onSensorChanged(e: SensorEvent) {
        val x = e.values[0]
        val y = e.values[1]
        val right: Float
        val up: Float
        when (windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_90 -> { right = -y; up = x }
            Surface.ROTATION_270 -> { right = y; up = -x }
            Surface.ROTATION_180 -> { right = -x; up = -y }
            else -> { right = x; up = y }
        }
        raw = Math.toDegrees(atan2(-right, up).toDouble()).toFloat()
        if (!hasReading) { hasReading = true; offset = raw }
        var delta = raw - offset
        while (delta > 180) delta -= 360
        while (delta < -180) delta += 360
        smooth += (delta - smooth) * 0.35f
        dash?.steer = (smooth / (range / 2f)).coerceIn(-1f, 1f)
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
}

class DashView(ctx: Context) : View(ctx) {
    @Volatile var steer = 0f
    /** Palanca única: -1 (freno a fondo) ... 0 (reposo) ... +1 (acelerador a fondo) */
    @Volatile var pedal = 0f
    @Volatile var up = false
    @Volatile var down = false
    @Volatile var rangeDeg = 180f
    @Volatile var status = ""
    var onCenter: () -> Unit = {}
    var onRange: () -> Unit = {}

    val gas: Float get() = deadzone(pedal)
    val brake: Float get() = deadzone(-pedal)
    private fun deadzone(x: Float) = if (x < 0.04f) 0f else (x - 0.04f) / 0.96f

    private val pedalR = RectF(); private val upR = RectF(); private val downR = RectF()
    private val calR = RectF(); private val rangeR = RectF(); private val tmp = RectF()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgPaint = Paint()
    private val fontNum = Typeface.create(Typeface.SERIF, Typeface.BOLD)
    private val fontLabel = Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC)
    private var wx = 0f; private var wy = 0f; private var wr = 0f
    private var trackCy = 0f; private var halfLen = 1f; private var knobH = 60f
    private var pedalId = -1
    private var gearCaptionY = 0f; private var gearCaptionX = 0f

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val m = 20f
        val sideW = w * 0.2f
        // Derecha: palanca única acelerador / freno
        pedalR.set(w - m - sideW, m, w - m, h - m)
        val hh = pedalR.height()
        knobH = hh * 0.085f
        trackCy = pedalR.centerY()
        halfLen = hh * 0.5f - hh * 0.22f - knobH / 2f
        // Izquierda: marchas en horizontal
        val gap = 14f
        val gb = (sideW - gap) / 2f
        val gy = h / 2f - gb / 2f
        downR.set(m, gy, m + gb, gy + gb)
        upR.set(m + gb + gap, gy, m + sideW, gy + gb)
        gearCaptionX = m + sideW / 2f
        gearCaptionY = gy - 18f
        // Centro: botones y volante
        val cbw = w * 0.2f
        val cbh = h * 0.13f
        rangeR.set(w / 2f - cbw / 2f, m, w / 2f + cbw / 2f, m + cbh)
        calR.set(w / 2f - cbw / 2f, h - m - 14f - cbh, w / 2f + cbw / 2f, h - m - 14f)
        val availH = calR.top - rangeR.bottom
        wr = min(availH / 2f - 12f, (pedalR.left - (m + sideW)) / 2f - 14f)
        wx = w / 2f
        wy = rangeR.bottom + availH / 2f
        bgPaint.shader = RadialGradient(
            w / 2f, h / 2f, max(w, h) * 0.7f,
            0xFF2C4D3E.toInt(), 0xFF142A21.toInt(), Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(c: Canvas) {
        c.drawPaint(bgPaint)
        drawPedalPanel(c)
        drawWheel(c)

        p.typeface = fontLabel
        p.style = Paint.Style.FILL
        p.color = CREMA
        p.textAlign = Paint.Align.CENTER
        p.textSize = 30f
        c.drawText("Marcha", gearCaptionX, gearCaptionY, p)
        button(c, downR, "−", down, downR.height() * 0.7f)
        button(c, upR, "+", up, downR.height() * 0.7f)
        button(c, calR, "Centrar", false, min(calR.height() * 0.42f, 34f))
        button(c, rangeR, "Giro ${rangeDeg.roundToInt()}°", false, min(rangeR.height() * 0.42f, 34f))

        p.typeface = fontLabel
        p.style = Paint.Style.FILL
        p.color = 0xFFB9B08F.toInt()
        p.textAlign = Paint.Align.CENTER
        p.textSize = 22f
        c.drawText(status, width / 2f, height - 4f, p)
    }

    private fun drawWheel(c: Canvas) {
        val hub = wr * 0.3f
        c.save()
        c.rotate(steer * 135f, wx, wy)
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeWidth = wr * 0.14f
        p.color = CREMA
        c.drawCircle(wx, wy, wr, p)
        c.drawLine(wx - wr, wy, wx - hub, wy, p)
        c.drawLine(wx + hub, wy, wx + wr, wy, p)
        c.drawLine(wx, wy + hub, wx, wy + wr, p)
        p.color = ROJO
        c.drawLine(wx, wy - wr - wr * 0.1f, wx, wy - wr + wr * 0.1f, p)
        p.style = Paint.Style.FILL
        p.color = VERDE_OSCURO
        c.drawCircle(wx, wy, hub, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 5f
        p.color = LATON
        c.drawCircle(wx, wy, hub, p)
        c.restore()

        p.style = Paint.Style.FILL
        p.typeface = fontNum
        p.color = CREMA
        p.textAlign = Paint.Align.CENTER
        p.textSize = wr * 0.26f
        c.drawText("${(steer * rangeDeg / 2f).roundToInt()}°", wx, wy + p.textSize / 3f, p)
    }

    private fun drawPedalPanel(c: Canvas) {
        val r = pedalR
        val pw = r.width()
        val hh = r.height()
        val cx = r.centerX()

        // Bisel de latón y placa crema
        p.style = Paint.Style.FILL
        p.color = LATON_OSCURO
        c.drawRoundRect(r, 28f, 28f, p)
        tmp.set(r.left + 6f, r.top + 6f, r.right - 6f, r.bottom - 6f)
        p.color = LATON
        c.drawRoundRect(tmp, 24f, 24f, p)
        tmp.set(r.left + 16f, r.top + 16f, r.right - 16f, r.bottom - 16f)
        p.color = CREMA
        c.drawRoundRect(tmp, 16f, 16f, p)

        // Porcentajes
        val gasV = gas
        val brakeV = brake
        val labelSize = pw * 0.072f
        val pctSize = pw * 0.17f
        p.textAlign = Paint.Align.CENTER

        p.typeface = fontLabel; p.textSize = labelSize; p.color = TINTA
        c.drawText("Acelerador", cx, r.top + 16f + labelSize + 12f, p)
        p.typeface = fontNum; p.textSize = pctSize
        p.color = if (gasV > 0f) VERDE_ACEL else TINTA_SUAVE
        c.drawText("${(gasV * 100).roundToInt()}%", cx, r.top + 16f + labelSize + 12f + pctSize + 8f, p)

        p.typeface = fontLabel; p.textSize = labelSize; p.color = TINTA
        c.drawText("Freno", cx, r.bottom - 16f - 12f, p)
        p.typeface = fontNum; p.textSize = pctSize
        p.color = if (brakeV > 0f) ROJO else TINTA_SUAVE
        c.drawText("${(brakeV * 100).roundToInt()}%", cx, r.bottom - 16f - 12f - labelSize - 10f, p)

        // Ranura con relleno desde el centro
        val trackTop = r.top + hh * 0.22f
        val trackBot = r.bottom - hh * 0.22f
        val gh = pw * 0.07f
        p.style = Paint.Style.FILL
        p.color = TINTA
        tmp.set(cx - gh, trackTop, cx + gh, trackBot)
        c.drawRoundRect(tmp, gh, gh, p)
        val ky = trackCy - pedal * halfLen
        if (pedal > 0f) {
            p.color = VERDE_ACEL
            tmp.set(cx - gh, ky, cx + gh, trackCy)
            c.drawRect(tmp, p)
        } else if (pedal < 0f) {
            p.color = ROJO
            tmp.set(cx - gh, trackCy, cx + gh, ky)
            c.drawRect(tmp, p)
        }

        // Marcas de la escala (cada 10 %)
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.BUTT
        p.color = TINTA
        for (i in -10..10) {
            val y = trackCy - i / 10f * halfLen
            val big = i % 5 == 0
            val len = if (big) pw * 0.2f else pw * 0.1f
            p.strokeWidth = if (i == 0) 6f else if (big) 4f else 2f
            c.drawLine(cx - gh - 6f - len, y, cx - gh - 6f, y, p)
            c.drawLine(cx + gh + 6f, y, cx + gh + 6f + len, y, p)
        }

        // Perilla de latón
        val kw = pw * 0.68f
        p.style = Paint.Style.FILL
        tmp.set(cx - kw / 2f, ky - knobH / 2f, cx + kw / 2f, ky + knobH / 2f)
        p.color = LATON_OSCURO
        c.drawRoundRect(tmp, 14f, 14f, p)
        tmp.set(tmp.left + 4f, tmp.top + 4f, tmp.right - 4f, tmp.bottom - 4f)
        p.color = LATON
        c.drawRoundRect(tmp, 11f, 11f, p)
        p.style = Paint.Style.STROKE
        p.color = TINTA
        p.strokeWidth = 4f
        c.drawLine(cx - kw / 2f + 14f, ky, cx + kw / 2f - 14f, ky, p)
        p.strokeWidth = 2f
        c.drawLine(cx - kw / 2f + 14f, ky - knobH * 0.25f, cx + kw / 2f - 14f, ky - knobH * 0.25f, p)
        c.drawLine(cx - kw / 2f + 14f, ky + knobH * 0.25f, cx + kw / 2f - 14f, ky + knobH * 0.25f, p)
    }

    private fun button(c: Canvas, r: RectF, label: String, pressed: Boolean, textSize: Float) {
        p.style = Paint.Style.FILL
        p.color = if (pressed) LATON_OSCURO else VERDE_OSCURO
        c.drawRoundRect(r, 18f, 18f, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 4f
        p.color = LATON
        c.drawRoundRect(r, 18f, 18f, p)
        p.style = Paint.Style.FILL
        p.typeface = fontNum
        p.color = CREMA
        p.textAlign = Paint.Align.CENTER
        p.textSize = textSize
        c.drawText(label, r.centerX(), r.centerY() + textSize / 3f, p)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val act = e.actionMasked
        if (act == MotionEvent.ACTION_CANCEL) {
            pedalId = -1; pedal = 0f; up = false; down = false
            invalidate()
            return true
        }
        val lifting = if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_POINTER_UP) e.actionIndex else -1
        if (act == MotionEvent.ACTION_DOWN || act == MotionEvent.ACTION_POINTER_DOWN) {
            val i = e.actionIndex
            val x = e.getX(i); val y = e.getY(i)
            when {
                pedalR.contains(x, y) -> pedalId = e.getPointerId(i)
                calR.contains(x, y) -> onCenter()
                rangeR.contains(x, y) -> onRange()
            }
        }
        if (lifting >= 0 && e.getPointerId(lifting) == pedalId) pedalId = -1

        var v = 0f; var u = false; var d = false
        for (i in 0 until e.pointerCount) {
            if (i == lifting) continue
            val x = e.getX(i); val y = e.getY(i)
            if (e.getPointerId(i) == pedalId) {
                v = ((trackCy - y) / halfLen).coerceIn(-1f, 1f)   // arriba acelera, abajo frena
            } else if (upR.contains(x, y)) {
                u = true
            } else if (downR.contains(x, y)) {
                d = true
            }
        }
        pedal = v; up = u; down = d   // al soltar, la palanca vuelve al centro
        invalidate()
        return true
    }
}
