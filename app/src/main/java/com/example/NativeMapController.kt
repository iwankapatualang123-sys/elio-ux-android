package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.maplibre.android.LibraryLoader
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.Point
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Peta native OpenStreetMap (MapLibre + gaya gelap OpenFreeMap; gratis, tanpa API key) yang
 * diletakkan DI BELAKANG WebView. Halaman web "melubangi" kartu peta (latar transparan) dan
 * melaporkan posisi lubang lewat setRect(), sehingga pencarian, HUD, dan menu HTML tetap di atas.
 */
class NativeMapController private constructor(
    private val context: Context,
    container: TouchRouterLayout,
    savedState: Bundle?,
    private val runJs: (String) -> Unit,
) {
    companion object {
        private const val STATE_KEY = "elio_native_map"
        private const val STYLE_URL = "https://tiles.openfreemap.org/styles/dark"
        private const val DEST_SOURCE = "elio-dest-source"
        private const val DEST_LAYER = "elio-dest-layer"
        private const val EMPTY_GEOJSON = """{"type":"FeatureCollection","features":[]}"""

        /** null jika mesin peta tidak bisa dimuat di perangkat ini -> halaman memakai peta web. */
        fun create(
            context: Context,
            container: TouchRouterLayout,
            savedState: Bundle?,
            runJs: (String) -> Unit,
        ): NativeMapController? = try {
            MapLibre.getInstance(context.applicationContext)
            LibraryLoader.load()
            NativeMapController(context, container, savedState, runJs)
        } catch (_: Throwable) {
            null
        }
    }

    val mapView: MapView = MapView(context).apply {
        visibility = View.GONE
        layoutParams = FrameLayout.LayoutParams(0, 0, Gravity.TOP or Gravity.START)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val geocodeExecutor = Executors.newSingleThreadExecutor()
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var destSource: GeoJsonSource? = null
    private var pendingDest: LatLng? = null
    private var followCar = true
    private var wasMoving = false
    private var locationActive = false
    private var lastFix: Location? = null
    private var lastSpeedKmh = 0f

    init {
        mapView.onCreate(savedState?.getBundle(STATE_KEY))
        container.addView(mapView, 0)
        container.mapTarget = mapView
        mapView.getMapAsync { setupMap(it) }
    }

    private fun setupMap(m: MapLibreMap) {
        map = m
        m.uiSettings.apply {
            isCompassEnabled = true
            isTiltGesturesEnabled = false
            isLogoEnabled = false
            isAttributionEnabled = true // wajib: kredit data OpenStreetMap
        }
        m.addOnCameraMoveStartedListener { reason ->
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) followCar = false
        }
        val fix = lastFix
        if (fix != null) {
            m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(fix.latitude, fix.longitude), 16.0))
        } else {
            m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(-2.5, 118.0), 4.0))
        }
        m.setStyle(Style.Builder().fromUri(STYLE_URL)) { loaded ->
            style = loaded
            addDestinationLayer(loaded)
            activateLocation()
        }
    }

    private fun addDestinationLayer(s: Style) {
        val source = GeoJsonSource(DEST_SOURCE)
        s.addSource(source)
        s.addLayer(
            CircleLayer(DEST_LAYER, DEST_SOURCE).withProperties(
                circleRadius(9f),
                circleColor("#10B981"),
                circleStrokeColor("#FFFFFF"),
                circleStrokeWidth(3f),
            )
        )
        destSource = source
        pendingDest?.let { setDestinationPoint(it) }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    // Titik mobil memakai data GPS yang sudah ada (tanpa sumber lokasi kedua).
    @SuppressLint("MissingPermission")
    private fun activateLocation() {
        if (locationActive) return
        val m = map ?: return
        val s = style ?: return
        if (!hasLocationPermission()) return
        try {
            val lc = m.locationComponent
            lc.activateLocationComponent(
                LocationComponentActivationOptions.builder(context, s)
                    .useDefaultLocationEngine(false)
                    .build()
            )
            lc.isLocationComponentEnabled = true
            lc.renderMode = RenderMode.GPS
            lastFix?.let { lc.forceLocationUpdate(it) }
            locationActive = true
            if (followCar) startTracking()
        } catch (_: Throwable) {}
    }

    private fun startTracking() {
        val m = map ?: return
        val fix = lastFix
        val moving = lastSpeedKmh > 8f
        wasMoving = moving
        if (locationActive) {
            val lc = m.locationComponent
            lc.cameraMode = CameraMode.TRACKING_GPS
            lc.zoomWhileTracking(if (moving) 17.0 else 16.0)
            lc.tiltWhileTracking(if (moving) 45.0 else 0.0)
        } else if (fix != null) {
            m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(fix.latitude, fix.longitude), 16.0))
        }
    }

    fun onLocation(location: Location, speedKmh: Float) {
        lastFix = location
        lastSpeedKmh = speedKmh
        activateLocation()
        if (locationActive) map?.locationComponent?.forceLocationUpdate(location)
        val moving = speedKmh > 8f
        if (followCar && mapView.visibility == View.VISIBLE && (!locationActive || moving != wasMoving)) {
            startTracking()
        }
    }

    /** Posisi & ukuran lubang kartu peta dalam piksel layar; ukuran 0 = sembunyikan. */
    fun setRect(x: Int, y: Int, w: Int, h: Int) {
        if (w <= 0 || h <= 0) {
            mapView.visibility = View.GONE
            return
        }
        val lp = mapView.layoutParams as FrameLayout.LayoutParams
        if (lp.width != w || lp.height != h || lp.leftMargin != x || lp.topMargin != y) {
            lp.width = w
            lp.height = h
            lp.leftMargin = x
            lp.topMargin = y
            mapView.layoutParams = lp
        }
        val wasHidden = mapView.visibility != View.VISIBLE
        mapView.visibility = View.VISIBLE
        if (wasHidden && followCar) startTracking()
    }

    fun recenter() {
        followCar = true
        startTracking()
    }

    fun search(query: String) {
        val near = lastFix
        geocodeExecutor.execute {
            val result = geocode(query, near)
            mainHandler.post { showSearchResult(query, result) }
        }
    }

    // Geocoder bawaan Android: tanpa API key.
    @Suppress("DEPRECATION")
    private fun geocode(query: String, near: Location?): Address? {
        if (!Geocoder.isPresent()) return null
        val geocoder = Geocoder(context, Locale.forLanguageTag("id-ID"))
        return try {
            // Utamakan hasil di sekitar mobil (±~65 km), lalu cari tanpa batas wilayah.
            var results = near?.let {
                geocoder.getFromLocationName(
                    query, 1,
                    it.latitude - 0.6, it.longitude - 0.6,
                    it.latitude + 0.6, it.longitude + 0.6,
                )
            }
            if (results.isNullOrEmpty()) results = geocoder.getFromLocationName(query, 1)
            results?.firstOrNull()
        } catch (_: Exception) {
            null
        }
    }

    private fun setDestinationPoint(target: LatLng) {
        val source = destSource
        if (source == null) {
            pendingDest = target
            return
        }
        pendingDest = null
        source.setGeoJson(Feature.fromGeometry(Point.fromLngLat(target.longitude, target.latitude)))
    }

    private fun showSearchResult(query: String, address: Address?) {
        val m = map
        if (address == null || m == null) {
            runJs("window.onNativeMapSearchResult && window.onNativeMapSearchResult(false, ${JSONObject.quote(query)})")
            return
        }
        val target = LatLng(address.latitude, address.longitude)
        setDestinationPoint(target)
        followCar = false
        if (locationActive) m.locationComponent.cameraMode = CameraMode.NONE
        val fix = lastFix
        try {
            if (fix != null) {
                val bounds = LatLngBounds.Builder()
                    .include(target)
                    .include(LatLng(fix.latitude, fix.longitude))
                    .build()
                val padding = (60 * context.resources.displayMetrics.density).toInt()
                m.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, padding))
            } else {
                m.animateCamera(CameraUpdateFactory.newLatLngZoom(target, 15.0))
            }
        } catch (_: Exception) {
            m.moveCamera(CameraUpdateFactory.newLatLngZoom(target, 15.0))
        }
        val detail = address.getAddressLine(0) ?: query
        runJs("window.onNativeMapSearchResult && window.onNativeMapSearchResult(true, ${JSONObject.quote(detail)})")
    }

    fun clearSearch() {
        pendingDest = null
        destSource?.setGeoJson(EMPTY_GEOJSON)
        recenter()
    }

    fun onStart() = mapView.onStart()
    fun onResume() = mapView.onResume()
    fun onPause() = mapView.onPause()
    fun onStop() = mapView.onStop()
    fun onLowMemory() = mapView.onLowMemory()

    fun onDestroy() {
        geocodeExecutor.shutdownNow()
        mapView.onDestroy()
    }

    fun onSaveInstanceState(outState: Bundle) {
        val mapState = Bundle()
        mapView.onSaveInstanceState(mapState)
        outState.putBundle(STATE_KEY, mapState)
    }
}

/**
 * Wadah utama: WebView di depan, peta native di belakang. Sentuhan yang mendarat di dalam
 * kotak peta ditahan sebentar sambil bertanya ke halaman web (elementFromPoint) apakah titik
 * itu lubang peta atau tombol/menu HTML, lalu seluruh gerakan diteruskan ke tujuan yang benar.
 */
class TouchRouterLayout(context: Context) : FrameLayout(context) {
    var mapTarget: View? = null
    var hitTester: ((Float, Float, (Boolean) -> Unit) -> Unit)? = null

    private enum class Route { WEB, PENDING, MAP }

    private var route = Route.WEB
    private val pending = ArrayList<MotionEvent>()
    private var gestureId = 0

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            dropPending()
            val map = mapTarget
            val tester = hitTester
            if (map != null && tester != null && map.visibility == View.VISIBLE && isInside(map, ev)) {
                route = Route.PENDING
                pending.add(MotionEvent.obtain(ev))
                val id = ++gestureId
                tester(ev.x, ev.y) { onMap -> if (id == gestureId) resolve(if (onMap) Route.MAP else Route.WEB) }
                // Jika halaman tidak menjawab (sibuk), anggap sentuhan untuk halaman web.
                postDelayed({ if (id == gestureId) resolve(Route.WEB) }, 400)
                return true
            }
            route = Route.WEB
        }
        return when (route) {
            Route.PENDING -> {
                pending.add(MotionEvent.obtain(ev))
                true
            }
            Route.MAP -> sendToMap(ev)
            Route.WEB -> super.dispatchTouchEvent(ev)
        }
    }

    private fun resolve(target: Route) {
        if (route != Route.PENDING) return
        route = target
        val events = ArrayList(pending)
        pending.clear()
        for (e in events) {
            if (target == Route.MAP) sendToMap(e) else super.dispatchTouchEvent(e)
            e.recycle()
        }
    }

    private fun dropPending() {
        pending.forEach { it.recycle() }
        pending.clear()
        gestureId++
        route = Route.WEB
    }

    private fun isInside(v: View, ev: MotionEvent): Boolean =
        ev.x >= v.left && ev.x < v.right && ev.y >= v.top && ev.y < v.bottom

    private fun sendToMap(ev: MotionEvent): Boolean {
        val map = mapTarget ?: return false
        val e = MotionEvent.obtain(ev)
        e.offsetLocation(-map.left.toFloat(), -map.top.toFloat())
        val handled = map.dispatchTouchEvent(e)
        e.recycle()
        return handled
    }
}
