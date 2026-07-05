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

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ViewerScreen(sessionId: String, onBack: () -> Unit) {
    androidx.activity.compose.BackHandler { onBack() }

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
                Text(text = "Tracking: $sessionId", style = MaterialTheme.typography.titleMedium)
            }
        }

        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    setBackgroundColor(android.graphics.Color.parseColor("#0a0a1a"))

                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        setSupportZoom(true)
                        builtInZoomControls = true
                        displayZoomControls = false
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36"
                    }

                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                            Log.d("ViewerWebView", "JS [${msg?.messageLevel()}] ${msg?.message()}")
                            return true
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun onReceivedError(
                            view: WebView?,
                            request: android.webkit.WebResourceRequest?,
                            error: android.webkit.WebResourceError?
                        ) {
                            super.onReceivedError(view, request, error)
                            android.widget.Toast.makeText(
                                context, 
                                "WebView Error: ${error?.description}", 
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    }

                    val serverUrl = "https://gps-tracker-htzc.onrender.com/track/$sessionId"
                    Log.d("ViewerWebView", "Loading: $serverUrl")
                    loadUrl(serverUrl)
                }
            },
            modifier = Modifier.fillMaxWidth().weight(1f)
        )
    }
}
