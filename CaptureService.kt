package com.sesle.visual

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.sin

/** Cihazda çalan sesi (müzik, video vb.) yakalar, FFT'sini WebView'daki pushAudio()'ya yollar. */
class CaptureService : Service() {

    @Volatile private var running = false
    private var projection: MediaProjection? = null
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) { stopSelf(); return START_NOT_STICKY }

        // Önce foreground'a çık, sonra MediaProjection al (Android 14 şartı)
        startForeground(1, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val code = intent.getIntExtra("code", 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra("data", Intent::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra("data")
        if (data == null) { stopSelf(); return START_NOT_STICKY }

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val proj = mpm.getMediaProjection(code, data)
        projection = proj
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { running = false }
        }, null)

        startCapture(proj)
        return START_NOT_STICKY
    }

    private fun startCapture(proj: MediaProjection) {
        val cfg = AudioPlaybackCaptureConfiguration.Builder(proj)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val fmt = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(44100)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        val minBuf = AudioRecord.getMinBufferSize(44100, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(cfg)
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(maxOf(minBuf, 16384))
                .build()
        } catch (e: SecurityException) { stopSelf(); return }

        running = true
        rec.startRecording()

        worker = Thread {
            val n = 2048
            val hop = 1024
            val window = ShortArray(n)
            val hop_buf = ShortArray(hop)
            val hann = DoubleArray(n) { 0.5 - 0.5 * cos(2 * PI * it / (n - 1)) }
            val cosT = DoubleArray(n / 2) { cos(-2 * PI * it / n) }
            val sinT = DoubleArray(n / 2) { sin(-2 * PI * it / n) }
            val re = DoubleArray(n)
            val im = DoubleArray(n)
            val out = ByteArray(1024)
            val busy = AtomicBoolean(false)

            while (running) {
                var got = 0
                while (got < hop && running) {
                    val r = rec.read(hop_buf, got, hop - got)
                    if (r <= 0) break
                    got += r
                }
                if (got < hop) continue

                System.arraycopy(window, hop, window, 0, n - hop)
                System.arraycopy(hop_buf, 0, window, n - hop, hop)

                for (i in 0 until n) { re[i] = window[i] / 32768.0 * hann[i]; im[i] = 0.0 }
                fft(re, im, cosT, sinT)

                for (i in 0 until 1024) {
                    val mag = hypot(re[i], im[i]) / (n / 4.0)          // tam ölçekli sinüs ≈ 1
                    val db = 20 * log10(mag + 1e-9)
                    out[i] = (((db + 75.0) / 60.0).coerceIn(0.0, 1.0) * 255).toInt().toByte()   // -75..-15 dB
                }

                val b64 = Base64.encodeToString(out, Base64.NO_WRAP)
                val wv = MainActivity.webView
                if (wv != null && busy.compareAndSet(false, true)) {
                    wv.post {
                        wv.evaluateJavascript("pushAudio('$b64')", null)
                        busy.set(false)
                    }
                }
            }
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
        }.also { it.start() }
    }

    private fun fft(re: DoubleArray, im: DoubleArray, cosT: DoubleArray, sinT: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val step = n / len
            for (i in 0 until n step len) {
                for (k in 0 until len / 2) {
                    val wr = cosT[k * step]; val wi = sinT[k * step]
                    val a = i + k; val b = a + len / 2
                    val tr = re[b] * wr - im[b] * wi
                    val ti = re[b] * wi + im[b] * wr
                    re[b] = re[a] - tr; im[b] = im[a] - ti
                    re[a] += tr; im[a] += ti
                }
            }
            len = len shl 1
        }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("sesle", "Ses yakalama", NotificationManager.IMPORTANCE_LOW)
        )
        return Notification.Builder(this, "sesle")
            .setContentTitle("Sesle")
            .setContentText("Çalan ses görselleştiriliyor")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        running = false
        try { worker?.join(500) } catch (_: Exception) {}
        projection?.stop()
        projection = null
        super.onDestroy()
    }
}
