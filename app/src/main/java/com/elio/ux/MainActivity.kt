package com.elio.ux

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity(), SensorEventListener, LocationListener {

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1001
    }

    private lateinit var webView: WebView
    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var locationManager: LocationManager? = null

    private var currentGForce: Float = 0.0f
    private var currentGpsSpeedKmh: Float = 0.0f
    private var isAccPowerOn: Boolean = true

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_POWER_CONNECTED -> {
                    isAccPowerOn = true
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    webView.post {
                        webView.evaluateJavascript("window.onAccPowerStateChanged?.(true);", null)
                    }
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    isAccPowerOn = false
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    webView.post {
                        webView.evaluateJavascript("window.onAccPowerStateChanged?.(false);", null)
                    }
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Lock Fullscreen Immersive Sticky
        hideSystemUI()

        // Keep Screen ON by default for car dashboard
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        webView = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false // Allow smooth YouTube playback
                setGeolocationEnabled(true)
                allowFileAccess = true
                allowContentAccess = true
                cacheMode = WebSettings.LOAD_DEFAULT
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                javaScriptCanOpenWindowsAutomatically = true
                // Samarkan penanda "WebView" di User-Agent agar YouTube menganggap ini
                // browser mobile biasa (bukan WebView custom yang dibatasi fiturnya).
                userAgentString = userAgentString.replace("; wv", "")
            }

            // YouTube embed butuh cookie pihak ketiga untuk bisa inisialisasi player-nya
            // di dalam iframe. Android WebView mematikan ini secara default sejak Lollipop.
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            webChromeClient = object : WebChromeClient() {
                override fun onGeolocationPermissionsShowPrompt(
                    origin: String?,
                    callback: GeolocationPermissions.Callback?
                ) {
                    callback?.invoke(origin, true, false)
                }
            }

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    return false
                }
            }

            addJavascriptInterface(ElioHardwareBridge(this@MainActivity), "ElioNativeBridge")
        }

        setContentView(webView)

        // Load Offline Bundle Assets
        webView.loadUrl("file:///android_asset/index.html")

        // Setup Internal Sensors (accelerometer tidak butuh izin runtime)
        initAccelerometer()

        // Lokasi (GPS) butuh izin runtime di Android 6+ (API 23+)
        requestLocationPermissionThenStart()

        // Register Power Connection Broadcast
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        registerReceiver(powerReceiver, filter)
    }

    private fun initAccelerometer() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        accelerometer?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    private fun requestLocationPermissionThenStart() {
        val fineGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (fineGranted) {
            startLocationUpdates()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                LOCATION_PERMISSION_REQUEST_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE &&
            grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            startLocationUpdates()
        }
    }

    private fun startLocationUpdates() {
        locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        try {
            if (locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true) {
                locationManager?.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    500L,
                    0.5f,
                    this
                )
            }
            // Fallback tambahan: provider jaringan untuk update lebih cepat saat sinyal GPS lemah
            if (locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true) {
                locationManager?.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    1000L,
                    1f,
                    this
                )
            }
        } catch (_: Exception) {}
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            val y = event.values[1] // Forward-backward acceleration in landscape
            currentGForce = (y / SensorManager.GRAVITY_EARTH)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onLocationChanged(location: Location) {
        if (location.hasSpeed()) {
            currentGpsSpeedKmh = location.speed * 3.6f // m/s to km/h
            webView.post {
                webView.evaluateJavascript("window.onGpsSpeedUpdate?.($currentGpsSpeedKmh, $currentGForce);", null)
            }
        }
    }

    private fun hideSystemUI() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
        )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUI()
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager?.unregisterListener(this)
        locationManager?.removeUpdates(this)
        try { unregisterReceiver(powerReceiver) } catch (_: Exception) {}
    }

    // JavaScript Bridge exposed to Elio UX Web App
    class ElioHardwareBridge(private val activity: MainActivity) {
        @JavascriptInterface
        fun getGpsSpeed(): Float = activity.currentGpsSpeedKmh

        @JavascriptInterface
        fun getGForce(): Float = activity.currentGForce

        @JavascriptInterface
        fun isAccOn(): Boolean = activity.isAccPowerOn

        @JavascriptInterface
        fun openGoogleMapsIntent(destinationQuery: String) {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = android.net.Uri.parse("google.navigation:q=" + android.net.Uri.encode(destinationQuery) + "&mode=d")
                setPackage("com.google.android.apps.maps")
            }
            try {
                activity.startActivity(intent)
            } catch (_: Exception) {}
        }
    }
}
