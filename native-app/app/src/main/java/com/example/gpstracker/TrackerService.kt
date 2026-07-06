package com.example.gpstracker

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*

class TrackerService : Service() {
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var persistentId: String? = null

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            val location = locationResult.lastLocation ?: return
            if (persistentId == null) return
            
            Log.d("TrackerService", "Location fetched: ${location.latitude}, ${location.longitude} Acc: ${location.accuracy}")
            
            // Save coordinates for the UI to display
            val prefs = getSharedPreferences("GPS_PREFS", Context.MODE_PRIVATE)
            prefs.edit()
                .putString("LAST_LAT", location.latitude.toString())
                .putString("LAST_LNG", location.longitude.toString())
                .putString("LAST_TIME", java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date()))
                .apply()
                
            LocationSender.sendLocation(
                context = this@TrackerService,
                sessionId = persistentId!!,
                lat = location.latitude,
                lng = location.longitude,
                accuracy = location.accuracy,
                speed = location.speed
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        val prefs = getSharedPreferences("GPS_PREFS", Context.MODE_PRIVATE)
        persistentId = prefs.getString("PERSISTENT_ID", null)
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, "TrackerChannel")
            .setContentTitle("GPS Tracker Active")
            .setContentText("Continuous tracking enabled")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(1, notification)
        }
        
        Toast.makeText(this, "Live Tracking Started", Toast.LENGTH_SHORT).show()

        try {
            // Get immediate last known location
            fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
                if (location != null && persistentId != null) {
                    LocationSender.sendLocation(this, persistentId!!, location.latitude, location.longitude, location.accuracy, location.speed)
                }
            }

            // Create highly accurate location request (5-second intervals for real-time tracking)
            val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000L)
                .setMinUpdateIntervalMillis(2000L)
                .setMinUpdateDistanceMeters(0f)
                .build()

            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
            Log.d("TrackerService", "Registered FusedLocationClient for real-time updates")
        } catch (e: Exception) {
            Log.e("TrackerService", "Failed to start location updates", e)
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "TrackerChannel",
                "Location Tracking",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}
