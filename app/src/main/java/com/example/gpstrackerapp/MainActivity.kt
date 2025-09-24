package com.example.gpstrackerapp

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.media.MediaPlayer
import android.os.Bundle
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.app.ActivityCompat
import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

class MainActivity : AppCompatActivity() {

    private lateinit var map: MapView
    private lateinit var myLocationOverlay: MyLocationNewOverlay
    private lateinit var locationProvider: GpsMyLocationProvider

    private var anchorMarker: Marker? = null
    private var anchorCircle: Polygon? = null
    private var anchorRadiusMeters = 50.0

    private val trackPoints = mutableListOf<GeoPoint>()
    private var trackLine: Polygon? = null

    private lateinit var tvInfo: TextView
    private lateinit var seekBarRadius: SeekBar
    private lateinit var mediaPlayer: MediaPlayer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(applicationContext, getSharedPreferences("osmdroid", MODE_PRIVATE))
        setContentView(R.layout.activity_main)

        map = findViewById(R.id.map)
        map.setMultiTouchControls(true)
        tvInfo = findViewById(R.id.tvInfo)
        seekBarRadius = findViewById(R.id.seekBarRadius)

        mediaPlayer = MediaPlayer.create(this, R.raw.alarm)

        // Location permission check
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
            return
        }

        // Setup location overlay
        locationProvider = GpsMyLocationProvider(this)
        myLocationOverlay = MyLocationNewOverlay(locationProvider, map)
        myLocationOverlay.enableMyLocation()
        map.overlays.add(myLocationOverlay)

        // Wait for first GPS fix
        myLocationOverlay.runOnFirstFix {
            runOnUiThread {
                val myPos: GeoPoint? = myLocationOverlay.myLocation
                if (myPos != null) {
                    map.controller.setZoom(16.0)
                    map.controller.setCenter(myPos)

                    // Place draggable anchor marker
                    anchorMarker = Marker(map).apply {
                        position = myPos
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        isDraggable = true
                        icon = ContextCompat.getDrawable(this@MainActivity, R.drawable.anchor_icon)
                        setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
                            override fun onMarkerDrag(marker: Marker?) {
                                marker?.position?.let { drawAnchorCircle(it, anchorRadiusMeters) }
                            }
                            override fun onMarkerDragEnd(marker: Marker?) {}
                            override fun onMarkerDragStart(marker: Marker?) {}
                        })
                    }
                    map.overlays.add(anchorMarker)
                    drawAnchorCircle(myPos, anchorRadiusMeters)

                    // Start drift monitoring
                    startDriftMonitoring()

                    // Start track line
                    trackLine = Polygon(map).apply {
                        outlinePaint.color = Color.BLUE
                        outlinePaint.strokeWidth = 4f
                        points = trackPoints
                    }
                    map.overlays.add(trackLine)
                    startTracking()
                }
            }
        }

        // SeekBar for radius
        anchorRadiusMeters = seekBarRadius.progress.toDouble()
        updateInfo()
        seekBarRadius.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                anchorRadiusMeters = progress.toDouble()
                anchorMarker?.position?.let { drawAnchorCircle(it, anchorRadiusMeters) }
                updateInfo()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Periodically update info box
        map.postDelayed(object : Runnable {
            override fun run() {
                updateInfo()
                map.postDelayed(this, 5000)
            }
        }, 5000)
    }

    private fun drawAnchorCircle(center: GeoPoint, radius: Double) {
        if (anchorCircle == null) {
            anchorCircle = Polygon(map)
            map.overlays.add(anchorCircle)
        }
        anchorCircle?.apply {
            fillColor = Color.argb(50, 0, 255, 0)
            strokeColor = Color.GREEN
            strokeWidth = 3f
            points = mutableListOf<GeoPoint>().apply {
                for (i in 0..36) {
                    val angle = Math.toRadians(i * 10.0)
                    val lat = center.latitude + (radius / 111111f) * Math.cos(angle)
                    val lon = center.longitude + (radius / (111111f * Math.cos(Math.toRadians(center.latitude)))) * Math.sin(angle)
                    add(GeoPoint(lat, lon))
                }
            }
        }
    }

    private fun startDriftMonitoring() {
        map.postDelayed(object : Runnable {
            override fun run() {
                val location: Location? = locationProvider.lastKnownLocation
                val anchor: GeoPoint? = anchorMarker?.position
                if (location != null && anchor != null) {
                    val distance = FloatArray(1)
                    android.location.Location.distanceBetween(
                        anchor.latitude, anchor.longitude,
                        location.latitude, location.longitude, distance
                    )
                    if (distance[0] > anchorRadiusMeters) {
                        Toast.makeText(
                            this@MainActivity,
                            "⚠️ Boat is outside the safety radius!",
                            Toast.LENGTH_SHORT
                        ).show()
                        mediaPlayer.start()
                    }
                }
                map.postDelayed(this, 5000)
            }
        }, 5000)
    }

    private fun startTracking() {
        map.postDelayed(object : Runnable {
            override fun run() {
                val current: GeoPoint? = myLocationOverlay.myLocation
                if (current != null) {
                    val lastPoint = trackPoints.lastOrNull()
                    val distance = lastPoint?.distanceToAsDouble(current) ?: Double.MAX_VALUE
                    if (distance > 1.0) {
                        trackPoints.add(current)
                        trackLine?.points = trackPoints
                    }
                }
                map.postDelayed(this, 10000)
            }
        }, 10000)
    }

    private fun updateInfo() {
        val location: Location? = locationProvider.lastKnownLocation
        val accuracyMeters = location?.accuracy ?: 0f
        tvInfo.text = "Radius: ${anchorRadiusMeters.toInt()} m | Accuracy: ${accuracyMeters.toInt()} m"
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer.release()
    }
}
