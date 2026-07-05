package com.example.gpstracker

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object LocationSender {
    private const val TAG = "LocationSender"
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    fun sendLocation(context: Context, sessionId: String, lat: Double, lng: Double, accuracy: Float, speed: Float) {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val json = JSONObject().apply {
                    put("sessionId", sessionId)
                    put("latitude", lat)
                    put("longitude", lng)
                    put("accuracy", accuracy)
                    put("speed", speed)
                }

                val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                
                val request = Request.Builder()
                    .url("https://gps-tracker-htzc.onrender.com/api/location")
                    .post(body)
                    .build()

                client.newCall(request).execute().use { response ->
                    val responseCode = response.code
                    val responseBody = response.body?.string() ?: ""
                    Log.d(TAG, "Sent HTTP location update. Response: $responseCode - $responseBody")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send HTTP location", e)
            }
        }
    }
}
