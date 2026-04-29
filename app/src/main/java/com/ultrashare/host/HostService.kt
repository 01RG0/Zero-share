package com.ultrashare.host

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ultrashare.transport.RtpSender
import com.ultrashare.transport.WiFiDirectManager

/**
 * Feature #91: Foreground service with persistent notification.
 * Android will NOT kill this process. Critical for sustained streaming.
 */
class HostService : Service() {

    private lateinit var screenCaptureManager: ScreenCaptureManager
    private lateinit var hardwareEncoder: HardwareEncoder
    private lateinit var rtpSender: RtpSender
    private lateinit var wifiDirectManager: WiFiDirectManager
    private lateinit var thermalMonitor: ThermalMonitor
    private lateinit var idleSuppressor: IdleFrameSuppressor

    companion object {
        const val CHANNEL_ID = "ultrashare_host"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.ultrashare.START"
        const val ACTION_STOP = "com.ultrashare.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        const val EXTRA_PEER_IP = "peerIp"
        const val EXTRA_SESSION_ID = "sessionId"

        fun startIntent(context: Context, resultCode: Int, data: Intent,
                        peerIp: String, sessionId: String): Intent {
            return Intent(context, HostService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
                putExtra(EXTRA_PEER_IP, peerIp)
                putExtra(EXTRA_SESSION_ID, sessionId)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Initializing..."))

        screenCaptureManager = ScreenCaptureManager(this).also { it.initialize() }
        wifiDirectManager = WiFiDirectManager(this).also { it.initialize() }
        thermalMonitor = ThermalMonitor(this) { level ->
            handleThermalEvent(level)
        }.also { it.startMonitoring() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)!!
                val peerIp = intent.getStringExtra(EXTRA_PEER_IP)!!
                val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)!!

                startStreaming(resultCode, data, peerIp, sessionId)
            }
            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startStreaming(resultCode: Int, data: Intent, peerIp: String, sessionId: String) {
        val displayMetrics = resources.displayMetrics
        val width = displayMetrics.widthPixels
        val height = displayMetrics.heightPixels

        // Initialize RTP sender first (acquire WiFi lock ASAP)
        rtpSender = RtpSender(peerIp, 5004, this).also { it.start() }

        // Initialize HW encoder
        hardwareEncoder = HardwareEncoder(
            width = width,
            height = height,
            fps = 60,
            onEncodedFrame = { frameData, pts, isConfig ->
                // Send each encoded NAL unit via RTP
                rtpSender.sendVideoFrame(frameData, pts, isConfig, 0, 0x12345678)
                updateNotification("Streaming • ${width}x${height} @ 60fps")
            },
            onEncoderReady = { inputSurface ->
                // Now start capture feeding into encoder
                val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                        as MediaProjectionManager
                screenCaptureManager.startCapture(
                    resultCode = resultCode,
                    data = data,
                    encoderInputSurface = inputSurface,
                    manager = manager,
                    onStarted = { updateNotification("Streaming active") },
                    onError = { err -> updateNotification("Error: $err") }
                )
            },
            onError = { err -> updateNotification("Encoder error: $err") }
        ).also { it.startEncoding() }

        // Feature #93: Idle frame suppressor
        idleSuppressor = IdleFrameSuppressor(
            onSuppressStart = { /* pause RTP */ },
            onSuppressEnd   = { /* resume RTP */ },
            onHeartbeat     = { /* send keep-alive */ }
        ).also { it.start() }
    }

    private fun handleThermalEvent(level: ThermalMonitor.ThermalLevel) {
        when (level) {
            ThermalMonitor.ThermalLevel.NORMAL   -> hardwareEncoder.adaptBitrate(4_000_000)
            ThermalMonitor.ThermalLevel.WARM     -> hardwareEncoder.adaptBitrate(2_000_000)
            ThermalMonitor.ThermalLevel.HOT      -> {
                hardwareEncoder.adaptBitrate(1_000_000)
                screenCaptureManager.scaleResolution(0.75f)
            }
            ThermalMonitor.ThermalLevel.CRITICAL -> {
                hardwareEncoder.adaptBitrate(500_000)
                screenCaptureManager.scaleResolution(0.5f)
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "UltraShare Host",
            NotificationManager.IMPORTANCE_LOW
        ).apply { setSound(null, null) }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(status: String): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, HostService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("UltraShare — Hosting")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(status: String) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(status))
    }

    override fun onDestroy() {
        if (::screenCaptureManager.isInitialized) screenCaptureManager.stopCapture()
        if (::hardwareEncoder.isInitialized) hardwareEncoder.stopEncoding()
        if (::rtpSender.isInitialized) rtpSender.stop()
        if (::idleSuppressor.isInitialized) idleSuppressor.stop()
        if (::wifiDirectManager.isInitialized) wifiDirectManager.cleanup()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
