#include <jni.h>
#include <cstdint>
#include <cstring>
#include <arpa/inet.h>
#include <android/log.h>
#include <algorithm>

#define TAG "RTP_Native"
#define MTU_PAYLOAD 1388  // 1400 - 12 RTP header bytes

/**
 * Build a minimal 12-byte RTP header.
 */
static void write_rtp_header(
    uint8_t* buf,
    uint16_t seq,
    uint32_t timestamp,
    uint32_t ssrc,
    uint8_t  payload_type,
    bool     marker
) {
    buf[0] = 0x80;  // Version=2
    buf[1] = (uint8_t)((marker ? 0x80 : 0x00) | (payload_type & 0x7F));
    buf[2] = (uint8_t)(seq >> 8);
    buf[3] = (uint8_t)(seq & 0xFF);
    buf[4] = (uint8_t)(timestamp >> 24);
    buf[5] = (uint8_t)(timestamp >> 16);
    buf[6] = (uint8_t)(timestamp >>  8);
    buf[7] = (uint8_t)(timestamp & 0xFF);
    buf[8] = (uint8_t)(ssrc >> 24);
    buf[9] = (uint8_t)(ssrc >> 16);
    buf[10]= (uint8_t)(ssrc >>  8);
    buf[11]= (uint8_t)(ssrc & 0xFF);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_ultrashare_transport_RtpSenderNative_packetizeH265(
    JNIEnv* env,
    jobject /* this */,
    jbyteArray nalUnit,
    jlong    presentationTimeUs,
    jint     seqNum,
    jint     ssrc,
    jobjectArray outputPackets  // Pre-allocated ByteArray[] to fill
) {
    jsize nalLen = env->GetArrayLength(nalUnit);
    jbyte* nal   = env->GetByteArrayElements(nalUnit, nullptr);

    uint32_t rtp_ts = (uint32_t)((presentationTimeUs * 90LL) / 1000LL);
    int packet_count = 0;

    if (nalLen <= MTU_PAYLOAD) {
        // Single packet
        jbyteArray pkt = (jbyteArray)env->GetObjectArrayElement(outputPackets, 0);
        jbyte* buf = env->GetByteArrayElements(pkt, nullptr);

        write_rtp_header((uint8_t*)buf, (uint16_t)seqNum,
                         rtp_ts, (uint32_t)ssrc, 96, true);
        memcpy(buf + 12, nal, nalLen);

        env->ReleaseByteArrayElements(pkt, buf, 0);
        packet_count = 1;
    } else {
        // Fragmentation Units (FU)
        int offset = 1;
        uint8_t nal_type = ((uint8_t)nal[0] >> 1) & 0x3F;
        int idx = 0;

        while (offset < nalLen) {
            int chunk = std::min((int)(nalLen - offset), MTU_PAYLOAD - 3);
            bool is_first = (offset == 1);
            bool is_last  = (offset + chunk >= nalLen);

            jbyteArray pkt = (jbyteArray)env->GetObjectArrayElement(outputPackets, idx);
            jbyte* buf = env->GetByteArrayElements(pkt, nullptr);

            write_rtp_header((uint8_t*)buf, (uint16_t)(seqNum + idx),
                             rtp_ts, (uint32_t)ssrc, 96, is_last);

            buf[12] = 0x62;
            buf[13] = 0x01;
            buf[14] = (jbyte)(
                (is_first ? 0x80 : 0x00) |
                (is_last  ? 0x40 : 0x00) |
                (nal_type & 0x3F)
            );
            memcpy(buf + 15, nal + offset, chunk);

            env->ReleaseByteArrayElements(pkt, buf, 0);
            offset += chunk;
            idx++;
        }
        packet_count = idx;
    }

    env->ReleaseByteArrayElements(nalUnit, nal, JNI_ABORT);
    return packet_count;
}
