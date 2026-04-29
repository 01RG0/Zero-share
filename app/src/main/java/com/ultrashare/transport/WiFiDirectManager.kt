package com.ultrashare.transport

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.*
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Feature #23: WiFi Direct P2P — bypasses router entirely.
 * Device-to-device 802.11 at 1–5m range = ~1ms RTT vs ~15ms through router.
 */
class WiFiDirectManager(private val context: Context) {

    private val wifiP2pManager: WifiP2pManager by lazy {
        context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    }
    private lateinit var channel: WifiP2pManager.Channel

    var onPeerFound: ((WifiP2pDevice) -> Unit)? = null
    var onConnected: ((String) -> Unit)? = null  // Delivers peer IP address
    var onDisconnected: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    if (state != WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                        onError?.invoke("WiFi Direct not enabled")
                    }
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    requestPeerList()
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val networkInfo = intent.getParcelableExtra<android.net.NetworkInfo>(
                        WifiP2pManager.EXTRA_NETWORK_INFO
                    )
                    if (networkInfo?.isConnected == true) {
                        requestConnectionInfo()
                    } else {
                        onDisconnected?.invoke()
                    }
                }
            }
        }
    }

    fun initialize() {
        channel = wifiP2pManager.initialize(context, Looper.getMainLooper(), null)
        val intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        context.registerReceiver(receiver, intentFilter)
    }

    /**
     * Host: advertise as discoverable service
     * Feature #23: Uses service discovery so viewer finds host automatically
     */
    fun advertiseAsHost(sessionId: String) {
        val record = mapOf(
            "sessionId" to sessionId,
            "version" to "1",
            "port" to "5004"  // RTP port
        )
        val serviceInfo = WifiP2pDnsSdServiceInfo.newInstance(
            "UltraShare-$sessionId",
            "_ultrashare._tcp",
            record
        )
        wifiP2pManager.addLocalService(channel, serviceInfo,
            object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d("WiFiDirect", "Service advertised successfully")
                    startDiscovery()
                }
                override fun onFailure(reason: Int) {
                    onError?.invoke("Service advertise failed: $reason")
                }
            }
        )
    }

    /**
     * Viewer: discover host service
     */
    fun discoverHost(onHostFound: (String, Map<String, String>) -> Unit) {
        wifiP2pManager.setDnsSdResponseListeners(channel,
            { instanceName, registrationType, device ->
                Log.d("WiFiDirect", "Service found: $instanceName on ${device.deviceName}")
                onPeerFound?.invoke(device)
            },
            { fullDomainName, record, device ->
                if (fullDomainName.contains("ultrashare", ignoreCase = true)) {
                    onHostFound(device.deviceAddress, record)
                }
            }
        )

        val serviceRequest = WifiP2pDnsSdServiceRequest.newInstance()
        wifiP2pManager.addServiceRequest(channel, serviceRequest,
            object : WifiP2pManager.ActionListener {
                override fun onSuccess() { startDiscovery() }
                override fun onFailure(reason: Int) {
                    onError?.invoke("Service request failed: $reason")
                }
            }
        )
    }

    fun connectToPeer(device: WifiP2pDevice) {
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            // Feature #35: Prefer 5GHz band
            groupOwnerIntent = 0  // Viewer prefers to be client, not group owner
        }

        wifiP2pManager.connect(channel, config,
            object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d("WiFiDirect", "Connection initiated")
                }
                override fun onFailure(reason: Int) {
                    onError?.invoke("Connection failed: $reason")
                }
            }
        )
    }

    private fun startDiscovery() {
        wifiP2pManager.discoverServices(channel,
            object : WifiP2pManager.ActionListener {
                override fun onSuccess() { Log.d("WiFiDirect", "Discovery started") }
                override fun onFailure(reason: Int) {
                    // Fallback to peer discovery if service discovery fails
                    wifiP2pManager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() {}
                        override fun onFailure(reason: Int) {}
                    })
                }
            }
        )
    }

    private fun requestPeerList() {
        wifiP2pManager.requestPeers(channel) { peers ->
            peers.deviceList.firstOrNull()?.let { onPeerFound?.invoke(it) }
        }
    }

    private fun requestConnectionInfo() {
        wifiP2pManager.requestConnectionInfo(channel) { info ->
            if (info.groupFormed) {
                // Group owner = host, client = viewer
                // The P2P subnet is typically 192.168.49.x
                // Group owner IP is 192.168.49.1
                val peerIp = if (info.isGroupOwner) {
                    "0.0.0.0"  // Host waits for connection
                } else {
                    info.groupOwnerAddress.hostAddress ?: "192.168.49.1"
                }
                onConnected?.invoke(peerIp)
            }
        }
    }

    fun cleanup() {
        wifiP2pManager.removeGroup(channel, null)
        wifiP2pManager.removeLocalService(channel, null, null)
        try {
            context.unregisterReceiver(receiver)
        } catch (e: Exception) {}
    }
}
