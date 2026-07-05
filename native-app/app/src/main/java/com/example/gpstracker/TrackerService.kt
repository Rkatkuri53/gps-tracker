package com.example.gpstracker

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*

class TrackerService : Service() {
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) {
                    Log.d("TrackerService", "Location: ${location.latitude}, ${location.longitude}")
                    SocketClient.sendLocation(
                        lat = location.latitude,
                        lng = location.longitude,
                        accuracy = location.accuracy,
                        speed = location.speed // Send raw m/s — viewer converts to km/h
                    )
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, "TrackerChannel")
            .setContentTitle("GPS Tracker Active")
            .setContentText("Sharing location in background...")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(1, notification)

        // Phase 1: FAST TRACKING (Every 2 seconds) to get immediate location
        val fastRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000)
            .setMinUpdateIntervalMillis(1000)
            .build()

        fusedLocationClient.requestLocationUpdates(
            fastRequest,
            locationCallback,
            Looper.getMainLooper()
        )

        // Phase 2: SLOW TRACKING (Every 60 seconds) after 10 seconds has passed
        handler.postDelayed({
            fusedLocationClient.removeLocationUpdates(locationCallback)
            
            val slowRequest = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 60000)
                .setMinUpdateIntervalMillis(60000)
                .build()

            fusedLocationClient.requestLocationUpdates(
                slowRequest,
                locationCallback,
                Looper.getMainLooper()
            )
            Log.d("TrackerService", "Switched to 60-second battery saving mode")
        }, 10000)

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        handler.removeCallbacksAndMessages(null)
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
