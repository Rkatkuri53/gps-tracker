package com.example.gpstracker

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                requestBackgroundPermission.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        }
    }

    private val requestBackgroundPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestLocationPermissions()

        setContent {
            GPSTrackerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigation(
                        onStartTracking = { startTrackingService() },
                        onStopTracking = { stopTrackingService() }
                    )
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

    private fun stopTrackingService() {
        stopService(Intent(this, TrackerService::class.java))
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}

@Composable
fun AppNavigation(
    onStartTracking: () -> Unit,
    onStopTracking: () -> Unit
) {
    var viewMode by remember { mutableStateOf("home") }
    var sessionIdToTrack by remember { mutableStateOf("") }

    if (viewMode == "home") {
        HomeScreen(
            onStartTracking = onStartTracking,
            onStopTracking = onStopTracking,
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
    onStartTracking: () -> Unit,
    onStopTracking: () -> Unit,
    onOpenViewer: (String) -> Unit
) {
    var isTracking by remember { mutableStateOf(false) }
    var sessionId by remember { mutableStateOf(SocketClient.currentSessionId) }
    var viewerCount by remember { mutableIntStateOf(0) }
    var inputSessionId by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    DisposableEffect(Unit) {
        sessionId = SocketClient.currentSessionId
        SocketClient.onSessionCreated = { id, _ ->
            scope.launch(Dispatchers.Main) { sessionId = id }
        }
        SocketClient.onViewerCountChanged = { count ->
            scope.launch(Dispatchers.Main) { viewerCount = count }
        }
        onDispose {
            SocketClient.onSessionCreated = null
            SocketClient.onViewerCountChanged = null
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = "Share My Location", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(16.dp))

        if (sessionId != null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Your Session ID:", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = sessionId!!,
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                    Text("Viewers tracking you: $viewerCount", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                if (isTracking) {
                    onStopTracking()
                    SocketClient.disconnect()
                    sessionId = null
                } else {
                    SocketClient.connect()
                    SocketClient.startTracking()
                    onStartTracking()
                }
                isTracking = !isTracking
            },
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) {
            Text(if (isTracking) "Stop Sharing" else "Start Sharing Location")
        }

        Spacer(modifier = Modifier.height(48.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(48.dp))

        Text(text = "Track Someone Else", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = inputSessionId,
            onValueChange = { inputSessionId = it },
            label = { Text("Enter Session ID") },
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
            Text("Open In-App Map")
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

        // Simply load the viewer page from the Render server.
        // This is the most standard, reliable WebView usage — a normal HTTPS page.
        // No file:// restrictions, no loadDataWithBaseURL quirks, no Kotlin template issues.
        // Socket.IO auto-serves its client JS at /socket.io/socket.io.js.
        // Leaflet JS+CSS are now served from the server's /js/ and /css/ directories.
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    setBackgroundColor(android.graphics.Color.parseColor("#0a0a1a"))

                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                            Log.d("ViewerWebView", "JS [${msg?.messageLevel()}] ${msg?.message()}")
                            return true
                        }
                    }
                    webViewClient = WebViewClient()

                    // Load the viewer page from the Render server
                    val serverUrl = "https://gps-tracker-htzc.onrender.com/track/$sessionId"
                    Log.d("ViewerWebView", "Loading: $serverUrl")
                    loadUrl(serverUrl)
                }
            },
            modifier = Modifier.fillMaxWidth().weight(1f)
        )
    }
}
