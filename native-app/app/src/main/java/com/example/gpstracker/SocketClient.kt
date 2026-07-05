package com.example.gpstracker

import android.util.Log
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject
import java.net.URISyntaxException

object SocketClient {
    private var socket: Socket? = null
    private const val TAG = "SocketClient"
    var currentSessionId: String? = null
    var shareLink: String? = null

    // Callbacks
    var onSessionCreated: ((String, String) -> Unit)? = null
    var onViewerCountChanged: ((Int) -> Unit)? = null

    fun connect() {
        if (socket?.connected() == true) return

        try {
            // Pointing to the Render server with auto-reconnect
            val opts = IO.Options().apply {
                reconnection = true
                reconnectionAttempts = Integer.MAX_VALUE
                reconnectionDelay = 1000
                reconnectionDelayMax = 5000
                timeout = 20000
            }
            socket = IO.socket("https://gps-tracker-htzc.onrender.com", opts)
            
            socket?.on(Socket.EVENT_CONNECT) {
                Log.d(TAG, "Connected to server")
                
                if (currentSessionId != null) {
                    // We already have a session — rejoin it (handles server restarts)
                    val data = JSONObject().apply { put("sessionId", currentSessionId) }
                    socket?.emit("rejoin-tracking", data)
                    Log.d(TAG, "Rejoining existing session: $currentSessionId")
                }
                // If currentSessionId is null, we wait for user to tap "Start Sharing"
                // Do NOT auto-emit start-tracking!
            }

            socket?.on("session-created") { args ->
                val data = args[0] as JSONObject
                currentSessionId = data.getString("sessionId")
                shareLink = "https://gps-tracker-htzc.onrender.com" + data.getString("shareLink")
                Log.d(TAG, "Session created: $currentSessionId")
                
                onSessionCreated?.invoke(currentSessionId!!, shareLink!!)
            }

            socket?.on("rejoin-confirmed") { args ->
                val data = args[0] as JSONObject
                val sessionId = data.getString("sessionId")
                Log.d(TAG, "Rejoin confirmed for session: $sessionId")
            }

            socket?.on("viewer-count") { args ->
                val data = args[0] as JSONObject
                val count = data.getInt("count")
                onViewerCountChanged?.invoke(count)
            }

            socket?.connect()
        } catch (e: URISyntaxException) {
            e.printStackTrace()
        }
    }

    fun startTracking() {
        // Explicitly request a new session from the server
        socket?.emit("start-tracking")
        Log.d(TAG, "Requested new tracking session")
    }

    fun sendLocation(lat: Double, lng: Double, accuracy: Float, speed: Float) {
        if (currentSessionId == null) return
        val data = JSONObject().apply {
            put("sessionId", currentSessionId)
            put("latitude", lat)
            put("longitude", lng)
            put("accuracy", accuracy)
            put("speed", speed)  // Send raw m/s — viewer converts to km/h
            put("heading", 0.0)
            put("altitude", 0.0)
            put("timestamp", System.currentTimeMillis())
        }
        socket?.emit("location-update", data)
        Log.d(TAG, "Sent location update")
    }

    fun disconnect() {
        if (currentSessionId != null) {
            val data = JSONObject().apply { put("sessionId", currentSessionId) }
            socket?.emit("stop-tracking", data)
        }
        socket?.disconnect()
        socket = null
        currentSessionId = null
        shareLink = null  // Clear stale share link
    }
}
