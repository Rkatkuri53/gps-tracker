package com.example.gpstracker

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.gpstracker.theme.GPSTrackerTheme
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.util.UUID

class MainActivity : ComponentActivity() {

    private var persistentId: String = ""

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                requestBackgroundPermission.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            } else {
                startTrackingService()
            }
        }
    }

    private val requestBackgroundPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        startTrackingService()
        requestBatteryOptimization()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize Persistent ID
        val prefs = getSharedPreferences("GPS_PREFS", Context.MODE_PRIVATE)
        persistentId = prefs.getString("PERSISTENT_ID", null) ?: run {
            val newId = "device-" + UUID.randomUUID().toString().substring(0, 6)
            prefs.edit().putString("PERSISTENT_ID", newId).apply()
            newId
        }

        requestLocationPermissions()

        setContent {
            GPSTrackerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigation(persistentId)
                }
            }
        }
    }

    private fun requestLocationPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissionsLauncher.launch(missing.toTypedArray())
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestBackgroundPermission.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else {
            startTrackingService()
            requestBatteryOptimization()
        }
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent()
            val packageName = packageName
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                intent.action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            }
        }
    }

    private fun startTrackingService() {
        val intent = Intent(this, TrackerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}

@Composable
fun AppNavigation(persistentId: String) {
    var viewMode by remember { mutableStateOf("home") }
    var sessionIdToTrack by remember { mutableStateOf("") }

    if (viewMode == "home") {
        HomeScreen(
            persistentId = persistentId,
            onOpenViewer = { id ->
                sessionIdToTrack = id
                viewMode = "viewer"
            }
        )
    } else {
        ViewerScreen(
            sessionId = sessionIdToTrack,
            onBack = { viewMode = "home" }
        )
    }
}

@Composable
fun HomeScreen(
    persistentId: String,
    onOpenViewer: (String) -> Unit
) {
    var inputSessionId by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Zero-Click Tracking Active", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(8.dp))
                Text("This phone is now securely tracked.", style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(24.dp))
                Text("Your Tracking ID:", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = persistentId,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        
        // Show last known coordinates
        val prefs = androidx.compose.ui.platform.LocalContext.current.getSharedPreferences("GPS_PREFS", Context.MODE_PRIVATE)
        var lastLat by remember { mutableStateOf(prefs.getString("LAST_LAT", "Unknown")) }
        var lastLng by remember { mutableStateOf(prefs.getString("LAST_LNG", "Unknown")) }
        var lastTime by remember { mutableStateOf(prefs.getString("LAST_TIME", "Never")) }

        LaunchedEffect(Unit) {
            while(true) {
                kotlinx.coroutines.delay(2000)
                lastLat = prefs.getString("LAST_LAT", "Unknown")
                lastLng = prefs.getString("LAST_LNG", "Unknown")
                lastTime = prefs.getString("LAST_TIME", "Never")
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Last Known Location (From Phone)", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                if (lastLat == "Unknown") {
                    Text("Waiting for GPS lock...", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                } else {
                    Text("Lat: $lastLat", style = MaterialTheme.typography.bodyMedium)
                    Text("Lng: $lastLng", style = MaterialTheme.typography.bodyMedium)
                    Text("Time: $lastTime", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(24.dp))

        Text(text = "Track Someone Else", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = inputSessionId,
            onValueChange = { inputSessionId = it },
            label = { Text("Enter Tracking ID") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                if (inputSessionId.isNotBlank()) {
                    onOpenViewer(inputSessionId.trim())
                }
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
        ) {
            Text("Open Map")
        }
    }
}

@Composable
fun ViewerScreen(sessionId: String, onBack: () -> Unit) {
    androidx.activity.compose.BackHandler { onBack() }
    
    val context = androidx.compose.ui.platform.LocalContext.current
    var liveLocation by remember { mutableStateOf<GeoPoint?>(null) }
    var liveAccuracy by remember { mutableStateOf(0f) }
    var viewerCount by remember { mutableStateOf(0) }
    
    // Initialize Socket.IO
    DisposableEffect(sessionId) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val socket: Socket = try {
            IO.socket("https://gps-tracker-htzc.onrender.com")
        } catch (e: Exception) {
            Log.e("ViewerSocket", "Socket Error", e)
            throw RuntimeException(e)
        }
        
        socket.on(Socket.EVENT_CONNECT) {
            Log.d("ViewerSocket", "Connected")
            val joinData = JSONObject().put("sessionId", sessionId)
            socket.emit("join-session", joinData)
        }
        
        socket.on("session-data") { args ->
            val data = args[0] as JSONObject
            handler.post {
                if (data.has("locations")) {
                    val locations = data.getJSONArray("locations")
                    if (locations.length() > 0) {
                        val lastLoc = locations.getJSONObject(locations.length() - 1)
                        liveLocation = GeoPoint(lastLoc.getDouble("latitude"), lastLoc.getDouble("longitude"))
                        liveAccuracy = lastLoc.optDouble("accuracy", 0.0).toFloat()
                    }
                }
                if (data.has("viewerCount")) viewerCount = data.getInt("viewerCount")
            }
        }
        
        socket.on("location-update") { args ->
            val data = args[0] as JSONObject
            handler.post {
                liveLocation = GeoPoint(data.getDouble("latitude"), data.getDouble("longitude"))
                liveAccuracy = data.optDouble("accuracy", 0.0).toFloat()
            }
        }
        
        socket.connect()
        
        onDispose {
            socket.disconnect()
            socket.off()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(onClick = onBack) { Text("\u2190 Back") }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(text = "Tracking: $sessionId", style = MaterialTheme.typography.titleMedium)
                    Text(text = "Viewers: $viewerCount", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        AndroidView(
            factory = { ctx ->
                // Configure OSMDroid User-Agent
                Configuration.getInstance().userAgentValue = ctx.packageName
                
                MapView(ctx).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true)
                    // Start zoomed out so the user doesn't see a zoomed-in ocean if GPS is delayed
                    controller.setZoom(3.0) 
                }
            },
            update = { mapView ->
                liveLocation?.let { loc ->
                    var marker = mapView.overlays.filterIsInstance<Marker>().firstOrNull { it.id == "live_marker" }
                    if (marker == null) {
                        // First time getting a location lock
                        marker = Marker(mapView).apply {
                            id = "live_marker"
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                            title = "Live Location"
                        }
                        mapView.overlays.add(marker)
                        // Zoom in tightly now that we have a real location
                        mapView.controller.setZoom(16.0) 
                    }
                    
                    marker.position = loc

                    // Draw Accuracy Circle
                    var circle = mapView.overlays.filterIsInstance<org.osmdroid.views.overlay.Polygon>().firstOrNull { it.id == "accuracy_circle" }
                    if (circle == null) {
                        circle = org.osmdroid.views.overlay.Polygon().apply {
                            id = "accuracy_circle"
                            fillColor = android.graphics.Color.argb(50, 0, 150, 255) // Semi-transparent blue
                            strokeColor = android.graphics.Color.argb(150, 0, 150, 255)
                            strokeWidth = 2f
                        }
                        mapView.overlays.add(0, circle) // Add behind marker
                    }
                    if (liveAccuracy > 0) {
                        circle.points = org.osmdroid.views.overlay.Polygon.pointsAsCircle(loc, liveAccuracy.toDouble())
                    }

                    mapView.controller.animateTo(loc)
                    mapView.invalidate()
                }
            },
            modifier = Modifier.fillMaxWidth().weight(1f)
        )
    }
}
