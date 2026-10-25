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
import android.app.AlertDialog
import android.net.wifi.WifiManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.Locale
import kotlin.math.abs
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

/** Ajustes del usuario (se guardan en el celular). */
object Cfg {
    var haptics = true
    var level = 1          // 0 suave, 1 media, 2 fuerte
    var invert = false
    var dead = 0.04f       // zona muerta del pedal
    var smooth = 0.4f      // respuesta de la dirección
}

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
    private var vib: Vibrator? = null
    @Volatile private var searching = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Orientación bloqueada en horizontal (no gira al mover el celular)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        @Suppress("DEPRECATION")
        vib = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        loadCfg()
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
            text = "Gyromovil"; textSize = 34f; setTextColor(CREMA); gravity = Gravity.CENTER
            typeface = serif
        }
        val status = TextView(this).apply {
            setTextColor(LATON); gravity = Gravity.CENTER; setPadding(0, 10, 0, 10)
        }
        val ip = EditText(this).apply {
            setText(prefs.getString("ip", ""))
            hint = "o escribe la IP (192.168.1.20)"
            setTextColor(CREMA); setHintTextColor(Color.GRAY); gravity = Gravity.CENTER
            setSingleLine()
        }
        val go = Button(this).apply {
            text = "Conectar"
            setOnClickListener {
                searching = false
                val a2 = ip.text.toString().trim()
                if (a2.isNotEmpty()) {
                    prefs.edit().putString("ip", a2).apply()
                    addr = a2
                    startDash()
                }
            }
        }
        val find = {
            status.text = "Buscando tu PC…"
            discover({ found ->
                prefs.edit().putString("ip", found).apply()
                addr = found
                startDash()
            }, { status.text = "No encontré el PC. Abre el servidor en la PC o escribe la IP." })
        }
        val again = Button(this).apply { text = "Buscar de nuevo"; setOnClickListener { find() } }
        box.addView(title); box.addView(status); box.addView(ip); box.addView(go); box.addView(again)
        setContentView(box)
        find()
    }

    private fun startDash() {
        val d = DashView(this)
        d.onCenter = { offset = raw; smooth = 0f }
        d.onRange = {
            range = when (range) { 120f -> 180f; 180f -> 270f; 270f -> 360f; else -> 120f }
            d.rangeDeg = range
        }
        d.rangeDeg = range
        d.onHaptic = { buzz(it) }
        d.onSettings = { showSettings() }
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

    private fun loadCfg() {
        val p = getSharedPreferences("gw", MODE_PRIVATE)
        Cfg.haptics = p.getBoolean("hap", true)
        Cfg.level = p.getInt("lvl", 1)
        Cfg.invert = p.getBoolean("inv", false)
        Cfg.dead = p.getFloat("dead", 0.04f)
        Cfg.smooth = p.getFloat("smo", 0.4f)
    }

    private fun saveCfg() {
        getSharedPreferences("gw", MODE_PRIVATE).edit()
            .putBoolean("hap", Cfg.haptics).putInt("lvl", Cfg.level).putBoolean("inv", Cfg.invert)
            .putFloat("dead", Cfg.dead).putFloat("smo", Cfg.smooth).apply()
    }

    /** kind: 0 = marca del pedal, 1 = cambio de marcha, 2 = tope del pedal */
    private fun buzz(kind: Int) {
        if (!Cfg.haptics) return
        val v = vib ?: return
        if (!v.hasVibrator()) return
        val ms = when (kind) { 0 -> 12L; 1 -> 25L; else -> 40L }
        val amp = intArrayOf(60, 150, 255)[Cfg.level.coerceIn(0, 2)]
        if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(ms, amp))
        else @Suppress("DEPRECATION") v.vibrate(ms)
    }

    private fun showSettings() {
        fun label(t: String) = TextView(this).apply { text = t; textSize = 15f; setPadding(0, 24, 0, 6) }
        val hap = Switch(this).apply { text = "Vibración"; isChecked = Cfg.haptics }
        val lvl = SeekBar(this).apply { max = 2; progress = Cfg.level }
        val inv = Switch(this).apply { text = "Invertir dirección"; isChecked = Cfg.invert }
        val dead = SeekBar(this).apply { max = 15; progress = (Cfg.dead * 100f).roundToInt() }
        val resp = SeekBar(this).apply { max = 8; progress = ((Cfg.smooth - 0.1f) * 10f).roundToInt() }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 10)
            addView(hap)
            addView(label("Intensidad de la vibración (suave ← → fuerte)")); addView(lvl)
            addView(inv)
            addView(label("Zona muerta del pedal: 0 a 15 %")); addView(dead)
            addView(label("Respuesta de la dirección (suave ← → rápida)")); addView(resp)
        }
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("Ajustes")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Guardar") { _, _ ->
                Cfg.haptics = hap.isChecked
                Cfg.level = lvl.progress
                Cfg.invert = inv.isChecked
                Cfg.dead = dead.progress / 100f
                Cfg.smooth = 0.1f + resp.progress / 10f
                saveCfg()
                buzz(1)
            }
            .setNegativeButton("Cancelar", null)
            .setNeutralButton("Cambiar de PC") { _, _ ->
                running = false
                sm.unregisterListener(this)
                dash = null
                showConnect()
            }
            .show()
    }

    /** Busca el servidor del PC mandando un aviso a toda la red WiFi. */
    private fun discover(onFound: (String) -> Unit, onFail: () -> Unit) {
        searching = true
        Thread {
            var found: String? = null
            try {
                val sock = DatagramSocket()
                sock.broadcast = true
                sock.soTimeout = 700
                val msg = "GYRO?".toByteArray()
                val targets = mutableListOf("255.255.255.255")
                subnetBroadcast()?.let { targets.add(it) }
                val buf = ByteArray(32)
                var tries = 0
                while (searching && found == null && tries < 5) {
                    tries++
                    for (t in targets) {
                        try {
                            sock.send(DatagramPacket(msg, msg.size, InetAddress.getByName(t), 5005))
                        } catch (e: Exception) { }
                    }
                    try {
                        val reply = DatagramPacket(buf, buf.size)
                        sock.receive(reply)
                        if (String(reply.data, 0, reply.length) == "GYRO!") found = reply.address.hostAddress
                    } catch (e: java.net.SocketTimeoutException) { }
                }
                sock.close()
            } catch (e: Exception) { }
            val result = found
            runOnUiThread {
                if (searching) {
                    searching = false
                    if (result != null) onFound(result) else onFail()
                }
            }
        }.start()
    }

    @Suppress("DEPRECATION")
    private fun subnetBroadcast(): String? {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        val d = wm.dhcpInfo ?: return null
        if (d.ipAddress == 0) return null
        val b = (d.ipAddress and d.netmask) or d.netmask.inv()
        return "${b and 255}.${(b shr 8) and 255}.${(b shr 16) and 255}.${(b shr 24) and 255}"
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
        if (Cfg.invert) delta = -delta
        smooth += (delta - smooth) * Cfg.smooth
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
    var onSettings: () -> Unit = {}
    var onHaptic: (Int) -> Unit = {}
    private var lastStep = 0
    private val settingsR = RectF()

    val gas: Float get() = deadzone(pedal)
    val brake: Float get() = deadzone(-pedal)
    private fun deadzone(x: Float) = if (x < Cfg.dead) 0f else (x - Cfg.dead) / (1f - Cfg.dead)

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
        settingsR.set(m, m, m + sideW, m + cbh)
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
        button(c, settingsR, "Ajustes", false, min(settingsR.height() * 0.42f, 34f))

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
                settingsR.contains(x, y) -> onSettings()
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
        if (u && !up) onHaptic(1)          // vibración al cambiar de marcha
        if (d && !down) onHaptic(1)
        val step = (v * 10f).toInt()       // marca cada 10 % del pedal
        if (pedalId != -1 && step != lastStep) onHaptic(if (abs(step) >= 10) 2 else 0)
        lastStep = step
        pedal = v; up = u; down = d   // al soltar, la palanca vuelve al centro
        invalidate()
        return true
    }
}
