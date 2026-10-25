package com.gyrowheel

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
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
import kotlin.math.min
import kotlin.math.roundToInt

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
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        showConnect()
    }

    private fun showConnect() {
        val prefs = getSharedPreferences("gw", MODE_PRIVATE)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#20262E"))
            setPadding(80, 30, 80, 30)
        }
        val title = TextView(this).apply {
            text = "Gyro Wheel"; textSize = 32f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        }
        val info = TextView(this).apply {
            text = "Escribe la IP que muestra el programa del PC"
            setTextColor(Color.LTGRAY); gravity = Gravity.CENTER
        }
        val ip = EditText(this).apply {
            setText(prefs.getString("ip", ""))
            hint = "192.168.1.20"
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); gravity = Gravity.CENTER
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
    @Volatile var gas = 0f
    @Volatile var brake = 0f
    @Volatile var up = false
    @Volatile var down = false
    @Volatile var rangeDeg = 180f
    @Volatile var status = ""
    var onCenter: () -> Unit = {}
    var onRange: () -> Unit = {}

    private val brakeR = RectF(); private val gasR = RectF()
    private val upR = RectF(); private val downR = RectF()
    private val calR = RectF(); private val rangeR = RectF()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var wx = 0f; private var wy = 0f; private var wr = 0f

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val m = 20f
        val pw = w * 0.2f
        brakeR.set(m, m, m + pw, h - m)
        gasR.set(w - m - pw, m, w - m, h - m)
        val gap = 14f
        val bw = (w * 0.6f - 2 * m - 2 * gap) / 3f
        val bh = h * 0.16f
        val by = h - m - bh - 14f
        val x0 = w / 2f - (3 * bw + 2 * gap) / 2f
        downR.set(x0, by, x0 + bw, by + bh)
        calR.set(x0 + bw + gap, by, x0 + 2 * bw + gap, by + bh)
        upR.set(x0 + 2 * (bw + gap), by, x0 + 3 * bw + 2 * gap, by + bh)
        rangeR.set(w / 2f - bw * 0.9f, m, w / 2f + bw * 0.9f, m + bh * 0.8f)
        val availH = by - rangeR.bottom
        wr = min(availH / 2f - 12f, w * 0.28f)
        wx = w / 2f
        wy = rangeR.bottom + availH / 2f
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(0xFF20262E.toInt())
        pedal(c, brakeR, brake, 0xFFD93A35.toInt(), "Freno")
        pedal(c, gasR, gas, 0xFF2FAE66.toInt(), "Acelerador")

        val hub = wr * 0.3f
        c.save()
        c.rotate(steer * 135f, wx, wy)
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeWidth = wr * 0.14f
        p.color = 0xFFEEF1F4.toInt()
        c.drawCircle(wx, wy, wr, p)
        c.drawLine(wx - wr, wy, wx - hub, wy, p)
        c.drawLine(wx + hub, wy, wx + wr, wy, p)
        c.drawLine(wx, wy + hub, wx, wy + wr, p)
        p.color = 0xFFD93A35.toInt()
        c.drawLine(wx, wy - wr - wr * 0.1f, wx, wy - wr + wr * 0.1f, p)
        p.style = Paint.Style.FILL
        p.color = 0xFF2C343E.toInt()
        c.drawCircle(wx, wy, hub, p)
        c.restore()

        p.color = Color.WHITE
        p.textAlign = Paint.Align.CENTER
        p.textSize = wr * 0.28f
        c.drawText("${(steer * rangeDeg / 2f).roundToInt()}°", wx, wy + p.textSize / 3f, p)

        button(c, downR, "Marcha −", down)
        button(c, calR, "Centrar", false)
        button(c, upR, "Marcha +", up)
        button(c, rangeR, "Giro ${rangeDeg.roundToInt()}°", false)

        p.color = 0xFF9AA5B1.toInt()
        p.textSize = 22f
        c.drawText(status, width / 2f, height - 4f, p)
    }

    private fun pedal(c: Canvas, r: RectF, v: Float, color: Int, label: String) {
        p.style = Paint.Style.FILL
        p.color = 0xFF2C343E.toInt()
        c.drawRect(r, p)
        p.color = color
        c.drawRect(r.left, r.bottom - r.height() * v, r.right, r.bottom, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 3f; p.color = 0xFF4A5562.toInt()
        c.drawRect(r, p)
        p.style = Paint.Style.FILL
        p.color = Color.WHITE
        p.textAlign = Paint.Align.CENTER
        p.textSize = 34f
        c.drawText(label, r.centerX(), r.top + 44f, p)
        p.textSize = 44f
        c.drawText("${(v * 100).roundToInt()}%", r.centerX(), r.bottom - 22f, p)
    }

    private fun button(c: Canvas, r: RectF, label: String, pressed: Boolean) {
        p.style = Paint.Style.FILL
        p.color = if (pressed) 0xFF4A5562.toInt() else 0xFF2C343E.toInt()
        c.drawRoundRect(r, 18f, 18f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 3f; p.color = 0xFF4A5562.toInt()
        c.drawRoundRect(r, 18f, 18f, p)
        p.style = Paint.Style.FILL
        p.color = Color.WHITE
        p.textAlign = Paint.Align.CENTER
        p.textSize = min(r.height() * 0.42f, 34f)
        c.drawText(label, r.centerX(), r.centerY() + p.textSize / 3f, p)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        var b = 0f; var g = 0f; var u = false; var d = false
        val act = e.actionMasked
        val lifting = if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_POINTER_UP) e.actionIndex else -1
        if (act != MotionEvent.ACTION_CANCEL) {
            for (i in 0 until e.pointerCount) {
                if (i == lifting) continue
                val x = e.getX(i); val y = e.getY(i)
                val fresh = (act == MotionEvent.ACTION_DOWN || act == MotionEvent.ACTION_POINTER_DOWN) && i == e.actionIndex
                when {
                    brakeR.contains(x, y) -> b = ((brakeR.bottom - y) / brakeR.height()).coerceIn(0f, 1f)
                    gasR.contains(x, y) -> g = ((gasR.bottom - y) / gasR.height()).coerceIn(0f, 1f)
                    upR.contains(x, y) -> u = true
                    downR.contains(x, y) -> d = true
                    calR.contains(x, y) -> if (fresh) onCenter()
                    rangeR.contains(x, y) -> if (fresh) onRange()
                }
            }
        }
        brake = b; gas = g; up = u; down = d
        invalidate()
        return true
    }
}
