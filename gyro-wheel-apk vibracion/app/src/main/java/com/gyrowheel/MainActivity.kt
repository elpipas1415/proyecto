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
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.os.Handler
import android.os.Looper
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Paleta GT3: carbono, grafito, plata y acentos de volante de carreras
private val CARBON = 0xFF0F1113.toInt()
private val GRAFITO = 0xFF2B2F35.toInt()
private val GRAFITO_OSC = 0xFF15181B.toInt()
private val ALCANTARA = 0xFF3A3F46.toInt()
private val PLATA = 0xFFC3C8CE.toInt()
private val PLATA_OSC = 0xFF7D858E.toInt()
private val BLANCO = 0xFFF2F4F6.toInt()
private val NARANJA = 0xFFFF7A1A.toInt()
private val VERDE_GT = 0xFF2BD66A.toInt()
private val ROJO_GT = 0xFFF0392B.toInt()
private val AZUL_GT = 0xFF3A8DFF.toInt()
private val AMARILLO_GT = 0xFFFFC21A.toInt()
private val APAGADO = 0xFF4A5057.toInt()

/** Ajustes del usuario (se guardan en el celular). */
object Cfg {
    var haptics = true
    var level = 1          // 0 suave, 1 media, 2 fuerte
    var invert = false
    var dead = 0.04f       // zona muerta del pedal
    var smooth = 0.4f      // respuesta de la dirección
    var gradual = true     // vibración que crece con el pedal
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
    private val handler = Handler(Looper.getMainLooper())
    private var pumping = false
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
            setBackgroundColor(CARBON)
            setPadding(80, 30, 80, 30)
        }
        val title = TextView(this).apply {
            text = "Gyromovil"; textSize = 34f; setTextColor(BLANCO); gravity = Gravity.CENTER
            typeface = serif
        }
        val status = TextView(this).apply {
            setTextColor(NARANJA); gravity = Gravity.CENTER; setPadding(0, 10, 0, 10)
        }
        val ip = EditText(this).apply {
            setText(prefs.getString("ip", ""))
            hint = "o escribe la IP (192.168.1.20)"
            setTextColor(BLANCO); setHintTextColor(Color.GRAY); gravity = Gravity.CENTER
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
        d.onPedal = { startPump() }
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

    /** Mientras el pedal está activo, vibra cada vez más fuerte cuanto más lo empujas. */
    private val pump = object : Runnable {
        override fun run() {
            val d = dash
            val g = d?.gas ?: 0f
            val br = d?.brake ?: 0f
            val level = max(g, br)
            if (d != null && level > 0f && Cfg.haptics && Cfg.gradual) {
                val braking = br > g
                gradualBuzz(level, braking)
                handler.postDelayed(this, if (braking) 100L else 60L)
            } else {
                pumping = false
                vib?.cancel()
            }
        }
    }

    private fun startPump() {
        if (pumping || !Cfg.haptics || !Cfg.gradual) return
        pumping = true
        handler.post(pump)
    }

    /** Acelerar: zumbido continuo. Frenar: pulsos. Los dos crecen con la fuerza del pedal. */
    private fun gradualBuzz(level: Float, braking: Boolean) {
        val v = vib ?: return
        if (!v.hasVibrator()) return
        val scale = floatArrayOf(0.5f, 0.75f, 1f)[Cfg.level.coerceIn(0, 2)]
        val amp = (30 + 225 * level * scale).toInt().coerceIn(1, 255)
        val ms = if (braking) 50L else 70L
        if (Build.VERSION.SDK_INT >= 26) {
            // sin control de intensidad en el motor, se simula alargando el pulso
            val dur = if (v.hasAmplitudeControl()) ms else (ms * (0.3f + 0.7f * level)).toLong().coerceAtLeast(10L)
            v.vibrate(VibrationEffect.createOneShot(dur, amp))
        } else {
            @Suppress("DEPRECATION") v.vibrate(ms)
        }
    }

    private fun loadCfg() {
        val p = getSharedPreferences("gw", MODE_PRIVATE)
        Cfg.haptics = p.getBoolean("hap", true)
        Cfg.gradual = p.getBoolean("grad", true)
        Cfg.level = p.getInt("lvl", 1)
        Cfg.invert = p.getBoolean("inv", false)
        Cfg.dead = p.getFloat("dead", 0.04f)
        Cfg.smooth = p.getFloat("smo", 0.4f)
    }

    private fun saveCfg() {
        getSharedPreferences("gw", MODE_PRIVATE).edit()
            .putBoolean("hap", Cfg.haptics).putBoolean("grad", Cfg.gradual).putInt("lvl", Cfg.level).putBoolean("inv", Cfg.invert)
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
        val grad = Switch(this).apply { text = "Vibración gradual del pedal"; isChecked = Cfg.gradual }
        val lvl = SeekBar(this).apply { max = 2; progress = Cfg.level }
        val inv = Switch(this).apply { text = "Invertir dirección"; isChecked = Cfg.invert }
        val dead = SeekBar(this).apply { max = 15; progress = (Cfg.dead * 100f).roundToInt() }
        val resp = SeekBar(this).apply { max = 8; progress = ((Cfg.smooth - 0.1f) * 10f).roundToInt() }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 10)
            addView(hap)
            addView(grad)
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
                Cfg.gradual = grad.isChecked
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
        handler.removeCallbacks(pump)
        pumping = false
        vib?.cancel()
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
    var onPedal: () -> Unit = {}

    val gas: Float get() = deadzone(pedal)
    val brake: Float get() = deadzone(-pedal)
    private fun deadzone(x: Float) = if (x < Cfg.dead) 0f else (x - Cfg.dead) / (1f - Cfg.dead)

    private val pedalR = RectF(); private val upR = RectF(); private val downR = RectF()
    private val calR = RectF(); private val rangeR = RectF(); private val settingsR = RectF()
    private val ledR = RectF(); private val tmp = RectF()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgPaint = Paint()
    private val fontMono = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    private val fontCond = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    private var wx = 0f; private var wy = 0f; private var wr = 0f
    private var trackCy = 0f; private var halfLen = 1f; private var knobH = 60f
    private var pedalId = -1
    private var lastStep = 0
    private var gearCaptionX = 0f; private var gearCaptionY = 0f

    init {
        // Fondo de fibra de carbono: un cuadrito que se repite
        val t = 20
        val bmp = Bitmap.createBitmap(t, t, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val q = Paint()
        cv.drawColor(0xFF111316.toInt())
        q.color = 0xFF1A1D21.toInt()
        cv.drawRect(0f, 0f, t / 2f, t / 2f, q)
        cv.drawRect(t / 2f, t / 2f, t.toFloat(), t.toFloat(), q)
        q.color = 0xFF0C0D0F.toInt()
        cv.drawRect(t / 2f, 0f, t.toFloat(), t / 2f, q)
        bgPaint.shader = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val m = 20f
        val sideW = w * 0.2f
        val gap = 14f
        // Derecha: palanca única
        pedalR.set(w - m - sideW, m, w - m, h - m)
        val hh = pedalR.height()
        knobH = hh * 0.085f
        trackCy = pedalR.centerY()
        halfLen = hh * 0.5f - hh * 0.22f - knobH / 2f
        // Izquierda: ajustes, giro y marchas
        val cbh = h * 0.13f
        settingsR.set(m, m, m + sideW, m + cbh)
        rangeR.set(m, m + cbh + gap, m + sideW, m + 2 * cbh + gap)
        val gb = (sideW - gap) / 2f
        val gy = h / 2f - gb / 2f + cbh * 0.5f
        downR.set(m, gy, m + gb, gy + gb)
        upR.set(m + gb + gap, gy, m + sideW, gy + gb)
        gearCaptionX = m + sideW / 2f
        gearCaptionY = gy - 16f
        // Centro: tira de luces, volante y botón de centrar
        val cl = m + sideW + gap
        val cr = pedalR.left - gap
        val cbw = w * 0.2f
        ledR.set(cl, m, cr, m + h * 0.07f)
        calR.set(w / 2f - cbw / 2f, h - m - gap - cbh, w / 2f + cbw / 2f, h - m - gap)
        val availH = calR.top - ledR.bottom
        wr = min(availH / 2f - 12f, (cr - cl) / 2f - 6f)
        wx = (cl + cr) / 2f
        wy = ledR.bottom + availH / 2f
    }

    override fun onDraw(c: Canvas) {
        c.drawPaint(bgPaint)
        drawLeds(c)
        drawPedalPanel(c)
        drawWheel(c)

        p.style = Paint.Style.FILL
        p.typeface = fontCond
        p.color = PLATA_OSC
        p.textAlign = Paint.Align.CENTER
        p.textSize = 26f
        c.drawText("MARCHA", gearCaptionX, gearCaptionY, p)
        button(c, downR, "−", down, downR.height() * 0.7f, AZUL_GT)
        button(c, upR, "+", up, downR.height() * 0.7f, NARANJA)
        button(c, calR, "Centrar", false, min(calR.height() * 0.42f, 34f), PLATA_OSC)
        button(c, rangeR, "Giro ${rangeDeg.roundToInt()}°", false, min(rangeR.height() * 0.42f, 34f), PLATA_OSC)
        button(c, settingsR, "Ajustes", false, min(settingsR.height() * 0.42f, 34f), PLATA_OSC)

        p.typeface = fontMono
        p.color = APAGADO
        p.textSize = 22f
        c.drawText(status, width / 2f, height - 4f, p)
    }

    /** Tira de luces como la de un volante GT3: se enciende con el acelerador. */
    private fun drawLeds(c: Canvas) {
        val n = 15
        val cw = ledR.width() / n
        val rad = min(cw, ledR.height()) * 0.36f
        val lit = ceil(gas * n).toInt()
        p.style = Paint.Style.FILL
        for (i in 0 until n) {
            p.color = if (i < lit) {
                if (i < 5) VERDE_GT else if (i < 10) ROJO_GT else AZUL_GT
            } else 0xFF23272C.toInt()
            c.drawCircle(ledR.left + cw * (i + 0.5f), ledR.centerY(), rad, p)
        }
    }

    private fun drawWheel(c: Canvas) {
        val a = wr * 0.8f
        val b = wr * 0.6f
        c.save()
        c.rotate(steer * 135f, wx, wy)
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        // aro rectangular
        p.strokeWidth = wr * 0.12f
        p.color = GRAFITO
        tmp.set(wx - a, wy - b, wx + a, wy + b)
        c.drawRoundRect(tmp, b * 0.75f, b * 0.75f, p)
        // empuñaduras de alcántara
        p.strokeWidth = wr * 0.2f
        p.color = ALCANTARA
        c.drawLine(wx - a, wy - b * 0.45f, wx - a, wy + b * 0.45f, p)
        c.drawLine(wx + a, wy - b * 0.45f, wx + a, wy + b * 0.45f, p)
        // barra central
        p.strokeWidth = wr * 0.09f
        p.color = PLATA_OSC
        c.drawLine(wx - a, wy, wx + a, wy, p)
        // marca de las 12 en punto
        p.strokeCap = Paint.Cap.BUTT
        p.strokeWidth = wr * 0.07f
        p.color = NARANJA
        c.drawLine(wx, wy - b - wr * 0.09f, wx, wy - b + wr * 0.09f, p)
        // botones de colores
        p.style = Paint.Style.FILL
        for ((dx, col) in listOf(-0.6f to ROJO_GT, -0.38f to AMARILLO_GT, 0.38f to AZUL_GT, 0.6f to VERDE_GT)) {
            p.color = col
            c.drawCircle(wx + dx * a, wy - wr * 0.2f, wr * 0.055f, p)
        }
        // centro
        val hub = wr * 0.3f
        p.color = 0xFF050607.toInt()
        c.drawCircle(wx, wy, hub, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 5f
        p.color = NARANJA
        c.drawCircle(wx, wy, hub, p)
        c.restore()

        p.style = Paint.Style.FILL
        p.typeface = fontMono
        p.color = NARANJA
        p.textAlign = Paint.Align.CENTER
        p.textSize = wr * 0.24f
        c.drawText("${(steer * rangeDeg / 2f).roundToInt()}°", wx, wy + p.textSize / 3f, p)
    }

    private fun drawPedalPanel(c: Canvas) {
        val r = pedalR
        val pw = r.width()
        val cx = r.centerX()
        p.style = Paint.Style.FILL
        p.color = 0xFF0A0B0D.toInt()
        c.drawRoundRect(r, 22f, 22f, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 4f
        p.color = PLATA_OSC
        c.drawRoundRect(r, 22f, 22f, p)

        val gasV = gas
        val brakeV = brake
        val tag = pw * 0.075f
        val pct = pw * 0.17f
        p.style = Paint.Style.FILL
        p.textAlign = Paint.Align.CENTER
        p.typeface = fontCond; p.textSize = tag; p.color = PLATA
        c.drawText("ACEL", cx, r.top + 16f + tag + 8f, p)
        p.typeface = fontMono; p.textSize = pct
        p.color = if (gasV > 0f) VERDE_GT else APAGADO
        c.drawText("${(gasV * 100).roundToInt()}%", cx, r.top + 16f + tag + 8f + pct + 8f, p)
        p.color = if (brakeV > 0f) ROJO_GT else APAGADO
        c.drawText("${(brakeV * 100).roundToInt()}%", cx, r.bottom - 16f - tag - 22f, p)
        p.typeface = fontCond; p.textSize = tag; p.color = PLATA
        c.drawText("FRENO", cx, r.bottom - 16f, p)

        // barras por segmentos: verde sube, rojo baja
        val n = 10
        val step = halfLen / n
        val gap = 5f
        val bw = pw * 0.34f
        for (i in 0 until n) {
            tmp.set(cx - bw / 2f, trackCy - (i + 1) * step + gap / 2f, cx + bw / 2f, trackCy - i * step - gap / 2f)
            p.color = if (gasV * n > i) VERDE_GT else 0xFF1B1F23.toInt()
            c.drawRoundRect(tmp, 6f, 6f, p)
            tmp.set(cx - bw / 2f, trackCy + i * step + gap / 2f, cx + bw / 2f, trackCy + (i + 1) * step - gap / 2f)
            p.color = if (brakeV * n > i) ROJO_GT else 0xFF1B1F23.toInt()
            c.drawRoundRect(tmp, 6f, 6f, p)
        }

        // perilla de aluminio
        val ky = trackCy - pedal * halfLen
        val kw = pw * 0.8f
        tmp.set(cx - kw / 2f, ky - knobH / 2f, cx + kw / 2f, ky + knobH / 2f)
        p.color = PLATA_OSC
        c.drawRoundRect(tmp, 12f, 12f, p)
        tmp.set(tmp.left + 4f, tmp.top + 4f, tmp.right - 4f, tmp.bottom - 4f)
        p.color = PLATA
        c.drawRoundRect(tmp, 9f, 9f, p)
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.BUTT
        p.strokeWidth = 5f
        p.color = NARANJA
        c.drawLine(cx - kw / 2f + 14f, ky, cx + kw / 2f - 14f, ky, p)
    }

    private fun button(c: Canvas, r: RectF, label: String, pressed: Boolean, textSize: Float, accent: Int) {
        p.style = Paint.Style.FILL
        p.color = if (pressed) accent else GRAFITO_OSC
        c.drawRoundRect(r, 14f, 14f, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 4f
        p.color = accent
        c.drawRoundRect(r, 14f, 14f, p)
        p.style = Paint.Style.FILL
        p.typeface = fontCond
        p.color = if (pressed) CARBON else BLANCO
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
        if (u && !up) onHaptic(1)          // clic al cambiar de marcha
        if (d && !down) onHaptic(1)
        val step = (v * 10f).toInt()
        if (pedalId != -1 && step != lastStep && abs(step) >= 10) onHaptic(2)   // golpe al llegar al tope
        lastStep = step
        pedal = v; up = u; down = d   // al soltar, la palanca vuelve al centro
        if (pedalId != -1 && v != 0f) onPedal()
        invalidate()
        return true
    }
}
