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
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.LocationSource
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Peta Google native yang diletakkan DI BELAKANG WebView. Halaman web "melubangi" kartu peta
 * (latar transparan) dan melaporkan posisi lubang lewat setRect(), sehingga kolom pencarian,
 * HUD, dan menu HTML tetap tampil di atas peta.
 */
class NativeMapController(
    private val context: Context,
    container: TouchRouterLayout,
    savedState: Bundle?,
    private val runJs: (String) -> Unit,
) {
    companion object {
        private const val STATE_KEY = "elio_native_map"

        fun isAvailable(context: Context): Boolean {
            val key = try {
                @Suppress("DEPRECATION")
                context.packageManager
                    .getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
                    .metaData?.getString("com.google.android.geo.API_KEY")
            } catch (_: Exception) {
                null
            }
            if (key.isNullOrBlank()) return false
            return GoogleApiAvailability.getInstance()
                .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
        }
    }

    val mapView: MapView = MapView(context).apply {
        visibility = View.GONE
        layoutParams = FrameLayout.LayoutParams(0, 0, Gravity.TOP or Gravity.START)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val geocodeExecutor = Executors.newSingleThreadExecutor()
    private var map: GoogleMap? = null
    private var followCar = true
    private var lastFix: Location? = null
    private var lastSpeedKmh = 0f
    private var locationListener: LocationSource.OnLocationChangedListener? = null
    private var searchMarker: Marker? = null
    private var myLocationLayerOn = false

    init {
        mapView.onCreate(savedState?.getBundle(STATE_KEY))
        container.addView(mapView, 0)
        container.mapTarget = mapView
        mapView.getMapAsync { setupMap(it) }
    }

    private fun setupMap(m: GoogleMap) {
        map = m
        try {
            m.setMapStyle(MapStyleOptions.loadRawResourceStyle(context, R.raw.map_style_night))
        } catch (_: Exception) {}
        m.isTrafficEnabled = true
        m.isBuildingsEnabled = false
        m.uiSettings.apply {
            isCompassEnabled = true
            isMyLocationButtonEnabled = false
            isMapToolbarEnabled = false
            isZoomControlsEnabled = false
            isTiltGesturesEnabled = false
        }
        // Titik biru memakai data GPS yang sudah ada (tanpa sumber lokasi kedua).
        m.setLocationSource(object : LocationSource {
            override fun activate(listener: LocationSource.OnLocationChangedListener) {
                locationListener = listener
                lastFix?.let { listener.onLocationChanged(it) }
            }

            override fun deactivate() {
                locationListener = null
            }
        })
        m.setOnCameraMoveStartedListener { reason ->
            if (reason == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE) followCar = false
        }
        val fix = lastFix
        if (fix != null) {
            m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(fix.latitude, fix.longitude), 16f))
        } else {
            m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(-2.5, 118.0), 4.5f))
        }
        enableMyLocationLayer()
    }

    @SuppressLint("MissingPermission")
    private fun enableMyLocationLayer() {
        val m = map ?: return
        if (myLocationLayerOn) return
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return
        try {
            m.isMyLocationEnabled = true
            myLocationLayerOn = true
        } catch (_: SecurityException) {}
    }

    fun onLocation(location: Location, speedKmh: Float) {
        val firstFix = lastFix == null
        lastFix = location
        lastSpeedKmh = speedKmh
        locationListener?.onLocationChanged(location)
        enableMyLocationLayer()
        if (followCar && mapView.visibility == View.VISIBLE) moveToCar(animate = !firstFix)
    }

    private fun moveToCar(animate: Boolean) {
        val m = map ?: return
        val fix = lastFix ?: return
        val moving = lastSpeedKmh > 8f
        val position = CameraPosition.Builder()
            .target(LatLng(fix.latitude, fix.longitude))
            .zoom(if (moving) 17f else 16f)
            .bearing(if (moving && fix.hasBearing()) fix.bearing else m.cameraPosition.bearing)
            .tilt(if (moving) 45f else 0f)
            .build()
        val update = CameraUpdateFactory.newCameraPosition(position)
        if (animate) m.animateCamera(update, 900, null) else m.moveCamera(update)
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
        if (wasHidden && followCar) moveToCar(animate = false)
    }

    fun recenter() {
        followCar = true
        moveToCar(animate = true)
    }

    fun search(query: String) {
        val near = lastFix
        geocodeExecutor.execute {
            val result = geocode(query, near)
            mainHandler.post { showSearchResult(query, result) }
        }
    }

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

    private fun showSearchResult(query: String, address: Address?) {
        val m = map
        if (address == null || m == null) {
            runJs("window.onNativeMapSearchResult && window.onNativeMapSearchResult(false, ${JSONObject.quote(query)})")
            return
        }
        val target = LatLng(address.latitude, address.longitude)
        val detail = address.getAddressLine(0) ?: query
        searchMarker?.remove()
        searchMarker = m.addMarker(MarkerOptions().position(target).title(query).snippet(detail))
        searchMarker?.showInfoWindow()
        followCar = false
        val fix = lastFix
        try {
            if (fix != null) {
                val bounds = LatLngBounds.builder()
                    .include(target)
                    .include(LatLng(fix.latitude, fix.longitude))
                    .build()
                val padding = (60 * context.resources.displayMetrics.density).toInt()
                m.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, padding))
            } else {
                m.animateCamera(CameraUpdateFactory.newLatLngZoom(target, 15f))
            }
        } catch (_: Exception) {
            m.moveCamera(CameraUpdateFactory.newLatLngZoom(target, 15f))
        }
        runJs("window.onNativeMapSearchResult && window.onNativeMapSearchResult(true, ${JSONObject.quote(detail)})")
    }

    fun clearSearch() {
        searchMarker?.remove()
        searchMarker = null
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
