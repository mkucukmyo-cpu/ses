package com.sesle.visual

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast

class MainActivity : Activity() {

    companion object {
        @Volatile var webView: WebView? = null
        private const val REQ_PERMS = 1
        private const val REQ_PROJECTION = 2
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val wv = WebView(this)
        webView = wv
        wv.setBackgroundColor(0xFF040611.toInt())
        wv.settings.javaScriptEnabled = true
        wv.addJavascriptInterface(Bridge(), "Android")
        wv.loadUrl("file:///android_asset/index.html")
        setContentView(wv)

        hideSystemBars()
        requestNeededPermissions()
    }

    // index.html içindeki Android.exit() çağrısı
    inner class Bridge {
        @JavascriptInterface
        fun exit() {
            runOnUiThread {
                stopService(Intent(this@MainActivity, CaptureService::class.java))
                finishAndRemoveTask()
            }
        }
    }

    private fun requestNeededPermissions() {
        val missing = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            missing += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            missing += Manifest.permission.POST_NOTIFICATIONS

        if (missing.isEmpty()) askProjection()
        else requestPermissions(missing.toTypedArray(), REQ_PERMS)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code != REQ_PERMS) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) askProjection()
        else Toast.makeText(this, "Ses yakalama için mikrofon izni gerekli", Toast.LENGTH_LONG).show()
    }

    // Çalan sesi yakalamak için sistem onayı (ekran paylaşımı penceresi çıkar)
    private fun askProjection() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PROJECTION) return
        if (resultCode == RESULT_OK && data != null) {
            val i = Intent(this, CaptureService::class.java)
                .putExtra("code", resultCode)
                .putExtra("data", data)
            startForegroundService(i)
        } else {
            Toast.makeText(this, "Ses yakalama izni verilmedi", Toast.LENGTH_LONG).show()
        }
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onDestroy() {
        if (isFinishing) {
            stopService(Intent(this, CaptureService::class.java))
            webView = null
        }
        super.onDestroy()
    }
}
