package com.example.gpstracker

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object LocationSender {
    private const val TAG = "LocationSender"

    fun sendLocation(context: Context, sessionId: String, lat: Double, lng: Double, accuracy: Float, speed: Float) {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val url = URL("https://gps-tracker-htzc.onrender.com/api/location")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setRequestProperty("Accept", "application/json")
                connection.doOutput = true

                val json = JSONObject().apply {
                    put("sessionId", sessionId)
                    put("latitude", lat)
                    put("longitude", lng)
                    put("accuracy", accuracy)
                    put("speed", speed)
                }

                OutputStreamWriter(connection.outputStream).use { it.write(json.toString()) }
                
                val responseCode = connection.responseCode
                Log.d(TAG, "Sent HTTP location update. Response: $responseCode")
                
                if (responseCode == 200) {
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(context, "Location Sent! ($responseCode)", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(context, "Server Error: $responseCode", Toast.LENGTH_LONG).show()
                    }
                }
                
                connection.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send HTTP location", e)
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, "Network Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
