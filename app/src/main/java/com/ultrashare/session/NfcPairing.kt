package com.ultrashare.session

import android.app.Activity
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import org.json.JSONObject

/**
 * Feature #62: NFC tap-to-connect.
 */
class NfcPairing(private val activity: Activity) {

    private val nfcAdapter = NfcAdapter.getDefaultAdapter(activity)

    fun advertiseAsHost(sessionInfo: QrPairing.SessionInfo) {
        if (nfcAdapter?.isEnabled != true) return

        val payload = JSONObject().apply {
            put("ip", sessionInfo.hostIp)
            put("vp", sessionInfo.videoPort)
            put("cp", sessionInfo.controlPort)
            put("key", sessionInfo.sessionKey)
            put("sid", sessionInfo.sessionId)
        }.toString()

        val record = NdefRecord.createMime("application/ultrashare", payload.toByteArray())
        val message = NdefMessage(record)

        nfcAdapter.setNdefPushMessage(message, activity)
    }

    fun listenForHost(onSessionReceived: (QrPairing.SessionInfo) -> Unit) {
        // Handle incoming NDEF in Activity.onNewIntent()
    }

    fun disable() {
        nfcAdapter?.setNdefPushMessage(null, activity)
    }
}
