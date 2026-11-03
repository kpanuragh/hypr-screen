package com.hyprscreen

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Bundle
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
 * Handshake:  laptop: HELLO 2 <id> <hostname> <nonce>
 *             phone:  HYPRSCREEN 2 <w> <h> <dpi> <proof>   (proof = "-" over USB, else HMAC)
 *             laptop: OK <secret|-> | DENIED | BUSY
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
    private lateinit var keyBar: LinearLayout
    private var stickyMods = 0
    private val modButtons = mutableMapOf<Int, TextView>()

    private val prefs by lazy { getSharedPreferences("paired", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        surfaceView = SurfaceView(this).also {
            it.holder.addCallback(this)
            it.setOnTouchListener { v, e -> onTouch(v, e); true }
        }
        home = buildHome()
        keyCatcher = KeyCatcher()
        keyBar = buildKeyBar()
        kbdButton = buildKeyboardButton()
        val root = FrameLayout(this).apply {
            setBackgroundColor(BG)
            addView(surfaceView, FrameLayout.LayoutParams(-1, -1))
            addView(keyCatcher, FrameLayout.LayoutParams(1, 1))
            addView(keyBar, FrameLayout.LayoutParams(-1, dp(46), Gravity.BOTTOM))
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
            insets
        }
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

    private fun disconnect() { runCatching { session.get()?.close() } }

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
            addView(text(13f, MUTED).apply {
                setPadding(0, dp(20), 0, 0)
                text = "Plug in over USB and your phone becomes a second monitor automatically.\n" +
                    "Wi-Fi works after the first USB connection (pairing is automatic)."
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

    private fun showStream(streaming: Boolean) = runOnUiThread {
        home.visibility = if (streaming) View.GONE else View.VISIBLE
        kbdButton.visibility = if (streaming) View.VISIBLE else View.GONE
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
        val paired = l.usb || prefs.contains(l.id)
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
                    text = if (l.usb) "USB cable" else "Wi-Fi · ${l.host}" + if (paired) "" else " · connect by USB once to pair"
                    textSize = 13f; setTextColor(SUBTLE)
                })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(TextView(context).apply {
                text = "Connect"; textSize = 15f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (paired) Color.BLACK else MUTED)
                background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(if (paired) ACCENT else CARD2) }
                setPadding(dp(18), dp(8), dp(18), dp(8))
                isEnabled = paired
                setOnClickListener { connect(l) }
            })
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------- pairing

    private data class Pairing(val id: String, val name: String, val secret: String, val fingerprint: String)

    private fun pairing(id: String): Pairing? = prefs.getString("pair_$id", null)?.let { raw ->
        runCatching {
            val o = JSONObject(raw)
            Pairing(id, o.getString("name"), o.getString("secret"), o.getString("fingerprint"))
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
                require(id.matches(Regex("[0-9a-f]{12}")))
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

    /** TLS connection that only trusts the given certificate fingerprints (pinning, no CA). */
    private fun tlsConnect(host: String, pins: Set<String>, timeoutMs: Int): SSLSocket {
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
                throw CertificateException("not a server")
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                val fp = sha256Hex(chain.firstOrNull()?.encoded ?: throw CertificateException("no cert"))
                if (fp !in pins) throw CertificateException("unknown laptop certificate")
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
    private fun onTouch(v: View, e: MotionEvent) {
        if (!streaming || v.width == 0 || v.height == 0) return
        val buf = ByteBuffer.allocate((e.pointerCount + 2) * 6)
        fun put(type: Int, i: Int) {
            val id = e.getPointerId(i)
            if (id >= 10) return
            val x = (e.getX(i) / v.width * 65535f).toInt().coerceIn(0, 65535)
            val y = (e.getY(i) / v.height * 65535f).toInt().coerceIn(0, 65535)
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
        thread(name = "session", isDaemon = true) {
            try {
                runSession(l)
            } catch (e: Exception) {
                Log.i(TAG, "session ended: $e")
                setStatus(e.message ?: "Disconnected")
            } finally {
                runCatching { session.getAndSet(null)?.close() }
                streaming = false
                sessionClaim.set(null)
                showStream(false)
            }
        }
    }

    private fun runSession(l: Laptop) {
        setStatus("Connecting to ${l.name}…")
        val p = pairing(l.id) ?: error("Connect by USB once to pair with ${l.name}")
        val s = tlsConnect(l.host, setOf(p.fingerprint), 3000)
        session.set(s)
        if (!alive) error("Closing")
        s.tcpNoDelay = true
        s.soTimeout = 8000
        val input = BufferedInputStream(s.inputStream, 1 shl 20)
        val hello = readLine(input).split(" ")
        if (hello.size != 5 || hello[0] != "HELLO" || hello[1] != "3") error("Laptop needs updating (protocol mismatch)")
        if (hello[2] != p.id) error("Unexpected laptop identity")
        val clientNonce = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val proof = hmacHex(p.secret, "c|${hello[4]}|$clientNonce")
        val b = windowManager.maximumWindowMetrics.bounds
        val w = maxOf(b.width(), b.height())
        val h = minOf(b.width(), b.height())
        s.outputStream.apply {
            write("HYPRSCREEN 3 $w $h ${resources.displayMetrics.densityDpi} $clientNonce $proof\n".toByteArray()); flush()
        }
        when (readLine(input).split(" ").firstOrNull()) {
            "OK" -> {}
            "BUSY" -> error("${l.name} is already using another display")
            "DENIED" -> { prefs.edit().remove("pair_${p.id}").apply(); error("Pairing expired — connect by USB once") }
            else -> error("Laptop refused the connection")
        }
        if (!surfaceReady) error("Screen not ready")
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
        setStatus("Disconnected from ${l.name}")
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
                if (decoder == null) {
                    if (flags and FLAG_CONFIG == 0) continue // wait for SPS/PPS
                    decoder = Decoder(surfaceView.holder.surface, w, h)
                    showStream(true)
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

    private class Decoder(surface: Surface, w: Int, h: Int) {
        private val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        @Volatile private var running = true
        private val drain: Thread

        init {
            try {
                val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
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
        private const val FLAG_CONFIG = 1
        private const val FLAG_KEY = 2
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
