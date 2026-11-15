package com.camlink

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.YuvImage
import android.hardware.Camera
import android.hardware.camera2.*
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaCodecInfo
import android.media.MediaRecorder
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

const val PORT = 5000
const val FPS = 30
val BITRATES = intArrayOf(6_000_000, 12_000_000, 25_000_000, 40_000_000)
val JPEGQ = intArrayOf(70, 85, 95, 100)

data class Cfg(val front: Boolean, val w: Int, val h: Int, val q: Int, val auto: Boolean)

// Protokol: her paket = [tip:1][uzunluk:4 big-endian][veri]
// tip 9=bilgi 0=h264 config 1=h264 kare 3=h264 anahtar kare 2=jpeg kare 8=yayın durdu
@Suppress("DEPRECATION")
class MainActivity : Activity() {
    private val lock = Any()
    @Volatile private var client: Socket? = null
    @Volatile private var info: ByteArray? = null
    @Volatile private var h264Config: ByteArray? = null
    @Volatile private var running = false
    @Volatile private var gen = 0
    @Volatile private var camFrames = 0L
    @Volatile private var encOut = 0L
    @Volatile private var sentBytes = 0L
    private var fellBack = false
    private var cam2: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var codec: MediaCodec? = null
    private var encThread: Thread? = null
    private var prevSurface: Surface? = null
    private var cam1: Camera? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var dim = false
    private val lines = ArrayList<String>()
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private lateinit var ipView: TextView
    private lateinit var stats: TextView
    private lateinit var logView: TextView
    private lateinit var tv: TextureView
    private lateinit var modeSp: Spinner
    private lateinit var camSp: Spinner
    private lateinit var resSp: Spinner
    private lateinit var qSp: Spinner
    private lateinit var bStart: Button
    private lateinit var camThread: HandlerThread
    private lateinit var camHandler: Handler

    private val tick = object : Runnable {
        override fun run() {
            ipView.text = "WiFi IP: ${localIps()}    Port: $PORT"
            stats.text = "Kamera kare: $camFrames    Gönderilen kare: $encOut\n" +
                "Veri: ${sentBytes / 1024} KB    PC bağlı: ${if (client != null) "evet" else "hayır"}"
            ui.postDelayed(this, 1000)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun log(msg: String) {
        ui.post {
            lines.add(msg)
            while (lines.size > 10) lines.removeAt(0)
            logView.text = lines.joinToString("\n")
        }
    }

    private fun label(t: String) = TextView(this).apply { text = t; textSize = 13f; setPadding(0, dp(10), 0, 0) }

    private fun spinner(key: String, def: Int, items: List<String>): Spinner {
        val sp = Spinner(this)
        val ad = ArrayAdapter(this, android.R.layout.simple_spinner_item, items)
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        sp.adapter = ad
        sp.setSelection(prefs.getInt(key, def))
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val old = prefs.getInt(key, def)
                if (pos != old) {
                    prefs.edit().putInt(key, pos).apply()
                    if (running) { stopAll(); ensureStart() }
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        return sp
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = getSharedPreferences("camlink", MODE_PRIVATE)
        camThread = HandlerThread("cam").also { it.start() }
        camHandler = Handler(camThread.looper)
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "camlink").also { it.acquire() }
        } catch (_: Exception) {}

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        ipView = TextView(this).apply { textSize = 15f }
        stats = TextView(this).apply { textSize = 13f }
        tv = TextureView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220))
        }
        modeSp = spinner("mode", 0, listOf("Otomatik (önerilen)", "H.264 (Camera2)", "MJPEG (uyumlu mod)"))
        camSp = spinner("cam", 0, listOf("Arka kamera", "Ön kamera"))
        resSp = spinner("res", 0, listOf("720p (önerilen)", "1080p", "480p"))
        qSp = spinner("q", 2, listOf("Düşük", "Orta", "Yüksek", "Maksimum"))
        bStart = Button(this).apply {
            text = "Başlat"
            setOnClickListener { if (running) stopAll() else ensureStart() }
        }
        val bDim = Button(this).apply {
            text = "Ekranı karart / aç"
            setOnClickListener {
                dim = !dim
                val lp = window.attributes
                lp.screenBrightness = if (dim) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                window.attributes = lp
            }
        }
        logView = TextView(this).apply { textSize = 12f; setPadding(0, dp(10), 0, 0) }

        root.addView(ipView); root.addView(stats); root.addView(tv)
        root.addView(label("Mod")); root.addView(modeSp)
        root.addView(label("Kamera")); root.addView(camSp)
        root.addView(label("Çözünürlük")); root.addView(resSp)
        root.addView(label("Kalite")); root.addView(qSp)
        root.addView(bStart); root.addView(bDim); root.addView(logView)
        setContentView(ScrollView(this).apply { addView(root) })

        log("Hazır. Başlat'a bas, sonra bilgisayardan bağlan.")
        startServer()
        ui.post(tick)
    }

    private fun localIps(): String = try {
        NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .filter { !it.isLoopbackAddress && it is Inet4Address }
            .joinToString { it.hostAddress ?: "" }.ifEmpty { "yok" }
    } catch (e: Exception) { "?" }

    private fun ensureStart() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startAll()
        else requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        if (r.isNotEmpty() && r[0] == PackageManager.PERMISSION_GRANTED) startAll() else log("Kamera izni verilmedi")
    }

    // ---------- ağ ----------
    private fun writeRaw(s: Socket, type: Int, d: ByteArray) {
        val n = d.size
        val buf = ByteArray(n + 5)
        buf[0] = type.toByte()
        buf[1] = (n ushr 24).toByte(); buf[2] = (n ushr 16).toByte()
        buf[3] = (n ushr 8).toByte(); buf[4] = n.toByte()
        System.arraycopy(d, 0, buf, 5, n)
        synchronized(lock) { s.outputStream.write(buf); s.outputStream.flush(); sentBytes += buf.size }
    }

    private fun sendPacket(type: Int, d: ByteArray) {
        val s = client ?: return
        try { writeRaw(s, type, d) } catch (e: Exception) { dropClient(s) }
    }

    private fun dropClient(s: Socket) {
        try { s.close() } catch (_: Exception) {}
        synchronized(lock) { if (client === s) client = null }
        log("PC bağlantısı koptu")
    }

    private fun setInfo(text: String) {
        val b = text.toByteArray()
        info = b
        thread { sendPacket(9, b) }
    }

    private fun requestKey() {
        try {
            codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        } catch (_: Exception) {}
    }

    private fun startServer() = thread(isDaemon = true) {
        try {
            val ss = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(PORT)) }
            while (true) {
                val s = ss.accept()
                s.tcpNoDelay = true
                synchronized(lock) {
                    client?.let { try { it.close() } catch (_: Exception) {} }
                    client = null
                }
                try {
                    info?.let { writeRaw(s, 9, it) }
                    h264Config?.let { writeRaw(s, 0, it) }
                } catch (e: Exception) { continue }
                client = s
                requestKey()
                log("PC bağlandı: ${s.inetAddress.hostAddress}")
            }
        } catch (e: Exception) { log("Sunucu hatası: $e") }
    }

    // ---------- başlat / durdur ----------
    private fun startAll() {
        if (running) return
        if (tv.surfaceTexture == null) { log("Önizleme henüz hazır değil, tekrar bas"); return }
        val (w, h) = when (resSp.selectedItemPosition) { 0 -> 1280 to 720; 1 -> 1920 to 1080; else -> 854 to 480 }
        val mode = modeSp.selectedItemPosition
        val c = Cfg(camSp.selectedItemPosition == 1, w, h, qSp.selectedItemPosition, mode == 0)
        fellBack = false
        camFrames = 0; encOut = 0; sentBytes = 0
        running = true
        bStart.text = "Durdur"
        log("Başlıyor: ${w}x$h, mod=${modeSp.selectedItem}")
        val g = gen
        camHandler.post { if (mode == 2) startMjpeg(c) else startH264(c) }
        camHandler.postDelayed({
            if (running && g == gen && camFrames == 0L) {
                if (c.auto && !fellBack) fail(c, "H.264 modunda 6 sn içinde görüntü gelmedi")
                else log("Uyarı: kameradan kare gelmiyor")
            }
        }, 6000)
    }

    private fun stopAll() {
        running = false
        thread { sendPacket(8, ByteArray(0)) }
        stopHw()
        info = null
        bStart.text = "Başlat"
        log("Durdu")
    }

    private fun stopHw() {
        gen++
        try { encThread?.join(500) } catch (_: Exception) {}
        encThread = null
        try { session?.close() } catch (_: Exception) {}
        try { cam2?.close() } catch (_: Exception) {}
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        try { prevSurface?.release() } catch (_: Exception) {}
        try { cam1?.setPreviewCallbackWithBuffer(null); cam1?.stopPreview() } catch (_: Exception) {}
        try { cam1?.release() } catch (_: Exception) {}
        session = null; cam2 = null; codec = null; prevSurface = null; cam1 = null
        h264Config = null
    }

    private fun abort(msg: String) {
        log(msg)
        ui.post { if (running) stopAll() }
    }

    // H.264 başarısız olursa otomatik modda MJPEG'e geç
    private fun fail(c: Cfg, msg: String) {
        log(msg)
        if (c.auto && !fellBack && running) {
            fellBack = true
            log("Uyumlu MJPEG moduna geçiliyor...")
            stopHw()
            startMjpeg(c)
        } else abort("Başlatılamadı: $msg")
    }

    private fun nearest(l: List<Size>?, w: Int, h: Int): Size? =
        l?.minByOrNull { Math.abs(it.width - w) + Math.abs(it.height - h) }

    // ---------- H.264 (Camera2) ----------
    @SuppressLint("MissingPermission")
    private fun startH264(c: Cfg) {
        val g = gen
        try {
            val mgr = getSystemService(CAMERA_SERVICE) as CameraManager
            val want = if (c.front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
            val id = mgr.cameraIdList.firstOrNull {
                mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == want
            }
            if (id == null) { fail(c, "Bu yönde kamera bulunamadı"); return }
            val map = mgr.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val encSize = nearest(map?.getOutputSizes(MediaRecorder::class.java)?.toList(), c.w, c.h)
            val pvSize = nearest(map?.getOutputSizes(SurfaceTexture::class.java)?.toList(), 640, 360)
            if (encSize == null || pvSize == null) { fail(c, "Desteklenen çözünürlükler alınamadı"); return }
            log("H.264 ${encSize.width}x${encSize.height}, önizleme ${pvSize.width}x${pvSize.height}")

            val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, encSize.width, encSize.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, BITRATES[c.q])
                setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val encSurface = enc.createInputSurface()
            enc.start()
            codec = enc
            h264Config = null
            setInfo("h264;${encSize.width};${encSize.height};$FPS")

            encThread = thread {
                val bi = MediaCodec.BufferInfo()
                while (running && gen == g) {
                    val i = try { enc.dequeueOutputBuffer(bi, 100_000) } catch (e: Exception) { break }
                    if (i >= 0) {
                        val buf = enc.getOutputBuffer(i)
                        if (buf != null && bi.size > 0) {
                            val data = ByteArray(bi.size)
                            buf.position(bi.offset); buf.limit(bi.offset + bi.size); buf.get(data)
                            if (bi.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                h264Config = data
                                sendPacket(0, data)
                            } else {
                                encOut++
                                val key = bi.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                                sendPacket(if (key) 3 else 1, data)
                            }
                        }
                        try { enc.releaseOutputBuffer(i, false) } catch (_: Exception) {}
                    }
                }
            }

            val st = tv.surfaceTexture!!
            st.setDefaultBufferSize(pvSize.width, pvSize.height)
            val prev = Surface(st)
            prevSurface = prev

            mgr.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(cd: CameraDevice) {
                    if (g != gen || !running) { cd.close(); return }
                    cam2 = cd
                    log("Kamera açıldı")
                    openSession(cd, g, c, encSurface, prev)
                }
                override fun onDisconnected(cd: CameraDevice) {
                    cd.close()
                    if (g == gen && running) fail(c, "Kamera bağlantısı koptu")
                }
                override fun onError(cd: CameraDevice, e: Int) {
                    cd.close()
                    if (g == gen && running) fail(c, "Kamera hatası, kod $e")
                }
            }, camHandler)
        } catch (e: Exception) { fail(c, "H.264 hatası: $e") }
    }

    private fun openSession(cd: CameraDevice, g: Int, c: Cfg, enc: Surface, prev: Surface?) {
        try {
            val targets = if (prev != null) listOf(enc, prev) else listOf(enc)
            val req = cd.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                targets.forEach { addTarget(it) }
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            }
            cd.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (g != gen || !running) { s.close(); return }
                    session = s
                    log("Oturum hazır")
                    try {
                        s.setRepeatingRequest(req.build(), object : CameraCaptureSession.CaptureCallback() {
                            override fun onCaptureCompleted(
                                ss: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult
                            ) { camFrames++ }
                        }, camHandler)
                    } catch (e: Exception) { fail(c, "İstek hatası: $e") }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    if (g != gen || !running) return
                    if (prev != null) {
                        log("Önizlemeyle oturum kurulamadı, önizlemesiz deniyorum")
                        openSession(cd, g, c, enc, null)
                    } else fail(c, "Kamera oturumu kurulamadı")
                }
            }, camHandler)
        } catch (e: Exception) { fail(c, "Oturum hatası: $e") }
    }

    // ---------- MJPEG (eski Camera API, en uyumlu mod) ----------
    private fun startMjpeg(c: Cfg) {
        val g = gen
        try {
            val want = if (c.front) Camera.CameraInfo.CAMERA_FACING_FRONT else Camera.CameraInfo.CAMERA_FACING_BACK
            var idx = -1
            val ci = Camera.CameraInfo()
            for (i in 0 until Camera.getNumberOfCameras()) {
                Camera.getCameraInfo(i, ci)
                if (ci.facing == want) { idx = i; break }
            }
            if (idx < 0) idx = 0
            val cam = Camera.open(idx)
            cam1 = cam
            val p = cam.parameters
            val size = nearest(p.supportedPreviewSizes.map { Size(it.width, it.height) }, c.w, c.h)!!
            p.setPreviewSize(size.width, size.height)
            p.previewFormat = ImageFormat.NV21
            p.supportedPreviewFpsRange?.filter { it[1] >= FPS * 1000 }?.minByOrNull { it[1] }
                ?.let { p.setPreviewFpsRange(it[0], it[1]) }
            if (p.supportedFocusModes?.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO) == true)
                p.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO
            cam.parameters = p

            val w = size.width
            val h = size.height
            val jq = JPEGQ[c.q]
            val bos = ByteArrayOutputStream(w * h / 2)
            val rect = Rect(0, 0, w, h)
            repeat(3) { cam.addCallbackBuffer(ByteArray(w * h * 3 / 2)) }
            cam.setPreviewCallbackWithBuffer { data, cm ->
                camFrames++
                if (g == gen && running && client != null) {
                    try {
                        bos.reset()
                        YuvImage(data, ImageFormat.NV21, w, h, null).compressToJpeg(rect, jq, bos)
                        sendPacket(2, bos.toByteArray())
                        encOut++
                    } catch (_: Exception) {}
                }
                cm.addCallbackBuffer(data)
            }
            try {
                cam.setPreviewTexture(tv.surfaceTexture)
                cam.startPreview()
            } catch (e: Exception) {
                log("Önizleme açılamadı, gizli önizleme kullanılıyor")
                cam.setPreviewTexture(SurfaceTexture(10))
                cam.startPreview()
            }
            setInfo("mjpeg;$w;$h;$FPS")
            log("MJPEG yayını: ${w}x$h, JPEG kalite $jq")
        } catch (e: Exception) { abort("Kamera açılamadı: $e") }
    }

    override fun onDestroy() {
        running = false
        stopHw()
        ui.removeCallbacksAndMessages(null)
        try { wifiLock?.release() } catch (_: Exception) {}
        camThread.quitSafely()
        super.onDestroy()
    }
}
