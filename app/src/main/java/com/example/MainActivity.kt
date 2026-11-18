package com.example

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
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), SensorEventListener, LocationListener {

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1001
        private const val SENSOR_PUSH_INTERVAL_MS = 350L
    }

    // Halaman lokal disajikan dari origin https berbasis ID aplikasi (format yang diminta YouTube
    // untuk embed di WebView). Tanpa origin -> Error 153; mengaku youtube.com -> Error 152.
    private val launcherHost: String by lazy { packageName.lowercase(Locale.ROOT) }
    private val launcherOrigin: String by lazy { "https://$launcherHost/" }

    private lateinit var rootContainer: FrameLayout
    private lateinit var webView: WebView
    private var customFullscreenView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isPageLoaded = false

    // Sensors
    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var magnetometer: Sensor? = null
    private var lightSensor: Sensor? = null

    private val gravity = FloatArray(3)
    private var hasGravityInit = false
    private val lastAccelValues = FloatArray(3)
    private val lastMagValues = FloatArray(3)
    private var hasAccel = false
    private var hasMag = false
    private val rotMatrix = FloatArray(9)
    private val remappedRotMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    // Location & GNSS
    private var locationManager: LocationManager? = null
    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var fusedLocationCallback: LocationCallback? = null
    private var gnssCallback: GnssStatus.Callback? = null

    // Live Telemetry States
    @Volatile private var currentGForce: Float = 0.0f
    @Volatile private var currentPitchDeg: Float = 0.0f
    @Volatile private var currentCompassDeg: Float = 0.0f
    @Volatile private var currentLightLux: Float = 120.0f

    @Volatile private var currentGpsSpeedKmh: Float = 0.0f
    @Volatile private var currentLat: Double = -6.2088
    @Volatile private var currentLon: Double = 106.8456
    @Volatile private var hasRealFix: Boolean = false
    @Volatile private var currentAltitudeM: Double = 0.0
    @Volatile private var currentBearingDeg: Float = 0.0f
    @Volatile private var currentAccuracyM: Float = 0.0f
    @Volatile private var satsUsedInFix: Int = 0
    @Volatile private var satsVisibleTotal: Int = 0
    @Volatile private var tripDistanceKm: Double = 0.0
    private var previousLocation: Location? = null
    private var lastGpsTimestampMs: Long = 0L

    // Power & Battery States
    @Volatile private var isAccPowerOn: Boolean = true
    @Volatile private var batteryPercent: Int = 100
    @Volatile private var isBatteryCharging: Boolean = true
    @Volatile private var batteryTempC: Float = 30.0f

    private var lastSensorPushTimeMs: Long = 0L

    private val powerAndBatteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_POWER_CONNECTED -> {
                    isAccPowerOn = true
                    isBatteryCharging = true
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    evaluateJs("if (window.onAccPowerStateChanged) window.onAccPowerStateChanged(true);")
                    pushSensorTelemetry(force = true)
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    isAccPowerOn = false
                    isBatteryCharging = false
                    evaluateJs("if (window.onAccPowerStateChanged) window.onAccPowerStateChanged(false);")
                    pushSensorTelemetry(force = true)
                }
                Intent.ACTION_BATTERY_CHANGED -> {
                    updateBatteryFromIntent(intent)
                    pushSensorTelemetry(force = false)
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        hideSystemUI()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        rootContainer = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.parseColor("#020408"))
        }

        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(android.graphics.Color.parseColor("#020408"))
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                setGeolocationEnabled(true)
                allowFileAccess = true
                allowContentAccess = true
                javaScriptCanOpenWindowsAutomatically = true
                setSupportMultipleWindows(false)
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                cacheMode = WebSettings.LOAD_DEFAULT
                useWideViewPort = true
                loadWithOverviewMode = true
                // Remove WebView "; wv" token so YouTube embedded player runs cleanly
                userAgentString = userAgentString.replace("; wv", "")
            }

            val currentWebView = this
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(currentWebView, true)
            }

            webChromeClient = object : WebChromeClient() {
                override fun onGeolocationPermissionsShowPrompt(
                    origin: String?,
                    callback: GeolocationPermissions.Callback?
                ) {
                    callback?.invoke(origin, true, false)
                }

                override fun onPermissionRequest(request: PermissionRequest?) {
                    request?.grant(request.resources)
                }

                override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                    if (view == null) return
                    if (customFullscreenView != null) {
                        callback?.onCustomViewHidden()
                        return
                    }
                    customFullscreenView = view
                    customViewCallback = callback
                    webView.visibility = View.GONE
                    rootContainer.addView(
                        view,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    )
                    hideSystemUI()
                }

                override fun onHideCustomView() {
                    val view = customFullscreenView ?: return
                    rootContainer.removeView(view)
                    customFullscreenView = null
                    customViewCallback?.onCustomViewHidden()
                    customViewCallback = null
                    webView.visibility = View.VISIBLE
                    hideSystemUI()
                }

                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    consoleMessage?.let {
                        Log.d(
                            "ElioWebView",
                            "[${it.messageLevel()}] ${it.message()} (${it.sourceId()}:${it.lineNumber()})"
                        )
                    }
                    return super.onConsoleMessage(consoleMessage)
                }
            }

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val uri = request?.url ?: return null
                    val host = uri.host ?: ""
                    val path = uri.path ?: ""
                    if (uri.scheme == "https" && host.equals(launcherHost, ignoreCase = true)) {
                        if (path.isEmpty() || path == "/" || path == "/index.html") {
                            return try {
                                val stream = assets.open("index.html")
                                WebResourceResponse("text/html", "UTF-8", stream)
                            } catch (_: Exception) {
                                null
                            }
                        }
                        // Domain ini fiktif: jangan biarkan permintaan lain (mis. favicon) keluar ke internet
                        return WebResourceResponse(
                            "text/plain", "UTF-8", 404, "Not Found", emptyMap(),
                            java.io.ByteArrayInputStream(ByteArray(0))
                        )
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    isPageLoaded = true
                    pushAllHardwareSnapshots()
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val uri = request?.url ?: return false
                    val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false

                    if (scheme == "http" || scheme == "https") {
                        // Never let external watch/maps links hijack the main launcher frame
                        if (request.isForMainFrame) {
                            val host = uri.host?.lowercase(Locale.ROOT) ?: ""
                            if (host != launcherHost) {
                                try {
                                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                                } catch (_: Exception) {}
                                return true
                            }
                        }
                        return false
                    }

                    // Handle deep links (intent://, vnd.youtube:, geo:, google.navigation:, tel:, etc.)
                    try {
                        val intent = if (scheme == "intent") {
                            Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
                        } else {
                            Intent(Intent.ACTION_VIEW, uri)
                        }
                        startActivity(intent)
                    } catch (_: Exception) {}
                    return true
                }
            }

            addJavascriptInterface(ElioHardwareBridge(this@MainActivity), "ElioNativeBridge")
        }

        rootContainer.addView(webView)
        setContentView(rootContainer)
        webView.requestFocus()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (customFullscreenView != null) {
                    webView.webChromeClient?.onHideCustomView()
                    return
                }
                webView.evaluateJavascript("window.handleAndroidBack ? window.handleAndroidBack() : false;") { result ->
                    if (result != "true") {
                        moveTaskToBack(true)
                    }
                }
            }
        })

        // Serve bundled assets/index.html with HTTPS base URL so YouTube Referer/Origin & Geolocation work cleanly
        loadHtmlWithHttpsOrigin()

        // Initialize all onboard tablet hardware sensors (Accelerometer, Magnetometer, Ambient Light)
        initHardwareSensors()

        // Read initial battery & ACC charger status immediately
        val initialBattery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (initialBattery != null) {
            updateBatteryFromIntent(initialBattery)
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        ContextCompat.registerReceiver(
            this,
            powerAndBatteryReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // Request Location permission & start GPS + Fused + GNSS satellites
        requestLocationPermissionThenStart()
    }

    private fun loadHtmlWithHttpsOrigin() {
        try {
            webView.loadUrl(launcherOrigin + "index.html")
        } catch (_: Exception) {
            try {
                val html = assets.open("index.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
                webView.loadDataWithBaseURL(launcherOrigin, html, "text/html", "UTF-8", null)
            } catch (_: Exception) {
                webView.loadUrl("file:///android_asset/index.html")
            }
        }
    }

    private fun updateBatteryFromIntent(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        if (level >= 0) {
            batteryPercent = ((level * 100f) / scale).roundToInt().coerceIn(0, 100)
        }
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        isBatteryCharging = plugged != 0
        val tempTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 300)
        batteryTempC = tempTenths / 10f
    }

    private fun initHardwareSensors() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        magnetometer = sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        lightSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT)

        registerSensors()
    }

    private fun registerSensors() {
        accelerometer?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        magnetometer?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        lightSensor?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    private fun requestLocationPermissionThenStart() {
        val fineGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (fineGranted || coarseGranted) {
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
            grantResults.any { it == PackageManager.PERMISSION_GRANTED }
        ) {
            startLocationUpdates()
        } else if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            evaluateJs("if (window.onNativeGpsPermissionDenied) window.onNativeGpsPermissionDenied();")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        val fineGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!fineGranted && !coarseGranted) return

        locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager

        // 1. Seed with last known location immediately if available
        try {
            val lastGps = locationManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val lastNet = locationManager?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            val bestLast = when {
                lastGps != null && lastNet != null -> if (lastGps.time >= lastNet.time) lastGps else lastNet
                else -> lastGps ?: lastNet
            }
            if (bestLast != null) {
                processLocationFix(bestLast, isInitialSeed = true)
            }
        } catch (_: Exception) {}

        // 2. Register GNSS satellite counter (API 24+, Samsung Tab A6 Oreo API 27 supported)
        try {
            if (gnssCallback == null) {
                gnssCallback = object : GnssStatus.Callback() {
                    override fun onSatelliteStatusChanged(status: GnssStatus) {
                        var used = 0
                        val total = status.satelliteCount
                        for (i in 0 until total) {
                            if (status.usedInFix(i)) used++
                        }
                        satsUsedInFix = used
                        satsVisibleTotal = total
                        pushSensorTelemetry(force = false)
                    }
                }
            }
            gnssCallback?.let { cb ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    locationManager?.registerGnssStatusCallback(mainExecutor, cb)
                } else {
                    @Suppress("DEPRECATION")
                    locationManager?.registerGnssStatusCallback(cb, mainHandler)
                }
            }
        } catch (_: Exception) {}

        // 3. Satu sumber lokasi saja (Fused, 1x/detik). Dulu GPS + Network + Fused jalan bersamaan
        //    dan membanjiri thread utama sampai ~6 update/detik di tablet lama.
        try {
            if (fusedLocationClient == null) {
                fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
            }
            if (fusedLocationCallback == null) {
                fusedLocationCallback = object : LocationCallback() {
                    override fun onLocationResult(result: LocationResult) {
                        result.lastLocation?.let { processLocationFix(it, isInitialSeed = false) }
                    }
                }
            }
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
                .setMinUpdateIntervalMillis(1000L)
                .setMinUpdateDistanceMeters(0f)
                .build()
            fusedLocationClient!!.requestLocationUpdates(
                request,
                fusedLocationCallback!!,
                Looper.getMainLooper()
            ).addOnFailureListener { startLegacyGpsUpdates() }
        } catch (_: Exception) {
            startLegacyGpsUpdates()
        }
    }

    // Cadangan bila Google Play Services tidak tersedia di perangkat.
    @SuppressLint("MissingPermission")
    private fun startLegacyGpsUpdates() {
        try {
            if (locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true) {
                locationManager?.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000L,
                    0f,
                    this,
                    Looper.getMainLooper()
                )
            }
        } catch (_: Exception) {}
    }

    override fun onLocationChanged(location: Location) {
        processLocationFix(location, isInitialSeed = false)
    }

    private fun processLocationFix(location: Location, isInitialSeed: Boolean) {
        val nowMs = System.currentTimeMillis()
        currentLat = location.latitude
        currentLon = location.longitude
        if (location.hasAltitude()) currentAltitudeM = location.altitude
        if (location.hasAccuracy()) currentAccuracyM = location.accuracy
        if (location.hasBearing() && location.speed > 1.0f) {
            currentBearingDeg = location.bearing
        }

        // Compute Speed in km/h
        var speedKmh = 0f
        if (!isInitialSeed) {
            if (location.hasSpeed()) {
                speedKmh = location.speed * 3.6f
            } else {
                val prev = previousLocation
                val dtSec = (nowMs - lastGpsTimestampMs) / 1000f
                if (prev != null && dtSec in 0.35f..10f && location.accuracy < 25f) {
                    val distMeters = prev.distanceTo(location)
                    if (distMeters > 1.5f) {
                        speedKmh = (distMeters / dtSec) * 3.6f
                    }
                }
            }

            // Deadzone clamp under 1.2 km/h to eliminate stationary GPS jitter
            if (speedKmh < 1.2f) speedKmh = 0f
            currentGpsSpeedKmh = speedKmh.coerceIn(0f, 260f)

            // Accumulate genuine Trip Odometer between valid fixes
            val prev = previousLocation
            if (prev != null && (!location.hasAccuracy() || location.accuracy <= 35f)) {
                val segmentMeters = prev.distanceTo(location)
                val dtSec = ((nowMs - lastGpsTimestampMs).coerceAtLeast(100L)) / 1000f
                val impliedMps = segmentMeters / dtSec
                if (segmentMeters >= 2.5f && impliedMps <= 90f) {
                    tripDistanceKm += (segmentMeters / 1000.0)
                }
            }
            previousLocation = location
            lastGpsTimestampMs = nowMs
            hasRealFix = true
        } else {
            previousLocation = location
            lastGpsTimestampMs = nowMs
            hasRealFix = true
        }

        pushGpsTelemetry()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(event.values, 0, lastAccelValues, 0, 3)
                hasAccel = true

                val alpha = 0.82f
                if (!hasGravityInit) {
                    gravity[0] = event.values[0]
                    gravity[1] = event.values[1]
                    gravity[2] = event.values[2]
                    hasGravityInit = true
                } else {
                    gravity[0] = alpha * gravity[0] + (1f - alpha) * event.values[0]
                    gravity[1] = alpha * gravity[1] + (1f - alpha) * event.values[1]
                    gravity[2] = alpha * gravity[2] + (1f - alpha) * event.values[2]
                }

                // Isolate dynamic linear acceleration without static Earth gravity
                val lx = event.values[0] - gravity[0]
                val ly = event.values[1] - gravity[1]
                val lz = event.values[2] - gravity[2]

                val rotation = getDisplayRotationCompat()
                val lateralLinear = when (rotation) {
                    Surface.ROTATION_90 -> ly
                    Surface.ROTATION_270 -> -ly
                    Surface.ROTATION_180 -> -lx
                    else -> lx
                }
                val gRaw = lateralLinear / SensorManager.GRAVITY_EARTH
                val gSmoothed = if (kotlin.math.abs(gRaw) < 0.02f) 0f else gRaw
                currentGForce = (currentGForce * 0.65f) + (gSmoothed * 0.35f)

                val gNorm = sqrt((gravity[0] * gravity[0] + gravity[1] * gravity[1] + gravity[2] * gravity[2]).toDouble()).coerceAtLeast(0.1)
                val pitchRad = when (rotation) {
                    Surface.ROTATION_90, Surface.ROTATION_270 -> atan2(gravity[0].toDouble(), sqrt((gravity[1] * gravity[1] + gravity[2] * gravity[2]).toDouble()))
                    else -> atan2(gravity[1].toDouble(), sqrt((gravity[0] * gravity[0] + gravity[2] * gravity[2]).toDouble()))
                }
                currentPitchDeg = Math.toDegrees(pitchRad).toFloat().coerceIn(-45f, 45f)
                if (gNorm > 0) {
                    updateCompassOrientation()
                }
                pushSensorTelemetry(force = false)
            }

            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(event.values, 0, lastMagValues, 0, 3)
                hasMag = true
                updateCompassOrientation()
                pushSensorTelemetry(force = false)
            }

            Sensor.TYPE_LIGHT -> {
                currentLightLux = event.values[0].coerceAtLeast(0f)
                pushSensorTelemetry(force = false)
            }
        }
    }

    private fun updateCompassOrientation() {
        if (!hasAccel || !hasMag) return
        if (SensorManager.getRotationMatrix(rotMatrix, null, lastAccelValues, lastMagValues)) {
            when (getDisplayRotationCompat()) {
                Surface.ROTATION_90 -> SensorManager.remapCoordinateSystem(
                    rotMatrix, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, remappedRotMatrix
                )
                Surface.ROTATION_180 -> SensorManager.remapCoordinateSystem(
                    rotMatrix, SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y, remappedRotMatrix
                )
                Surface.ROTATION_270 -> SensorManager.remapCoordinateSystem(
                    rotMatrix, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, remappedRotMatrix
                )
                else -> System.arraycopy(rotMatrix, 0, remappedRotMatrix, 0, 9)
            }
            SensorManager.getOrientation(remappedRotMatrix, orientationAngles)
            var deg = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
            if (deg < 0f) deg += 360f
            currentCompassDeg = deg
        }
    }

    @Suppress("DEPRECATION")
    private fun getDisplayRotationCompat(): Int {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                display?.rotation ?: Surface.ROTATION_90
            } else {
                windowManager.defaultDisplay?.rotation ?: Surface.ROTATION_90
            }
        } catch (_: Exception) {
            Surface.ROTATION_90
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun pushGpsTelemetry() {
        if (!isPageLoaded) return
        val effectiveHeading = if (currentGpsSpeedKmh > 5f && currentBearingDeg > 0f) {
            currentBearingDeg
        } else {
            currentCompassDeg
        }
        val js = String.format(
            Locale.US,
            "if (window.onNativeGpsUpdate) window.onNativeGpsUpdate(%.2f, %.6f, %.6f, %.1f, %.1f, %.1f, %d, %d, %.2f);",
            currentGpsSpeedKmh,
            currentLat,
            currentLon,
            currentAltitudeM,
            effectiveHeading,
            currentAccuracyM,
            satsUsedInFix,
            satsVisibleTotal,
            tripDistanceKm
        )
        evaluateJs(js)
    }

    private fun pushSensorTelemetry(force: Boolean) {
        if (!isPageLoaded) return
        val now = System.currentTimeMillis()
        if (!force && (now - lastSensorPushTimeMs) < SENSOR_PUSH_INTERVAL_MS) return
        lastSensorPushTimeMs = now

        val headingDeg = if (currentGpsSpeedKmh > 5f && currentBearingDeg > 0f) {
            currentBearingDeg
        } else {
            currentCompassDeg
        }
        val networkOnline = isNetworkAvailable()

        val js = String.format(
            Locale.US,
            "if (window.onNativeSensorUpdate) window.onNativeSensorUpdate(%.3f, %.1f, %.1f, %.1f, %d, %b, %.1f, %b, %d, %d, %b);",
            currentGForce,
            currentPitchDeg,
            headingDeg,
            currentLightLux,
            batteryPercent,
            isBatteryCharging,
            batteryTempC,
            isAccPowerOn,
            satsUsedInFix,
            satsVisibleTotal,
            networkOnline
        )
        evaluateJs(js)
    }

    private fun pushAllHardwareSnapshots() {
        pushSensorTelemetry(force = true)
        if (hasRealFix) {
            pushGpsTelemetry()
        }
    }

    private fun isNetworkAvailable(): Boolean {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            false
        }
    }

    private fun evaluateJs(script: String) {
        webView.post {
            try {
                webView.evaluateJavascript(script, null)
            } catch (_: Exception) {}
        }
    }

    @Suppress("DEPRECATION")
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

    override fun onResume() {
        super.onResume()
        webView.onResume()
        registerSensors()
        startLocationUpdates()
        pushAllHardwareSnapshots()
    }

    override fun onPause() {
        super.onPause()
        // Split screen di Android 8.1: saat jari memakai app di sebelah (Maps/YouTube), Elio
        // dianggap "pause" padahal masih terlihat. Speedometer harus tetap hidup.
        if (!isInMultiWindowMode) webView.onPause()
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        if (isInMultiWindowMode) webView.onResume()
        if (!isInMultiWindowMode) hideSystemUI()
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager?.unregisterListener(this)
        try { locationManager?.removeUpdates(this) } catch (_: Exception) {}
        try {
            gnssCallback?.let { locationManager?.unregisterGnssStatusCallback(it) }
        } catch (_: Exception) {}
        try {
            fusedLocationCallback?.let { fusedLocationClient?.removeLocationUpdates(it) }
        } catch (_: Exception) {}
        try { unregisterReceiver(powerAndBatteryReceiver) } catch (_: Exception) {}
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
        fun requestSensorSync() {
            activity.mainHandler.post {
                activity.pushAllHardwareSnapshots()
            }
        }

        @JavascriptInterface
        fun requestLocationPermission() {
            activity.mainHandler.post {
                activity.requestLocationPermissionThenStart()
            }
        }

        @JavascriptInterface
        fun resetTripOdometer() {
            activity.tripDistanceKm = 0.0
            activity.previousLocation = null
            activity.mainHandler.post {
                activity.pushGpsTelemetry()
            }
        }

        @JavascriptInterface
        fun setKeepScreenOn(keepOn: Boolean) {
            activity.mainHandler.post {
                if (keepOn) {
                    activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }

        @JavascriptInterface
        fun ensureFullscreen() {
            activity.mainHandler.post {
                activity.hideSystemUI()
            }
        }

        @JavascriptInterface
        fun openGoogleMapsIntent(destinationQuery: String) {
            val uri = when {
                activity.hasRealFix && (destinationQuery == "CURRENT_GPS" || destinationQuery.isBlank()) ->
                    Uri.parse("geo:${activity.currentLat},${activity.currentLon}?q=${activity.currentLat},${activity.currentLon}")
                destinationQuery.isBlank() ->
                    Uri.parse("geo:0,0")
                else ->
                    Uri.parse("google.navigation:q=" + Uri.encode(destinationQuery) + "&mode=d")
            }
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage("com.google.android.apps.maps")
            }
            try {
                activity.startActivity(intent)
            } catch (_: Exception) {
                try {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, uri))
                } catch (_: Exception) {}
            }
        }

        @JavascriptInterface
        fun openYouTubeVideo(videoId: String) {
            val cleanId = videoId.trim()
            val appIntent = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$cleanId"))
            try {
                activity.startActivity(appIntent)
            } catch (_: Exception) {
                try {
                    activity.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$cleanId"))
                    )
                } catch (_: Exception) {}
            }
        }

        @JavascriptInterface
        fun launchExternalApp(appKey: String): Boolean {
            val pm = activity.packageManager
            val intent: Intent? = when (appKey.lowercase(Locale.ROOT)) {
                "spotify" -> pm.getLaunchIntentForPackage("com.spotify.music")
                "waze" -> pm.getLaunchIntentForPackage("com.waze")
                    ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://waze.com/ul"))
                "maps" -> pm.getLaunchIntentForPackage("com.google.android.apps.maps")
                "youtube" -> pm.getLaunchIntentForPackage("com.google.android.youtube")
                "phone" -> Intent(Intent.ACTION_DIAL)
                "dashcam" -> Intent(MediaStore.INTENT_ACTION_VIDEO_CAMERA)
                else -> null
            }
            if (intent != null) {
                try {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    activity.startActivity(intent)
                    return true
                } catch (_: Exception) {}
            }
            return false
        }
    }
}
