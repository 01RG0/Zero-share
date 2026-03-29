package com.ultrashare.transport

object RtpSenderNative {
    init {
        System.loadLibrary("ultrashare_rtp")
    }

    external fun packetizeH265(
        nalUnit: ByteArray,
        presentationTimeUs: Long,
        seqNum: Int,
        ssrc: Int,
        outputPackets: Array<ByteArray>
    ): Int
}
