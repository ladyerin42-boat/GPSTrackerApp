package com.example.gpstrackerapp

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.media.MediaPlayer
import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.app.ActivityCompat
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.TilesOverlay
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
    private lateinit var btnStartTrack: Button
    private lateinit var mediaPlayer: MediaPlayer

    private val desiredAccuracyMeters = 10f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Configuration.getInstance().load(applicationContext, getSharedPreferences("osmdroid", MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = packageName

        setContentView(R.layout.activity_main)

        // Bind UI elements
        map = findViewById(R.id.map)
        tvInfo = findViewById(R.id.tvInfo)
        seekBarRadius = findViewById(R.id.seekBarRadius)
        btnStartTrack = findViewById(R.id.btnStartTrack)

        map.setMultiTouchControls(true)

        // --- Map Setup ---
        // Base map (MAPNIK)
        map.setTileSource(TileSourceFactory.MAPNIK)

        // OpenSeaMap overlay
        val seaMapTileSource = XYTileSource(
            "OpenSeaMap",
            0, 18, 256, ".png",
            arrayOf("https://tiles.openseamap.org/seamark/")
        )
        val seaMapProvider = MapTileProviderBasic(this, seaMapTileSource)
        val seaMapOverlay = TilesOverlay(seaMapProvider, this)
        seaMapOverlay.setLoadingBackgroundColor(Color.TRANSPARENT)
        map.overlays.add(seaMapOverlay)
        // -----------------

        mediaPlayer = MediaPlayer.create(this, R.raw.alarm)

        // Initialize button as disabled and showing loading
        btnStartTrack.isEnabled = false
        btnStartTrack.text = "Loading GPS…"

        // Request location permission if needed
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                1
            )
            return
        }

        // Setup location overlay
        locationProvider = GpsMyLocationProvider(this)
        myLocationOverlay = MyLocationNewOverlay(locationProvider, map)
        myLocationOverlay.enableMyLocation()
        map.overlays.add(myLocationOverlay)

        // Set initial map center
        val lastLocation: Location? = locationProvider.lastKnownLocation
        if (lastLocation != null) {
            map.controller.setCenter(GeoPoint(lastLocation.latitude, lastLocation.longitude))
            map.controller.setZoom(16.0)
        } else {
            map.controller.setCenter(GeoPoint(0.0, 0.0)) // fallback world center
            map.controller.setZoom(16.0)
        }

        // Poll GPS accuracy to update button
        checkAccuracyAndEnableButton()

        // Start Track button listener
        btnStartTrack.setOnClickListener {
            if (anchorMarker == null) {
                val location: Location? = locationProvider.lastKnownLocation
                if (location != null) {
                    val geoPoint = GeoPoint(location.latitude, location.longitude)
                    setupAnchor(geoPoint)
                } else {
                    Toast.makeText(this, "GPS location not available yet", Toast.LENGTH_SHORT).show()
                }
            } else {
                // Start new track from anchor position
                trackPoints.clear()
                trackLine?.points = trackPoints
                anchorMarker?.position?.let { trackPoints.add(it) }
                startTracking()
                Toast.makeText(this, "Started new track from anchor", Toast.LENGTH_SHORT).show()
            }
        }

        // SeekBar listener
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

        // Periodically update info text
        map.postDelayed(object : Runnable {
            override fun run() {
                updateInfo()
                map.postDelayed(this, 5000)
            }
        }, 5000)
    }

    private fun checkAccuracyAndEnableButton() {
        val location: Location? = locationProvider.lastKnownLocation

        if (location != null) {
            map.controller.setCenter(GeoPoint(location.latitude, location.longitude))
            if (location.accuracy <= desiredAccuracyMeters) {
                btnStartTrack.isEnabled = true
                btnStartTrack.text = "Start Track"
            } else {
                btnStartTrack.isEnabled = false
                btnStartTrack.text = "Loading GPS… (accuracy too low)"
            }
        } else {
            btnStartTrack.isEnabled = false
            btnStartTrack.text = "Loading GPS… (no signal)"
        }

        map.postDelayed({ checkAccuracyAndEnableButton() }, 1000)
    }

    private fun setupAnchor(location: GeoPoint) {
        if (anchorMarker == null) {
            anchorMarker = Marker(map).apply {
                position = location
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
            drawAnchorCircle(location, anchorRadiusMeters)
        }

        // Reset track points and line
        trackPoints.clear()
        if (trackLine == null) {
            trackLine = Polygon(map).apply {
                outlinePaint.color = Color.BLUE
                outlinePaint.strokeWidth = 4f
                map.overlays.add(this)
            }
        }
        trackLine?.points = trackPoints

        // Add anchor position as starting point for track (can be optional if you want GPS-only)
        // trackPoints.add(anchorMarker!!.position)

        // Center map
        map.controller.setZoom(16.0)
        map.controller.setCenter(anchorMarker!!.position)

        startDriftMonitoring()
        startTracking()
    }

    private fun drawAnchorCircle(center: GeoPoint, radius: Double) {
        if (anchorCircle == null) {
            anchorCircle = Polygon(map)
            map.overlays.add(anchorCircle)
        }

        anchorCircle?.apply {
            fillColor = Color.TRANSPARENT
            outlinePaint.apply {
                color = Color.rgb(255, 105, 180)
                strokeWidth = 6f
                style = android.graphics.Paint.Style.STROKE
            }
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

                if (location == null) {
                    Toast.makeText(this@MainActivity, "⚠️ GPS signal lost!", Toast.LENGTH_SHORT).show()
                    mediaPlayer.start()
                } else if (location.accuracy > desiredAccuracyMeters) {
                    Toast.makeText(this@MainActivity, "⚠️ GPS accuracy too low (${location.accuracy.toInt()} m)!", Toast.LENGTH_SHORT).show()
                    mediaPlayer.start()
                } else if (anchor != null) {
                    val distance = FloatArray(1)
                    android.location.Location.distanceBetween(
                        anchor.latitude, anchor.longitude,
                        location.latitude, location.longitude, distance
                    )
                    if (distance[0] > anchorRadiusMeters) {
                        Toast.makeText(this@MainActivity, "⚠️ Boat is outside the safety radius!", Toast.LENGTH_SHORT).show()
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
                    // Only add to track if GPS has moved significantly
                    val lastPoint = trackPoints.lastOrNull()
                    val movedDistance = lastPoint?.distanceToAsDouble(current) ?: Double.MAX_VALUE
                    if (movedDistance > 1.0) {
                        trackPoints.add(current)
                    }

                    // Determine line color based on distance to anchor
                    val lineColor = anchorMarker?.let { anchor ->
                        val distance = FloatArray(1)
                        android.location.Location.distanceBetween(
                            anchor.position.latitude, anchor.position.longitude,
                            current.latitude, current.longitude, distance
                        )
                        if (distance[0] <= anchorRadiusMeters) Color.BLUE else Color.RED
                    } ?: Color.BLUE // default to blue if no anchor

                    // Initialize trackLine if null
                    if (trackLine == null) {
                        trackLine = Polygon(map).apply {
                            outlinePaint.strokeWidth = 4f
                            map.overlays.add(this)
                        }
                    }

                    // Update trackLine with only GPS points
                    trackLine?.apply {
                        outlinePaint.color = lineColor
                        points = trackPoints
                    }
                }

                // Repeat every 10 seconds
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
