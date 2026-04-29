package com.ultrashare.transport

import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Feature #37: Separate high-priority data channel for input events.
 * Input events NEVER compete with video RTP packets on this channel.
 */
class DataChannel {

    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var outputStream: DataOutputStream? = null
    private var inputStream: DataInputStream? = null
    private val isRunning = AtomicBoolean(false)

    val sendQueue = LinkedBlockingQueue<ControlMessage>(100)

    var onTouchEvent: ((Float, Float, Int) -> Unit)? = null  // x, y, action
    var onKeyEvent: ((Int, Int) -> Unit)? = null              // keyCode, action
    var onGestureEvent: ((String, Map<String, Float>) -> Unit)? = null
    var onClipboardEvent: ((String) -> Unit)? = null          // Feature #85

    // Host: listen for viewer control input
    fun startAsHost(port: Int = 5005) {
        isRunning.set(true)
        Thread {
            try {
                serverSocket = ServerSocket(port)
                clientSocket = serverSocket?.accept()
                inputStream = DataInputStream(clientSocket!!.getInputStream())
                outputStream = DataOutputStream(clientSocket!!.getOutputStream())
                readLoop()
            } catch (e: Exception) {
                Log.e("DataChannel", "Host error: ${e.message}")
            }
        }.apply { name = "UltraShare-DataCh-Host"; isDaemon = true }.start()
    }

    // Viewer: connect to host
    fun connectAsViewer(hostIp: String, port: Int = 5005) {
        isRunning.set(true)
        Thread {
            try {
                clientSocket = Socket(hostIp, port)
                outputStream = DataOutputStream(clientSocket!!.getOutputStream())
                inputStream = DataInputStream(clientSocket!!.getInputStream())
                startWriteLoop()
                readLoop()
            } catch (e: Exception) {
                Log.e("DataChannel", "Viewer error: ${e.message}")
            }
        }.apply { name = "UltraShare-DataCh-Viewer"; isDaemon = true }.start()
    }

    private fun readLoop() {
        try {
            while (isRunning.get()) {
                val type = inputStream?.readByte() ?: break
                when (type) {
                    MSG_TOUCH -> {
                        val x = inputStream!!.readFloat()
                        val y = inputStream!!.readFloat()
                        val action = inputStream!!.readInt()
                        onTouchEvent?.invoke(x, y, action)
                    }
                    MSG_KEY -> {
                        val code = inputStream!!.readInt()
                        val action = inputStream!!.readInt()
                        onKeyEvent?.invoke(code, action)
                    }
                    MSG_CLIPBOARD -> {
                        val len = inputStream!!.readInt()
                        val bytes = ByteArray(len)
                        inputStream!!.readFully(bytes)
                        onClipboardEvent?.invoke(String(bytes))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("DataChannel", "Read error: ${e.message}")
        } finally {
            cleanup()
        }
    }

    private fun startWriteLoop() {
        Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            while (isRunning.get()) {
                val msg = sendQueue.take()
                try {
                    outputStream?.let { os ->
                        os.writeByte(msg.type.toInt())
                        when (msg) {
                            is ControlMessage.Touch -> {
                                os.writeFloat(msg.x)
                                os.writeFloat(msg.y)
                                os.writeInt(msg.action)
                            }
                            is ControlMessage.Key -> {
                                os.writeInt(msg.keyCode)
                                os.writeInt(msg.action)
                            }
                            is ControlMessage.Clipboard -> {
                                val bytes = msg.text.toByteArray()
                                os.writeInt(bytes.size)
                                os.write(bytes)
                            }
                        }
                        os.flush()
                    }
                } catch (e: Exception) {
                    break
                }
            }
        }.apply { name = "UltraShare-DataCh-Writer"; isDaemon = true }.start()
    }

    fun stop() {
        isRunning.set(false)
        cleanup()
    }

    private fun cleanup() {
        try {
            clientSocket?.close()
            serverSocket?.close()
        } catch (e: Exception) {}
    }

    companion object {
        const val MSG_TOUCH: Byte = 1
        const val MSG_KEY: Byte = 2
        const val MSG_CLIPBOARD: Byte = 3
    }

    sealed class ControlMessage(val type: Byte) {
        data class Touch(val x: Float, val y: Float, val action: Int)
            : ControlMessage(MSG_TOUCH)
        data class Key(val keyCode: Int, val action: Int)
            : ControlMessage(MSG_KEY)
        data class Clipboard(val text: String)
            : ControlMessage(MSG_CLIPBOARD)
    }
}
