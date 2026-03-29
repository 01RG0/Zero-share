package com.ultrashare.transport

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/**
 * Feature #24: mDNS discovery for same-LAN fallback.
 * No internet needed, works through router.
 */
class LanDiscovery(private val context: Context) {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    companion object {
        const val SERVICE_TYPE = "_ultrashare._tcp."
        const val TAG = "LanDiscovery"
    }

    /**
     * Host: register on LAN so viewer can find it without internet
     */
    fun advertiseOnLan(port: Int, sessionId: String, onRegistered: (String) -> Unit) {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "UltraShare-$sessionId"
            serviceType = SERVICE_TYPE
            this.port = port
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.d(TAG, "mDNS registered: ${info.serviceName}")
                onRegistered(info.serviceName)
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Registration failed: $errorCode")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }

        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
    }

    /**
     * Viewer: discover host on LAN
     */
    fun discoverOnLan(onFound: (String, Int) -> Unit) {
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery start failed: $errorCode")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "mDNS discovery started")
            }
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceName.startsWith("UltraShare")) {
                    nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}
                        override fun onServiceResolved(info: NsdServiceInfo) {
                            val host = info.host.hostAddress ?: return
                            onFound(host, info.port)
                        }
                    })
                }
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
        }

        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    fun stopDiscovery() {
        registrationListener?.let { nsdManager.unregisterService(it) }
        discoveryListener?.let { nsdManager.stopServiceDiscovery(it) }
    }
}
