package com.example.gpstracker

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat

class TrackerService : Service() {
    private lateinit var locationManager: LocationManager
    private var persistentId: String? = null

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (persistentId == null) return
            Log.d("TrackerService", "Location fetched: ${location.latitude}, ${location.longitude}")
            
            // Save coordinates for the UI to display
            val prefs = getSharedPreferences("GPS_PREFS", Context.MODE_PRIVATE)
            prefs.edit()
                .putString("LAST_LAT", location.latitude.toString())
                .putString("LAST_LNG", location.longitude.toString())
                .putString("LAST_TIME", java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date()))
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
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        val prefs = getSharedPreferences("GPS_PREFS", Context.MODE_PRIVATE)
        persistentId = prefs.getString("PERSISTENT_ID", null)
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, "TrackerChannel")
            .setContentTitle("GPS Tracker Active")
            .setContentText("Continuous tracking enabled (Network + GPS)")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(1, notification)
        Toast.makeText(this, "Live Tracking Started", Toast.LENGTH_SHORT).show()

        try {
            // Instantly try to send last known Network location
            val lastNet = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (lastNet != null && persistentId != null) {
                LocationSender.sendLocation(this, persistentId!!, lastNet.latitude, lastNet.longitude, lastNet.accuracy, lastNet.speed)
            }

            // Register for guaranteed location updates every 30 seconds
            locationManager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER,
                30000L,
                0f,
                locationListener,
                Looper.getMainLooper()
            )
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                30000L,
                0f,
                locationListener,
                Looper.getMainLooper()
            )
            Log.d("TrackerService", "Registered Network and GPS providers for 30s updates")
        } catch (e: Exception) {
            Log.e("TrackerService", "Failed to start location updates", e)
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        locationManager.removeUpdates(locationListener)
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
