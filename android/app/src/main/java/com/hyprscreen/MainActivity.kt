package com.hyprscreen

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.text.InputType
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/**
 * Phone side of Hypr Screen. Finds the laptop's hypr-screen service over USB
 * (adb reverse -> 127.0.0.1) or Wi-Fi (UDP discovery), then shows the H.264
 * stream of the Hyprland monitor it creates for us.
 *
 * Everything runs over TLS 1.3; the laptop's certificate is pinned.
 * Handshake:  laptop: HELLO 3 <id> <hostname> <nonce>
 *             phone:  HYPRSCREEN 3 <w> <h> <dpi> <client nonce> <HMAC proof> [device id]
 *             laptop: OK | DENIED | BUSY
 * Wi-Fi pairing (instead of HYPRSCREEN):
 *             phone:  PAIR 3 <sha256 commit of client nonce> <phone name>
 *             laptop: PAIRNONCE <server nonce>
 *             phone:  PAIRREVEAL <client nonce>       both show a code; users compare it
 *             phone:  PAIRCONFIRM | PAIRCANCEL
 *             laptop: PAIRED <device id> <secret> | DENIED
 * Then frames: u32 len | u8 flags (1 config, 2 key) | u32 pts ms | Annex-B
 */
class MainActivity : Activity(), SurfaceHolder.Callback {
    private data class Laptop(val id: String, val name: String, val host: String, val usb: Boolean)

    private lateinit var surfaceView: SurfaceView
    private lateinit var home: ScrollView
    private lateinit var statusText: TextView
    private lateinit var list: LinearLayout

    @Volatile private var surfaceReady = false
    @Volatile private var alive = true
    private val session = AtomicReference<Socket?>(null)
    private val sessionClaim = AtomicReference<Any?>(null)
    @Volatile private var autoUsb = false
    @Volatile private var visible = false
    private var found = listOf<Laptop>()
    /** Touch messages waiting to be written to the laptop (one batch per MotionEvent). */
    private val touchQueue = LinkedBlockingQueue<ByteArray>() // unbounded: releases are never dropped
    @Volatile private var streaming = false

    private lateinit var keyCatcher: KeyCatcher
    private lateinit var kbdButton: TextView
    private lateinit var padButton: TextView
    /** Trackpad mode: the screen works like a laptop touchpad instead of touching where you tap. */
    @Volatile private var trackpad = false
    /** What the laptop supports (from INFO; 0 for laptops before 0.4.0, which get plain touch). */
    @Volatile private var hostFeatures = 0
    /** Pointer ids we sent T_DOWN for and haven't released (UI thread only). */
    private val activeTouches = mutableSetOf<Int>()
    private var penHovered = false
    private var lastPenX = 0
    private var lastPenY = 0
    private val penExit = Runnable { touchQueue.offer(msg(S_AUX, 0, 0, 90 or (90 shl 8)) + msg(S_POS, 0, lastPenX, lastPenY)) }
    private lateinit var keyBar: LinearLayout
    private var stickyMods = 0
    private val modButtons = mutableMapOf<Int, TextView>()

    private val prefs by lazy { getSharedPreferences("paired", Context.MODE_PRIVATE) }
    private val settings by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }

    private lateinit var root: FrameLayout
    /** Size and codec of the stream being shown (from the laptop's INFO, or what we asked for). */
    @Volatile private var streamW = 0
    @Volatile private var streamH = 0
    private var imeOpen = false
    private var imeHeight = 0
    @Volatile private var userStop = false
    @Volatile private var streamedThisAttempt = false

    private val hevcDecoder: Boolean by lazy {
        runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { c ->
                !c.isEncoder && c.isHardwareAccelerated &&
                    c.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true) }
            }
        }.getOrDefault(false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        surfaceView = SurfaceView(this).also {
            it.holder.addCallback(this)
            it.setOnTouchListener { v, e -> onTouch(v, e); true }
            it.setOnGenericMotionListener { v, e -> onHover(v, e) } // pen hovering above the screen
        }
        home = buildHome()
        keyCatcher = KeyCatcher()
        keyBar = buildKeyBar()
        kbdButton = buildKeyboardButton()
        padButton = buildPadButton()
        root = FrameLayout(this).apply {
            setBackgroundColor(BG)
            addView(surfaceView, FrameLayout.LayoutParams(-1, -1))
            addView(keyCatcher, FrameLayout.LayoutParams(1, 1))
            addView(keyBar, FrameLayout.LayoutParams(-1, dp(46), Gravity.BOTTOM))
            addView(padButton, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.BOTTOM or Gravity.END).apply {
                setMargins(0, 0, dp(76), dp(16))
            })
            addView(kbdButton, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.BOTTOM or Gravity.END).apply {
                setMargins(0, 0, dp(16), dp(16))
            })
            addView(home, FrameLayout.LayoutParams(-1, -1))
        }
        setContentView(root)
        window.setDecorFitsSystemWindows(false)
        root.setOnApplyWindowInsetsListener { _, insets ->
            val ime = insets.getInsets(WindowInsets.Type.ime()).bottom
            val open = insets.isVisible(WindowInsets.Type.ime()) // also true for a floating keyboard
            keyBar.visibility = if (open && streaming) View.VISIBLE else View.GONE
            keyBar.translationY = -ime.toFloat()
            kbdButton.translationY = if (open) -(ime + dp(46)).toFloat() else 0f
            if (!open && kbdButton.y < 0) kbdButton.translationY = 0f
            padButton.translationY = kbdButton.translationY
            if (open != imeOpen || ime != imeHeight) {
                imeOpen = open; imeHeight = ime
                layoutVideo()
            }
            insets
        }
        root.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) layoutVideo()
        }
        applyOrientation()
        autoUsb = intent?.getBooleanExtra(EXTRA_AUTO_USB, false) == true
        thread(name = "discovery", isDaemon = true) { discoveryLoop() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_AUTO_USB, false)) autoUsb = true
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onStart() { super.onStart(); visible = true }
    override fun onStop() { visible = false; super.onStop() }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (sessionClaim.get() != null) disconnect() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    override fun onDestroy() {
        alive = false
        disconnect()
        super.onDestroy()
    }

    /** Back/explicit disconnect: tell the laptop it's on purpose (no reconnect grace), then close. */
    private fun disconnect() {
        userStop = true
        val s = session.get() ?: return
        if (streaming) {
            touchQueue.offer(msg(C_BYE, 0, 0, 0))
            thread(isDaemon = true) { Thread.sleep(250); runCatching { s.close() } }
        } else {
            runCatching { s.close() }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (streaming) touchQueue.offer(viewMsg()) // rotated: ask the laptop for a matching monitor
    }

    private fun applyOrientation() {
        requestedOrientation = when (settings.getInt("orient", 0)) {
            1 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            2 -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR // follows the phone, even with rotation lock on
            else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    /** Monitor size to ask for: the phone's current orientation, scaled by the resolution setting. */
    private fun viewSize(): Pair<Int, Int> {
        val b = windowManager.currentWindowMetrics.bounds
        val res = settings.getInt("res", 100)
        fun dim(v: Int) = maxOf(160, v * res / 100 / 8 * 8) // encoders like multiples of 8
        return dim(b.width()) to dim(b.height())
    }

    private fun viewMsg(): ByteArray = viewSize().let { (w, h) -> msg(C_VIEW, 0, w, h) }

    private fun msg(type: Int, a: Int, x: Int, y: Int): ByteArray =
        ByteBuffer.allocate(6).put(type.toByte()).put(a.toByte()).putShort(x.toShort()).putShort(y.toShort()).array()

    /** Fit the video into the screen, or into the space above the keyboard while it is open. */
    private fun layoutVideo() = runOnUiThread {
        val rw = root.width
        val rh = root.height
        if (rw == 0 || rh == 0) return@runOnUiThread
        val availH = if (imeOpen && streaming) maxOf(dp(80), rh - imeHeight - dp(46)) else rh
        val sw = if (streamW > 0) streamW else rw
        val sh = if (streamH > 0) streamH else rh
        val scale = minOf(rw.toFloat() / sw, availH.toFloat() / sh)
        val lp = FrameLayout.LayoutParams((sw * scale).toInt(), (sh * scale).toInt(),
            if (imeOpen && streaming) Gravity.TOP or Gravity.CENTER_HORIZONTAL else Gravity.CENTER)
        val cur = surfaceView.layoutParams as? FrameLayout.LayoutParams
        if (cur == null || cur.width != lp.width || cur.height != lp.height || cur.gravity != lp.gravity) {
            surfaceView.layoutParams = lp
        }
    }

    // ---------------------------------------------------------------- UI

    private fun buildHome(): ScrollView {
        fun text(size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
            textSize = size; setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }
        statusText = text(15f, SUBTLE).apply { setPadding(0, dp(4), 0, dp(20)) }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(32), dp(28), dp(32), dp(28))
            addView(text(28f, ACCENT, bold = true).apply { text = "Hypr Screen" })
            addView(statusText)
            addView(list)
            addView(text(13f, SUBTLE, bold = true).apply { text = "DISPLAY"; setPadding(0, dp(18), 0, dp(6)) })
            addView(settingsPanel())
            addView(text(13f, MUTED).apply {
                setPadding(0, dp(20), 0, 0)
                text = "Plug in over USB and your phone becomes a second monitor automatically.\n" +
                    "Over Wi-Fi, tap Pair and accept the request on the laptop (Wi-Fi must be on in the laptop's config)."
            })
        }
        setStatus("Looking for your laptop…")
        return ScrollView(this).apply {
            setBackgroundColor(BG)
            isFillViewport = true
            addView(col, FrameLayout.LayoutParams(-1, -2))
        }
    }

    private fun setStatus(msg: String) = runOnUiThread { statusText.text = msg }

    /** Tap a row to cycle its value. Applied when you next connect (rotation applies live). */
    private fun settingsPanel(): LinearLayout {
        data class Opt(val label: String, val key: String, val values: List<Int>, val names: List<String>, val def: Int)
        val opts = listOf(
            Opt("Resolution", "res", listOf(100, 75, 50), listOf("Full", "75%", "50% (battery saver)"), 100),
            Opt("Frame rate", "fps", listOf(0, 60, 30), listOf("Laptop default", "60 fps", "30 fps (battery saver)"), 0),
            Opt("Text size", "scale", listOf(0, 100, 125, 150, 200), listOf("Laptop default", "100%", "125%", "150%", "200%"), 0),
            Opt("Video codec", "codec", listOf(0, 1),
                listOf(if (hevcDecoder) "Auto (HEVC)" else "Auto (H.264)", "H.264 only"), 0),
        )
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(CARD) }
            setPadding(dp(18), dp(6), dp(18), dp(6))
        }
        fun row(label: String, value: () -> String, onTap: () -> Unit) {
            val v = TextView(this).apply { textSize = 15f; setTextColor(ACCENT); text = value() }
            panel.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, dp(10))
                addView(TextView(context).apply { text = label; textSize = 15f; setTextColor(Color.WHITE) },
                    LinearLayout.LayoutParams(0, -2, 1f))
                addView(v)
                setOnClickListener { onTap(); v.text = value() }
            })
        }
        for (o in opts) {
            fun cur() = settings.getInt(o.key, o.def).let { if (it in o.values) it else o.def }
            row(o.label, { o.names[o.values.indexOf(cur())] }) {
                val next = o.values[(o.values.indexOf(cur()) + 1) % o.values.size]
                settings.edit().putInt(o.key, next).apply()
            }
        }
        val orientNames = listOf("Landscape", "Portrait", "Auto-rotate")
        row("Orientation", { orientNames[settings.getInt("orient", 0).coerceIn(0, 2)] }) {
            settings.edit().putInt("orient", (settings.getInt("orient", 0).coerceIn(0, 2) + 1) % 3).apply()
            applyOrientation() // while connected, the laptop's monitor follows
        }
        return panel
    }

    private fun showStream(streaming: Boolean) = runOnUiThread {
        home.visibility = if (streaming) View.GONE else View.VISIBLE
        kbdButton.visibility = if (streaming) View.VISIBLE else View.GONE
        padButton.visibility = if (streaming && hostFeatures and H_TRACKPAD != 0) View.VISIBLE else View.GONE
        if (!streaming) { setTrackpad(false); activeTouches.clear() }
        if (!streaming) hideKeyboard()
    }

    private fun renderList(laptops: List<Laptop>) = runOnUiThread {
        if (laptops == found && list.childCount > 0) return@runOnUiThread
        found = laptops
        list.removeAllViews()
        if (laptops.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "No laptop found yet. Is the hypr-screen service running?"
                setTextColor(MUTED); textSize = 14f
            })
        }
        laptops.forEach { l -> list.addView(row(l)) }
    }

    private fun row(l: Laptop): View {
        val paired = l.usb || pairing(l.id) != null
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(CARD) }
            setPadding(dp(18), dp(14), dp(18), dp(14))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply { text = l.name; textSize = 17f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD })
                addView(TextView(context).apply {
                    text = if (l.usb) "USB cable" else "Wi-Fi · ${l.host}" + if (paired) "" else " · not paired yet"
                    textSize = 13f; setTextColor(SUBTLE)
                })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(TextView(context).apply {
                text = if (paired) "Connect" else "Pair"; textSize = 15f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (paired) Color.BLACK else ACCENT)
                background = GradientDrawable().apply {
                    cornerRadius = dp(20).toFloat(); setColor(if (paired) ACCENT else CARD2)
                }
                setPadding(dp(18), dp(8), dp(18), dp(8))
                setOnClickListener { if (paired) connect(l) else pairWith(l) }
            })
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------- pairing

    /** device is null for a USB pairing (shared secret), else this phone's own id on the laptop. */
    private data class Pairing(
        val id: String, val name: String, val secret: String, val fingerprint: String, val device: String? = null,
    )

    private fun pairing(id: String): Pairing? = prefs.getString("pair_$id", null)?.let { raw ->
        runCatching {
            val o = JSONObject(raw)
            Pairing(id, o.getString("name"), o.getString("secret"), o.getString("fingerprint"),
                o.optString("device").takeIf { it.matches(HEX12) })
        }.getOrNull()
    }

    private fun allPairings(): List<Pairing> =
        prefs.all.keys.filter { it.startsWith("pair_") }.mapNotNull { pairing(it.removePrefix("pair_")) }

    /**
     * The laptop writes pair-<id>.json into our app-specific storage with adb.
     * Other apps can't read or write that directory, so this is the only way a
     * pairing secret ever reaches us.
     */
    private fun importPairings() {
        val dir = getExternalFilesDir(null) ?: return
        dir.listFiles { f -> f.name.startsWith("pair-") && f.name.endsWith(".json") }?.forEach { f ->
            runCatching {
                val o = JSONObject(f.readText())
                val id = o.getString("id")
                require(id.matches(HEX12))
                require(o.getString("secret").matches(Regex("[0-9a-f]{64}")))
                require(o.getString("fingerprint").matches(Regex("[0-9a-f]{64}")))
                prefs.edit().putString("pair_$id", o.toString()).apply()
            }
            f.delete()
        }
    }

    // ---------------------------------------------------------------- discovery

    private fun discoveryLoop() {
        while (alive) {
            if (session.get() == null && visible) {
                importPairings()
                val laptops = mutableListOf<Laptop>()
                probeUsb()?.let(laptops::add)
                laptops += discoverWifi().filter { w -> laptops.none { it.id == w.id } }
                renderList(laptops)
                setStatus(if (laptops.isEmpty()) "Looking for your laptop…" else "Choose a laptop")
                val usb = laptops.firstOrNull { it.usb }
                if (autoUsb && usb != null && surfaceReady) {
                    autoUsb = false
                    runOnUiThread { connect(usb) }
                }
            }
            Thread.sleep(1500)
        }
    }

    /**
     * TLS connection that only trusts the given certificate fingerprints (pinning, no CA).
     * pins == null accepts any certificate: only for pairing, where the users' code check
     * covers the fingerprint.
     */
    private fun tlsConnect(host: String, pins: Set<String>?, timeoutMs: Int): SSLSocket {
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
                throw CertificateException("not a server")
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                val fp = sha256Hex(chain.firstOrNull()?.encoded ?: throw CertificateException("no cert"))
                if (pins != null && fp !in pins) throw CertificateException("unknown laptop certificate")
            }
        }
        val ctx = SSLContext.getInstance("TLSv1.3").apply { init(null, arrayOf(trust), null) }
        val plain = Socket()
        plain.connect(InetSocketAddress(host, PORT), timeoutMs)
        val s = ctx.socketFactory.createSocket(plain, host, PORT, true) as SSLSocket
        s.enabledProtocols = arrayOf("TLSv1.3")
        s.soTimeout = timeoutMs * 2
        s.startHandshake()
        return s
    }

    private fun serverFingerprint(s: SSLSocket): String =
        sha256Hex(s.session.peerCertificates.first().encoded)

    private fun probeUsb(): Laptop? {
        val pairings = allPairings()
        if (pairings.isEmpty()) return null
        return runCatching {
            tlsConnect("127.0.0.1", pairings.map { it.fingerprint }.toSet(), 500).use { s ->
                val hello = readLine(s.inputStream).split(" ")
                s.outputStream.write("PING\n".toByteArray()); s.outputStream.flush()
                val p = if (hello.size == 5 && hello[0] == "HELLO") pairing(hello[2]) else null
                if (p != null && p.fingerprint == serverFingerprint(s)) Laptop(p.id, p.name, "127.0.0.1", true) else null
            }
        }.getOrNull()
    }

    private fun discoverWifi(): List<Laptop> = runCatching {
        val out = mutableMapOf<String, Laptop>()
        DatagramSocket().use { u ->
            u.broadcast = true
            u.soTimeout = 500
            val msg = "HYPRSCREEN-DISCOVER 3".toByteArray()
            broadcastAddresses().forEach { a -> runCatching { u.send(DatagramPacket(msg, msg.size, a, PORT)) } }
            val buf = ByteArray(256)
            val end = System.currentTimeMillis() + 600
            while (System.currentTimeMillis() < end) {
                val p = DatagramPacket(buf, buf.size)
                try { u.receive(p) } catch (e: SocketTimeoutException) { break }
                val parts = String(p.data, 0, p.length).split(" ")
                if (parts.size >= 4 && parts[0] == "HYPRSCREEN-HERE" && parts[1] == "3") {
                    val id = parts[2]
                    // Discovery replies are unauthenticated; only the pinned TLS cert proves identity.
                    out.getOrPut(id) { Laptop(id, pairing(id)?.name ?: parts[3].take(64), p.address.hostAddress ?: continue, false) }
                }
            }
        }
        out.values.toList()
    }.getOrDefault(emptyList())

    private fun broadcastAddresses(): List<InetAddress> {
        val list = mutableListOf<InetAddress>(InetAddress.getByName("255.255.255.255"))
        runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.interfaceAddresses }
                .filter { it.address is Inet4Address }
                .mapNotNull { it.broadcast }
                .forEach(list::add)
        }
        return list
    }


    // ---------------------------------------------------------------- keyboard

    /** Bluetooth/USB keyboards attached to the phone: forward raw evdev scancodes. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val dev = event.device
        if (streaming && dev != null && !dev.isVirtual && event.scanCode in 1..247 &&
            event.keyCode != KeyEvent.KEYCODE_BACK
        ) {
            when {
                event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 -> sendMsg(K_RAW, 0, event.scanCode, 1)
                event.action == KeyEvent.ACTION_UP -> sendMsg(K_RAW, 0, event.scanCode, 0)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun sendMsg(type: Int, a: Int, x: Int, y: Int) {
        if (!streaming) return
        val b = ByteBuffer.allocate(6).put(type.toByte()).put(a.toByte()).putShort(x.toShort()).putShort(y.toShort())
        touchQueue.offer(b.array())
    }

    /** Sticky Ctrl/Alt/Super from the key bar apply to the next key, then reset. */
    private fun takeMods(): Int {
        val m = stickyMods
        if (m != 0) { stickyMods = 0; refreshModButtons() }
        return m
    }

    private fun sendKey(linuxCode: Int) = sendMsg(K_TAP, takeMods(), linuxCode, 0)

    private fun sendText(text: CharSequence) {
        var i = 0
        val str = text.toString()
        while (i < str.length) {
            val cp = str.codePointAt(i)
            sendMsg(K_CHAR, takeMods(), cp and 0xffff, cp ushr 16)
            i += Character.charCount(cp)
        }
    }

    private fun androidKey(keyCode: Int, e: KeyEvent?): Boolean {
        val code = when (keyCode) {
            KeyEvent.KEYCODE_DEL -> 14
            KeyEvent.KEYCODE_FORWARD_DEL -> 111
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> 28
            KeyEvent.KEYCODE_TAB -> 15
            KeyEvent.KEYCODE_ESCAPE -> 1
            KeyEvent.KEYCODE_DPAD_UP -> 103
            KeyEvent.KEYCODE_DPAD_DOWN -> 108
            KeyEvent.KEYCODE_DPAD_LEFT -> 105
            KeyEvent.KEYCODE_DPAD_RIGHT -> 106
            KeyEvent.KEYCODE_MOVE_HOME -> 102
            KeyEvent.KEYCODE_MOVE_END -> 107
            KeyEvent.KEYCODE_PAGE_UP -> 104
            KeyEvent.KEYCODE_PAGE_DOWN -> 109
            else -> 0
        }
        if (code != 0) { sendKey(code); return true }
        val ch = e?.unicodeChar ?: 0
        if (ch != 0) { sendText(String(Character.toChars(ch))); return true }
        return false
    }

    private fun toggleKeyboard() {
        val imm = getSystemService(InputMethodManager::class.java)
        val shown = window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
        if (shown) hideKeyboard() else {
            keyCatcher.requestFocus()
            imm.showSoftInput(keyCatcher, 0)
        }
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(keyCatcher.windowToken, 0)
    }

    /** Invisible editor that receives the soft keyboard's input. */
    private inner class KeyCatcher : View(this@MainActivity) {
        init { isFocusable = true; isFocusableInTouchMode = true }
        override fun onCheckIsTextEditor() = true
        override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
            (streaming && androidKey(keyCode, event)) || super.onKeyDown(keyCode, event)
        override fun onCreateInputConnection(out: EditorInfo): InputConnection {
            out.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            out.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or
                EditorInfo.IME_ACTION_NONE
            return object : BaseInputConnection(this, false) {
                private var composing = ""
                private fun replaceComposing(new: String) {
                    val common = composing.commonPrefixWith(new).length
                    repeat(composing.length - common) { sendKey(14) }
                    if (new.length > common) sendText(new.substring(common))
                    composing = new
                }
                override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                    val t = text.toString()
                    if (composing.isNotEmpty()) { replaceComposing(t); composing = "" } else sendText(t)
                    return true
                }
                override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
                    replaceComposing(text.toString()); return true
                }
                override fun finishComposingText(): Boolean { composing = ""; return true }
                override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                    repeat(beforeLength) { sendKey(14) }
                    repeat(afterLength) { sendKey(111) }
                    return true
                }
                override fun sendKeyEvent(event: KeyEvent): Boolean {
                    if (event.action == KeyEvent.ACTION_DOWN) androidKey(event.keyCode, event)
                    return true
                }
                override fun performEditorAction(actionCode: Int): Boolean { sendKey(28); return true }
            }
        }
    }

    private fun buildKeyboardButton() = TextView(this).apply {
        text = "⌨"
        textSize = 22f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#99000000")) }
        alpha = 0.7f
        visibility = View.GONE
        // drag anywhere; tap toggles the keyboard
        var downX = 0f; var downY = 0f; var startX = 0f; var startY = 0f; var dragging = false
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = v.x; startY = v.y; dragging = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (dragging || dx * dx + dy * dy > slop * slop) {
                        dragging = true
                        v.x = startX + dx; v.y = startY + dy
                    }
                }
                MotionEvent.ACTION_UP -> if (!dragging) toggleKeyboard()
            }
            true
        }
    }

    private fun buildPadButton() = TextView(this).apply {
        text = "🖱"
        textSize = 20f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#99000000")) }
        alpha = 0.7f
        visibility = View.GONE
        contentDescription = "Trackpad mode"
        setOnClickListener { setTrackpad(!trackpad) }
    }

    private fun setTrackpad(on: Boolean) = runOnUiThread {
        if (trackpad && !on) padRelease()
        if (on) releaseTouches() // a finger resting on the screen must not stay pressed on the laptop
        trackpad = on
        (padButton.background as GradientDrawable).setColor(if (on) ACCENT else Color.parseColor("#99000000"))
        padButton.setTextColor(if (on) Color.BLACK else Color.WHITE)
        padButton.alpha = if (on) 0.9f else 0.7f
    }

    private fun buildKeyBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#2A2A3C"))
            visibility = View.GONE
        }
        fun key(label: String, onTap: () -> Unit) = TextView(this).apply {
            text = label; textSize = 15f; gravity = Gravity.CENTER
            setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            setOnClickListener { onTap() }
            bar.addView(this, LinearLayout.LayoutParams(0, -1, 1f))
        }
        key("Esc") { sendKey(1) }
        key("Tab") { sendKey(15) }
        listOf(MOD_CTRL to "Ctrl", MOD_ALT to "Alt", MOD_SUPER to "Super").forEach { (bit, label) ->
            modButtons[bit] = key(label) { stickyMods = stickyMods xor bit; refreshModButtons() }
        }
        key("←") { sendKey(105) }
        key("↑") { sendKey(103) }
        key("↓") { sendKey(108) }
        key("→") { sendKey(106) }
        return bar
    }

    private fun refreshModButtons() = runOnUiThread {
        modButtons.forEach { (bit, v) ->
            v.setTextColor(if (stickyMods and bit != 0) Color.BLACK else Color.WHITE)
            v.background = if (stickyMods and bit != 0) GradientDrawable().apply { setColor(ACCENT); cornerRadius = dp(8).toFloat() } else null
        }
    }

    // ---------------------------------------------------------------- touch

    /** Encode one MotionEvent as touch messages: type, slot, x, y (0..65535) + sync. */
    /** Index of the stylus pointer in this event, or -1 (also -1 if the laptop has no pen support). */
    private fun penIndex(e: MotionEvent): Int {
        if (hostFeatures and H_PEN == 0) return -1
        for (i in 0 until e.pointerCount) {
            val t = e.getToolType(i)
            if (t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER) return i
        }
        return -1
    }

    private fun onHover(v: View, e: MotionEvent): Boolean {
        val pi = penIndex(e)
        if (!streaming || pi < 0) return false
        penHovered = true
        when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                surfaceView.removeCallbacks(penExit)
                sendPen(v, e, pi, touching = false, inRange = true)
            }
            // Android sends HOVER_EXIT right before the pen touches down: only leave range if no touch follows.
            MotionEvent.ACTION_HOVER_EXIT -> surfaceView.postDelayed(penExit, 120)
            else -> return false
        }
        return true
    }

    /** Lift every finger the laptop thinks is down (pen arrived, or trackpad mode switched on). */
    private fun releaseTouches() {
        if (activeTouches.isEmpty()) return
        val buf = ByteBuffer.allocate((activeTouches.size + 1) * 6)
        for (id in activeTouches) buf.put(msg(T_UP, id, 0, 0))
        buf.put(msg(T_SYNC, 0, 0, 0))
        activeTouches.clear()
        touchQueue.offer(buf.array())
    }

    /** Stylus: absolute position plus pressure, tilt, eraser and side button. */
    private fun sendPen(v: View, e: MotionEvent, i: Int, touching: Boolean, inRange: Boolean) {
        if (v.width == 0 || v.height == 0) return
        var flags = 0
        if (touching) flags = flags or PEN_TOUCH
        if (inRange) flags = flags or PEN_RANGE
        if (e.getToolType(i) == MotionEvent.TOOL_TYPE_ERASER) flags = flags or PEN_ERASER
        if (e.buttonState and (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_SECONDARY) != 0) flags = flags or PEN_BUTTON
        val x = (e.getX(i) / v.width * 65535f).toInt().coerceIn(0, 65535)
        val y = (e.getY(i) / v.height * 65535f).toInt().coerceIn(0, 65535)
        lastPenX = x; lastPenY = y
        val pressure = (e.getPressure(i).coerceIn(0f, 1f) * 65535f).toInt()
        val tiltDeg = Math.toDegrees(e.getAxisValue(MotionEvent.AXIS_TILT, i).toDouble())
        val orient = e.getAxisValue(MotionEvent.AXIS_ORIENTATION, i).toDouble()
        val tx = (tiltDeg * Math.sin(orient)).toInt().coerceIn(-90, 90) + 90
        val ty = (-tiltDeg * Math.cos(orient)).toInt().coerceIn(-90, 90) + 90
        val moving = e.actionMasked == MotionEvent.ACTION_MOVE || e.actionMasked == MotionEvent.ACTION_HOVER_MOVE
        if (moving && touchQueue.size > 64) return
        touchQueue.offer(msg(S_AUX, 0, pressure, tx or (ty shl 8)) + msg(S_POS, flags, x, y))
    }

    // ---- trackpad mode
    private var padLastX = 0f
    private var padLastY = 0f
    private var padCount = 0
    private var padMaxCount = 0
    private var padDownAt = 0L
    private var padMoved = 0f
    private var padDragging = false
    private var padSwipeStartX = 0f
    private var padSwipeDone = false
    private var padAccX = 0f
    private var padAccY = 0f
    private val padLongPress = Runnable {
        if (trackpad && padCount == 1 && padMoved < ViewConfiguration.get(this).scaledTouchSlop) {
            padDragging = true // hold, then move: drag with the left button held
            touchQueue.offer(msg(M_BTN, 0, 1, 0))
        }
    }

    private fun padRelease() {
        surfaceView.removeCallbacks(padLongPress)
        if (padDragging) touchQueue.offer(msg(M_BTN, 0, 0, 0))
        padDragging = false
    }

    private fun onTrackpad(e: MotionEvent) {
        val skip = if (e.actionMasked == MotionEvent.ACTION_POINTER_UP) e.actionIndex else -1
        var cx = 0f; var cy = 0f; var n = 0
        for (i in 0 until e.pointerCount) if (i != skip) { cx += e.getX(i); cy += e.getY(i); n++ }
        if (n > 0) { cx /= n; cy /= n }
        val density = resources.displayMetrics.density
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                padRelease()
                padDownAt = SystemClock.uptimeMillis(); padMoved = 0f; padMaxCount = 1; padCount = 1
                padLastX = cx; padLastY = cy; padAccX = 0f; padAccY = 0f
                surfaceView.postDelayed(padLongPress, 450)
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                padCount = n; padMaxCount = maxOf(padMaxCount, e.pointerCount)
                padLastX = cx; padLastY = cy; padAccX = 0f; padAccY = 0f // re-base: no jump
                surfaceView.removeCallbacks(padLongPress)
                if (n >= 3) { padSwipeStartX = cx; padSwipeDone = false }
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = cx - padLastX; val dy = cy - padLastY
                padLastX = cx; padLastY = cy
                padMoved += Math.abs(dx) + Math.abs(dy)
                if (padMoved > slop) surfaceView.removeCallbacks(padLongPress)
                when {
                    padMaxCount >= 3 -> if (n >= 3 && !padSwipeDone && Math.abs(cx - padSwipeStartX) > 90 * density) {
                        padSwipeDone = true // swipe left = next workspace, like a laptop touchpad
                        touchQueue.offer(msg(M_GESTURE, if (cx < padSwipeStartX) 1 else 2, 0, 0))
                    } // 3-finger touch: no pointer moves or scrolling, even while fingers lift one by one
                    n == 1 -> {
                        padAccX += dx / density * 1.6f; padAccY += dy / density * 1.6f
                        val mx = padAccX.toInt().coerceIn(-32000, 32000); val my = padAccY.toInt().coerceIn(-32000, 32000)
                        if (mx != 0 || my != 0) {
                            padAccX -= mx; padAccY -= my
                            if (touchQueue.size < 64) touchQueue.offer(msg(M_MOVE, 0, mx, my))
                        }
                    }
                    n == 2 -> { // natural scrolling: content follows the fingers; 120 = one wheel notch per 24 dp
                        padAccX += -dx / density * 5f; padAccY += -dy / density * 5f
                        val sx = padAccX.toInt().coerceIn(-32000, 32000); val sy = padAccY.toInt().coerceIn(-32000, 32000)
                        if (sx != 0 || sy != 0) {
                            padAccX -= sx; padAccY -= sy
                            touchQueue.offer(msg(M_SCROLL, 0, sx, sy))
                        }
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasDragging = padDragging
                padRelease()
                val quick = SystemClock.uptimeMillis() - padDownAt < 300 && padMoved < slop * 2
                if (!wasDragging && quick && e.actionMasked == MotionEvent.ACTION_UP && !padSwipeDone) {
                    val button = when (padMaxCount) { 1 -> 0; 2 -> 1; else -> 2 } // tap / 2-finger / 3-finger
                    touchQueue.offer(msg(M_BTN, button, 1, 0) + msg(M_BTN, button, 0, 0))
                }
                padCount = 0; padSwipeDone = false
            }
        }
    }

    private fun onTouch(v: View, e: MotionEvent) {
        if (!streaming || v.width == 0 || v.height == 0) return
        val pi = penIndex(e)
        if (pi >= 0) { // the pen wins: fingers (a resting palm) are ignored while it is on the screen
            releaseTouches()
            surfaceView.removeCallbacks(penExit)
            val penAction = e.actionIndex == pi
            when (e.actionMasked) {
                MotionEvent.ACTION_CANCEL -> sendPen(v, e, pi, touching = false, inRange = false)
                MotionEvent.ACTION_UP -> sendPen(v, e, pi, touching = false, inRange = penHovered)
                MotionEvent.ACTION_POINTER_UP -> if (penAction) sendPen(v, e, pi, touching = false, inRange = penHovered)
                MotionEvent.ACTION_POINTER_DOWN -> if (penAction) sendPen(v, e, pi, touching = true, inRange = true)
                else -> sendPen(v, e, pi, touching = true, inRange = true) // DOWN, MOVE
            }
            return
        }
        if (trackpad) { onTrackpad(e); return }
        val buf = ByteBuffer.allocate((e.pointerCount + 2) * 6)
        fun put(type: Int, i: Int) {
            val id = e.getPointerId(i)
            if (id >= 10) return
            val x = (e.getX(i) / v.width * 65535f).toInt().coerceIn(0, 65535)
            val y = (e.getY(i) / v.height * 65535f).toInt().coerceIn(0, 65535)
            if (type == T_DOWN) activeTouches.add(id) else if (type == T_UP) activeTouches.remove(id)
            buf.put(type.toByte()).put(id.toByte()).putShort(x.toShort()).putShort(y.toShort())
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> put(T_DOWN, e.actionIndex)
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) put(T_MOVE, i)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> put(T_UP, e.actionIndex)
            MotionEvent.ACTION_CANCEL -> for (i in 0 until e.pointerCount) put(T_UP, i)
            else -> return
        }
        buf.put(T_SYNC.toByte()).put(0).putShort(0).putShort(0)
        if (e.actionMasked == MotionEvent.ACTION_MOVE && touchQueue.size > 64) return // link is behind: skip moves only
        touchQueue.offer(buf.array().copyOf(buf.position()))
    }

    // ---------------------------------------------------------------- session

    private fun connect(l: Laptop) {
        val token = Any()
        if (!sessionClaim.compareAndSet(null, token)) return // already connecting/streaming
        userStop = false
        thread(name = "session", isDaemon = true) {
            var reconnectUntil = 0L
            try {
                while (true) {
                    var failure: Exception? = null
                    streamedThisAttempt = false
                    try {
                        runSession(l)
                    } catch (e: Exception) {
                        failure = e
                        Log.i(TAG, "session ended: $e")
                    } finally {
                        runCatching { session.getAndSet(null)?.close() }
                        streaming = false
                    }
                    val now = SystemClock.elapsedRealtime()
                    if (streamedThisAttempt) reconnectUntil = now + RECONNECT_MS
                    // Wi-Fi dropped (not Back, not a refusal): the laptop keeps the monitor for a while.
                    val dropped = failure == null || failure is IOException
                    if (!userStop && alive && !l.usb && dropped && now < reconnectUntil) {
                        showStream(false)
                        setStatus("Connection lost. Reconnecting to ${l.name}…")
                        Thread.sleep(1000)
                        if (userStop) break
                        continue
                    }
                    setStatus(if (failure == null || userStop) "Disconnected from ${l.name}" else failure.message ?: "Disconnected")
                    break
                }
            } finally {
                streaming = false
                sessionClaim.set(null)
                showStream(false)
            }
        }
    }

    // ---------------------------------------------------------------- Wi-Fi pairing

    private fun pairWith(l: Laptop) {
        val token = Any()
        if (!sessionClaim.compareAndSet(null, token)) return
        thread(name = "pairing", isDaemon = true) {
            var paired = false
            try {
                paired = runPairing(l)
            } catch (e: Exception) {
                Log.i(TAG, "pairing ended: $e")
                setStatus(e.message ?: "Pairing failed")
            } finally {
                runCatching { session.getAndSet(null)?.close() }
                runOnUiThread { pairDialog?.dismiss(); pairDialog = null }
                sessionClaim.set(null)
            }
            if (paired) runOnUiThread { connect(l) }
        }
    }

    private var pairDialog: AlertDialog? = null

    /**
     * Pair over the network. The laptop's certificate isn't known yet, so we accept it, then both
     * screens show a code derived from its fingerprint and both sides' nonces. We commit to our nonce
     * before seeing the laptop's, so an attacker in the middle can't make the codes match.
     */
    private fun runPairing(l: Laptop): Boolean {
        setStatus("Asking ${l.name} to pair…")
        val s = tlsConnect(l.host, null, 3000)
        session.set(s)
        val input = BufferedInputStream(s.inputStream)
        val out = s.outputStream
        val hello = readLine(input).split(" ")
        if (hello.size != 5 || hello[0] != "HELLO" || hello[1] != "3") error("Laptop needs updating (protocol mismatch)")
        if (hello[2] != l.id) error("Unexpected laptop identity")
        val fingerprint = serverFingerprint(s)
        val clientNonce = randomHex(16)
        val name = (Build.MODEL ?: "phone").replace(Regex("[^A-Za-z0-9._-]+"), "-")
            .trimStart { !it.isLetterOrDigit() }.take(40).ifEmpty { "phone" }
        out.write("PAIR 3 ${sha256Hex("hs-commit|$clientNonce".toByteArray())} $name\n".toByteArray()); out.flush()
        val reply = readLine(input).split(" ")
        when (reply.firstOrNull()) {
            "PAIRNONCE" -> {}
            "BUSY" -> error("${l.name} is busy with another request. Try again in a few seconds.")
            "DENIED" -> error("Wi-Fi pairing is turned off on ${l.name}")
            else -> error("${l.name} refused the request")
        }
        val serverNonce = reply.getOrNull(1)?.takeIf { it.matches(HEX32) } ?: error("Bad reply from laptop")
        out.write("PAIRREVEAL $clientNonce\n".toByteArray()); out.flush()
        val code = pairCode(fingerprint, clientNonce, serverNonce)

        // Events: the user's choice from the dialog, or lines from the laptop.
        val events = LinkedBlockingQueue<String>()
        s.soTimeout = 0
        thread(name = "pair-reader", isDaemon = true) {
            try { while (true) events.put(readLine(input)) } catch (e: Exception) { events.offer("EOF") }
        }
        runOnUiThread { showPairDialog(l, code, events) }
        setStatus("Type the code on ${l.name}, then tap Confirm")
        val end = System.currentTimeMillis() + PAIR_WAIT_MS
        var confirmed = false
        while (true) {
            val ev = events.poll(maxOf(1, end - System.currentTimeMillis()), TimeUnit.MILLISECONDS)
                ?: error("Pairing timed out")
            val parts = ev.split(" ")
            when (parts[0]) {
                "CONFIRM" -> {
                    confirmed = true
                    out.write("PAIRCONFIRM\n".toByteArray()); out.flush()
                    setStatus("Waiting for you to accept on ${l.name}…")
                }
                "CANCEL" -> {
                    runCatching { out.write("PAIRCANCEL\n".toByteArray()); out.flush() }
                    error("Pairing cancelled")
                }
                "PAIRED" -> {
                    if (!confirmed) error("Pairing failed: the laptop answered before you confirmed")
                    val device = parts.getOrNull(1)?.takeIf { it.matches(HEX12) } ?: error("Bad reply from laptop")
                    val secret = parts.getOrNull(2)?.takeIf { it.matches(HEX64) } ?: error("Bad reply from laptop")
                    val record = JSONObject().put("id", l.id).put("name", hello[3].take(64)).put("secret", secret)
                        .put("fingerprint", fingerprint).put("device", device)
                    prefs.edit().putString("pair_${l.id}", record.toString()).apply()
                    setStatus("Paired with ${l.name}")
                    return true
                }
                "DENIED" -> error("${l.name} rejected the request")
                else -> error("Pairing ended on ${l.name}")
            }
        }
    }

    private fun showPairDialog(l: Laptop, code: String, events: LinkedBlockingQueue<String>) {
        if (isFinishing || sessionClaim.get() == null) return
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(TextView(context).apply {
                text = "${code.take(3)} ${code.drop(3)}"
                textSize = 40f; typeface = Typeface.MONOSPACE; setTextColor(ACCENT)
                gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(12))
            })
            addView(TextView(context).apply {
                text = "Type this code in the window that opened on ${l.name} and press Enter, then tap Confirm.\n" +
                    "If no window opened on your laptop, tap Cancel."
                textSize = 14f
            })
        }
        pairDialog = AlertDialog.Builder(this)
            .setTitle("Pair with ${l.name}?")
            .setView(body)
            .setCancelable(false)
            .setPositiveButton("Confirm") { _, _ -> events.offer("CONFIRM") }
            .setNegativeButton("Cancel") { _, _ -> events.offer("CANCEL") }
            .show()
    }

    private fun pairCode(fingerprint: String, clientNonce: String, serverNonce: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest("hs-code|$fingerprint|$clientNonce|$serverNonce".toByteArray())
        val n = ((d[0].toLong() and 0xff) shl 24) or ((d[1].toLong() and 0xff) shl 16) or
            ((d[2].toLong() and 0xff) shl 8) or (d[3].toLong() and 0xff)
        return "%06d".format(n % 1_000_000)
    }

    private fun randomHex(bytes: Int): String =
        ByteArray(bytes).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    private fun runSession(l: Laptop) {
        setStatus("Connecting to ${l.name}…")
        val p = pairing(l.id) ?: error("Pair with ${l.name} first")
        val s = tlsConnect(l.host, setOf(p.fingerprint), 3000)
        session.set(s)
        if (!alive) error("Closing")
        s.tcpNoDelay = true
        s.soTimeout = 8000
        val input = BufferedInputStream(s.inputStream, 1 shl 20)
        val hello = readLine(input).split(" ")
        if (hello.size != 5 || hello[0] != "HELLO" || hello[1] != "3") error("Laptop needs updating (protocol mismatch)")
        if (hello[2] != p.id) error("Unexpected laptop identity")
        val clientNonce = randomHex(16)
        val proof = hmacHex(p.secret, "c|${hello[4]}|$clientNonce")
        val b = windowManager.maximumWindowMetrics.bounds
        // Laptops before 0.3.0 only read the handshake size: keep it the landscape full size as before.
        val w = maxOf(b.width(), b.height())
        val h = minOf(b.width(), b.height())
        s.outputStream.apply {
            val device = p.device?.let { " $it" } ?: ""
            write("HYPRSCREEN 3 $w $h ${resources.displayMetrics.densityDpi} $clientNonce $proof$device\n".toByteArray())
            flush()
        }
        when (readLine(input).split(" ").firstOrNull()) {
            "OK" -> {}
            // IOException: while reconnecting, the laptop may not have noticed the old connection drop yet
            "BUSY" -> throw IOException("${l.name} is already using another display")
            "DENIED" -> {
                prefs.edit().remove("pair_${p.id}").apply()
                error("${l.name} no longer knows this phone. Pair again, or connect by USB.")
            }
            else -> error("Laptop refused the connection")
        }
        if (!surfaceReady) error("Screen not ready")
        // 0.3.0 laptops: our features, preferences and the monitor size we want (older ones ignore these).
        val features = F_INFO or (if (hevcDecoder) F_HEVC else 0)
        val controls = ByteBuffer.allocate(6 * 6).apply {
            put(msg(C_HELLO, 0, features, 0))
            put(msg(C_PREF, P_FPS, settings.getInt("fps", 0), 0))
            put(msg(C_PREF, P_SCALE, settings.getInt("scale", 0), 0))
            put(msg(C_PREF, P_CODEC, settings.getInt("codec", 0), 0))
            put(msg(C_PREF, P_RES, settings.getInt("res", 100), 0))
            viewSize().let { (vw, vh) -> put(msg(C_VIEW, 0, vw, vh)) }
        }.array()
        s.outputStream.apply { write(controls); flush() }
        streamW = w; streamH = h
        mime = MediaFormat.MIMETYPE_VIDEO_AVC
        hostFeatures = 0
        s.soTimeout = 0
        setStatus("Connected to ${l.name}")
        touchQueue.clear()
        streaming = true
        val out = s.outputStream
        val writer = thread(name = "input-writer", isDaemon = true) {
            try {
                while (streaming) {
                    val msg = touchQueue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                    out.write(msg)
                    out.flush()
                }
            } catch (e: Exception) {
                runCatching { s.close() }
            }
        }
        try {
            stream(DataInputStream(input), w, h)
        } finally {
            streaming = false
            writer.interrupt()
        }
    }

    private fun stream(input: DataInputStream, w: Int, h: Int) {
        var decoder: Decoder? = null
        try {
            var buf = ByteArray(1 shl 20)
            while (alive) {
                val len = try { input.readInt() } catch (e: EOFException) { break }
                val flags = input.readUnsignedByte()
                val pts = input.readInt().toLong() and 0xffffffffL
                if (len < 0 || len > 64 shl 20) error("Bad frame")
                if (len > buf.size) buf = ByteArray(len)
                input.readFully(buf, 0, len)
                if (flags and FLAG_INFO != 0) { // new stream follows: "<w> <h> <h264|hevc>"
                    val p = String(buf, 0, minOf(len, 64)).trim().split(" ")
                    val iw = p.getOrNull(0)?.toIntOrNull() ?: continue
                    val ih = p.getOrNull(1)?.toIntOrNull() ?: continue
                    streamW = iw; streamH = ih
                    mime = if (p.getOrNull(2) == "hevc") MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
                    hostFeatures = p.getOrNull(3)?.toIntOrNull() ?: 0
                    decoder?.release()
                    decoder = null
                    layoutVideo()
                    continue
                }
                if (decoder == null) {
                    if (flags and FLAG_CONFIG == 0) continue // wait for SPS/PPS
                    decoder = Decoder(surfaceView.holder.surface, streamW, streamH, mime)
                    streamedThisAttempt = true // a picture arrived: worth reconnecting if this drops
                    showStream(true)
                    layoutVideo()
                }
                decoder.queue(buf, len, flags, pts * 1000)
            }
        } finally {
            decoder?.release()
        }
    }

    private fun readLine(input: InputStream): String {
        val sb = StringBuilder()
        while (sb.length < 512) {
            val c = input.read()
            if (c < 0) throw EOFException("Connection closed")
            if (c == '\n'.code) return sb.toString().trim()
            sb.append(c.toChar())
        }
        error("Line too long")
    }

    private fun hmacHex(secretHex: String, message: String): String {
        val key = secretHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
        return mac.doFinal(message.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    // ---------------------------------------------------------------- surface

    override fun surfaceCreated(holder: SurfaceHolder) { surfaceReady = true }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        disconnect() // the decoder can't outlive its surface
    }

    // ---------------------------------------------------------------- decoder

    @Volatile private var mime = MediaFormat.MIMETYPE_VIDEO_AVC

    private class Decoder(surface: Surface, w: Int, h: Int, mime: String) {
        private val codec = MediaCodec.createDecoderByType(mime)
        @Volatile private var running = true
        private val drain: Thread

        init {
            try {
                val fmt = MediaFormat.createVideoFormat(mime, w, h).apply {
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                    setInteger(MediaFormat.KEY_PRIORITY, 0) // realtime
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8 shl 20)
                }
                codec.configure(fmt, surface, null, 0)
                codec.start()
            } catch (e: Exception) {
                codec.release()
                throw e
            }
            drain = thread(name = "drain", isDaemon = true) {
                val info = MediaCodec.BufferInfo()
                try {
                    while (running) {
                        val idx = codec.dequeueOutputBuffer(info, 20_000)
                        if (idx >= 0) codec.releaseOutputBuffer(idx, true) // render now
                    }
                } catch (e: IllegalStateException) {
                    running = false
                }
            }
        }

        fun queue(data: ByteArray, len: Int, flags: Int, ptsUs: Long) {
            var idx: Int
            var tries = 0
            do {
                if (!running || !drain.isAlive) error("Decoder stopped")
                idx = codec.dequeueInputBuffer(50_000)
                if (++tries > 100) error("Decoder stalled") // ~5 s without a free buffer
            } while (idx < 0)
            val ib = codec.getInputBuffer(idx)!!
            ib.clear(); ib.put(data, 0, len)
            var f = 0
            if (flags and FLAG_CONFIG != 0) f = f or MediaCodec.BUFFER_FLAG_CODEC_CONFIG
            if (flags and FLAG_KEY != 0) f = f or MediaCodec.BUFFER_FLAG_KEY_FRAME
            codec.queueInputBuffer(idx, 0, len, ptsUs, f)
        }

        fun release() {
            running = false
            runCatching { drain.join(200) }
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
    }


    companion object {
        private const val TAG = "HyprScreen"
        const val PORT = 7878
        const val EXTRA_AUTO_USB = "autoconnect_usb"
        private val HEX12 = Regex("[0-9a-f]{12}")
        private val HEX32 = Regex("[0-9a-f]{32}")
        private val HEX64 = Regex("[0-9a-f]{64}")
        private const val PAIR_WAIT_MS = 95_000L
        private const val FLAG_CONFIG = 1
        private const val FLAG_KEY = 2
        private const val FLAG_INFO = 4
        private const val C_VIEW = 20
        private const val C_PREF = 21
        private const val C_HELLO = 22
        private const val C_BYE = 23
        private const val F_INFO = 1
        private const val F_HEVC = 2
        private const val P_FPS = 1
        private const val P_SCALE = 2
        private const val P_CODEC = 3
        private const val P_RES = 4
        private const val RECONNECT_MS = 20_000L
        private const val S_AUX = 30
        private const val S_POS = 31
        private const val PEN_TOUCH = 1
        private const val PEN_ERASER = 2
        private const val PEN_BUTTON = 4
        private const val PEN_RANGE = 8
        private const val M_MOVE = 40
        private const val M_BTN = 41
        private const val M_SCROLL = 42
        private const val M_GESTURE = 43
        private const val H_PEN = 1
        private const val H_TRACKPAD = 2
        private const val T_SYNC = 0
        private const val T_DOWN = 1
        private const val T_MOVE = 2
        private const val T_UP = 3
        private const val K_TAP = 10
        private const val K_CHAR = 11
        private const val K_RAW = 14
        private const val MOD_CTRL = 1
        private const val MOD_ALT = 2
        private const val MOD_SUPER = 8
        private val BG = Color.parseColor("#1E1E2E")
        private val CARD = Color.parseColor("#2A2A3C")
        private val CARD2 = Color.parseColor("#3A3A4C")
        private val ACCENT = Color.parseColor("#33CCFF")
        private val SUBTLE = Color.parseColor("#A6ADC8")
        private val MUTED = Color.parseColor("#6C7086")
    }
}
