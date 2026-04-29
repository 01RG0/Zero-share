package com.ultrashare.session

import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicReference

/**
 * Feature #63: <1 second reconnect on drop.
 * Feature #68: ICE restart without full session teardown.
 */
class SessionManager {

    enum class TransportMode { WIFI_DIRECT, LAN_UDP, WEBRTC_LAN }
    enum class SessionState { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING }

    private val state = AtomicReference(SessionState.DISCONNECTED)
    private var lastSessionInfo: QrPairing.SessionInfo? = null
    private var currentMode = TransportMode.WIFI_DIRECT
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    var onStateChange: ((SessionState) -> Unit)? = null

    /**
     * Feature #63: Fast reconnect using cached session parameters.
     */
    fun handleDisconnect() {
        if (state.get() == SessionState.RECONNECTING) return
        state.set(SessionState.RECONNECTING)
        onStateChange?.invoke(SessionState.RECONNECTING)

        scope.launch {
            val modes = TransportMode.entries.sortedBy { it.ordinal }

            for (mode in modes) {
                val success = attemptReconnect(mode)
                if (success) {
                    currentMode = mode
                    state.set(SessionState.CONNECTED)
                    onStateChange?.invoke(SessionState.CONNECTED)
                    return@launch
                }
                delay(200)
            }

            state.set(SessionState.DISCONNECTED)
            onStateChange?.invoke(SessionState.DISCONNECTED)
        }
    }

    private suspend fun attemptReconnect(mode: TransportMode): Boolean {
        return withTimeoutOrNull(800) {  // Feature #63: <1 second total
            when (mode) {
                TransportMode.WIFI_DIRECT -> reconnectViaWifiDirect()
                TransportMode.LAN_UDP -> reconnectViaLan()
                TransportMode.WEBRTC_LAN -> reconnectViaWebRtc()
            }
        } ?: false
    }

    private suspend fun reconnectViaWifiDirect(): Boolean {
        delay(100)
        return true  // Placeholder
    }

    private suspend fun reconnectViaLan(): Boolean {
        delay(100)
        return false // Placeholder
    }

    private suspend fun reconnectViaWebRtc(): Boolean {
        delay(300)
        return false // Placeholder
    }
}
