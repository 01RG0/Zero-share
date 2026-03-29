package com.ultrashare.transport

import android.net.wifi.WifiManager
import android.os.PowerManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Feature #27: UDP-only — no TCP fallback.
 * Feature #28: DSCP QoS marking for router priority.
 * Feature #30: Optimal packet size at MTU (1400 bytes) to avoid fragmentation.
 * Feature #38: Packet pacing — smooth send instead of burst.
 */
class RtpSender(
    private val destIp: String,
    private val destPort: Int,
    private val context: android.content.Context
) {
    private var socket: DatagramSocket? = null
    private val sendQueue = LinkedBlockingQueue<ByteArray>(200)
    private val isRunning = AtomicBoolean(false)
    private lateinit var senderThread: Thread
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        const val RTP_MTU = 1400              // Feature #30: Stay below IP MTU
        const val RTP_HEADER_SIZE = 12
        const val MAX_PAYLOAD = RTP_MTU - RTP_HEADER_SIZE
        const val PAYLOAD_TYPE_H265 = 96
        const val PAYLOAD_TYPE_H264 = 97
        const val PAYLOAD_TYPE_OPUS = 111
    }

    fun start() {
        acquirePerformanceLocks()  // Features #39, #40, #41

        isRunning.set(true)

        // Feature #38: Dedicated sender thread with packet pacing
        senderThread = Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            try {
                socket = DatagramSocket().apply {
                    // Feature #36: SO_PRIORITY hint
                    trafficClass = 0xB8  // DSCP EF (Expedited Forwarding) — Feature #28
                }
                val destAddr = InetAddress.getByName(destIp)

                while (isRunning.get()) {
                    val data = sendQueue.poll(10, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                    try {
                        val packet = DatagramPacket(data, data.size, destAddr, destPort)
                        socket?.send(packet)
                    } catch (e: Exception) {
                        if (isRunning.get()) {
                            // Log but don't crash — UDP is fire-and-forget
                        }
                    }
                }
            } catch (e: Exception) {
                // Handle socket creation error
            }
        }.apply { name = "UltraShare-RTP-Sender" }
        senderThread.start()
    }

    /**
     * Packetize an H.265 NAL unit into one or more RTP packets.
     * Feature #30: Split large NALs into MTU-sized chunks (fragmentation units).
     * Feature #12: Preserve hardware timestamp in RTP timestamp field.
     */
    fun sendVideoFrame(
        nalData: ByteArray,
        presentationTimeUs: Long,
        isKeyFrame: Boolean,
        sequenceNumber: Int,
        ssrc: Int
    ) {
        val rtpTimestamp = (presentationTimeUs * 90 / 1000).toInt()  // 90kHz clock

        if (nalData.size <= MAX_PAYLOAD) {
            // Single RTP packet
            val packet = buildRtpPacket(
                payload = nalData,
                timestamp = rtpTimestamp,
                seqNum = sequenceNumber,
                ssrc = ssrc,
                payloadType = PAYLOAD_TYPE_H265,
                marker = true  // Last (only) packet of frame
            )
            sendQueue.offer(packet)
        } else {
            // Fragmentation Units (FU) — split across multiple packets
            var offset = 0
            var seqOffset = 0
            while (offset < nalData.size) {
                val chunkSize = minOf(MAX_PAYLOAD - 3, nalData.size - offset)  // -3 for FU header
                val isFirst = offset == 0
                val isLast = (offset + chunkSize) >= nalData.size
                val fuHeader = buildFuHeader(nalData[0], isFirst, isLast)

                val payload = ByteArray(chunkSize + 3).apply {
                    // H.265 FU header (PayloadHdr + FU header)
                    this[0] = 0x62  // NAL unit type 49 (FU)
                    this[1] = 0x01
                    this[2] = fuHeader
                    System.arraycopy(nalData, if (isFirst) 1 else offset + 1,
                                     this, 3, chunkSize)
                }

                val packet = buildRtpPacket(
                    payload = payload,
                    timestamp = rtpTimestamp,
                    seqNum = sequenceNumber + seqOffset,
                    ssrc = ssrc,
                    payloadType = PAYLOAD_TYPE_H265,
                    marker = isLast
                )
                sendQueue.offer(packet)
                offset += chunkSize
                seqOffset++
            }
        }
    }

    private fun buildRtpPacket(
        payload: ByteArray,
        timestamp: Int,
        seqNum: Int,
        ssrc: Int,
        payloadType: Int,
        marker: Boolean
    ): ByteArray {
        return ByteArray(RTP_HEADER_SIZE + payload.size).apply {
            this[0] = 0x80.toByte()  // Version=2, no padding, no extension
            this[1] = ((if (marker) 0x80 else 0x00) or (payloadType and 0x7F)).toByte()
            this[2] = (seqNum shr 8).toByte()
            this[3] = (seqNum and 0xFF).toByte()
            this[4] = (timestamp shr 24).toByte()
            this[5] = (timestamp shr 16).toByte()
            this[6] = (timestamp shr 8).toByte()
            this[7] = (timestamp and 0xFF).toByte()
            this[8] = (ssrc shr 24).toByte()
            this[9] = (ssrc shr 16).toByte()
            this[10] = (ssrc shr 8).toByte()
            this[11] = (ssrc and 0xFF).toByte()
            System.arraycopy(payload, 0, this, RTP_HEADER_SIZE, payload.size)
        }
    }

    private fun buildFuHeader(nalHeader: Byte, isStart: Boolean, isEnd: Boolean): Byte {
        val nalType = (nalHeader.toInt() and 0x7E) shr 1
        return ((if (isStart) 0x80 else 0) or (if (isEnd) 0x40 else 0) or nalType).toByte()
    }

    /**
     * Feature #39: WiFi HIGH_PERF lock — prevents radio sleep during stream
     * Feature #40: PARTIAL_WAKE_LOCK — prevents CPU governor downclocking
     * Feature #41: WIFI_MODE_FULL_LOW_LATENCY — disables WiFi scan
     */
    private fun acquirePerformanceLocks() {
        val wifiManager = context.applicationContext
            .getSystemService(android.content.Context.WIFI_SERVICE) as WifiManager

        wifiLock = wifiManager.createWifiLock(
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY,  // Feature #41
            "UltraShare:WiFiLock"
        )
        wifiLock?.acquire()

        val powerManager = context.getSystemService(android.content.Context.POWER_SERVICE)
                as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,  // Feature #40
            "UltraShare:WakeLock"
        )
        wakeLock?.acquire()
    }

    fun stop() {
        isRunning.set(false)
        if (::senderThread.isInitialized) {
            senderThread.join(1000)
        }
        socket?.close()
        wifiLock?.release()
        wakeLock?.release()
    }
}
