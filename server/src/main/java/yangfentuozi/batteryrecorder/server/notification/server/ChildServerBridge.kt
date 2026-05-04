package yangfentuozi.batteryrecorder.server.notification.server

import android.net.LocalSocket
import android.net.LocalSocketAddress
import yangfentuozi.batteryrecorder.server.Main
import yangfentuozi.batteryrecorder.server.notification.server.stream.StreamWriter
import yangfentuozi.batteryrecorder.shared.util.Handlers
import yangfentuozi.batteryrecorder.shared.util.LoggerX
import java.io.IOException

private const val TAG = "Bridge"

class ChildServerBridge(
    private val apkPath: String,
    private val onFail: () -> Unit = {},
) {

    private var retryCount = 0
    private val retryMaxTimes = 5

    private var isStopped = false

    lateinit var process: Process
        private set
    private val handler = Handlers.getHandler("WaitNotificationServerExitThread")
    private val waitRunnable = Runnable {
        try {
            while (process.isAlive) {
                Thread.sleep(1000)
                if (!socket.isConnected) {
                    for (index in 1..10) {
                        if (!process.isAlive) {
                            LoggerX.w(
                                TAG,
                                "NotificationServer 在建连前已退出, exitValue=${runCatching { process.exitValue() }.getOrNull()}",
                            )
                            break
                        }

                        socket = LocalSocket()
                        try {
                            socket.connect(LocalSocketAddress(NotificationServer.SOCKET_NAME))
                            writer = StreamWriter(socket.outputStream)
                            onWriterConnected?.invoke(writer!!)
                            LoggerX.i(
                                TAG,
                                "connectSocket: 已连接 NotificationServer, attempt=${index + 1}"
                            )
                            break
                        } catch (_: IOException) {
                            runCatching { socket.close() }
                            if (index < 9) {
                                Thread.sleep(500)
                            }
                        }
                    }

                    if (!socket.isConnected && process.isAlive)
                        process.destroyForcibly()
                }
            }
            retryCount++
            startNotificationServer()
        } catch (_: InterruptedException) {
        }
    }

    private var socket: LocalSocket = LocalSocket()
    var writer: StreamWriter? = null
        private set
    var onWriterConnected: ((StreamWriter) -> Unit)? = null

    init {
        startNotificationServer()
    }

    fun startNotificationServer() {
        if (isStopped) return
        if (retryCount > retryMaxTimes) onFail()
        process = ProcessBuilder(
            "app_process",
            "-Djava.class.path=$apkPath",
            "/system/bin",
            Main::class.java.name,
            "--notification-server"
        ).start()

        handler.removeCallbacks(waitRunnable)
        handler.post(waitRunnable)
    }

    fun stop() {
        isStopped = true
        handler.removeCallbacks(waitRunnable)
        Handlers.interrupt("WaitNotificationServerExitThread")
        writer?.writeClose()
        writer?.close()
        writer = null
        socket.close()
        Thread.sleep(100)
        process.destroyForcibly()
    }
}
