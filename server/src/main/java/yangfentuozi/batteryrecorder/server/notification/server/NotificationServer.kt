package yangfentuozi.batteryrecorder.server.notification.server

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Looper
import android.system.Os
import yangfentuozi.batteryrecorder.server.notification.LocalNotificationUtil
import yangfentuozi.batteryrecorder.server.notification.NotificationUtil
import yangfentuozi.batteryrecorder.server.notification.server.stream.NotificationStreamMessage
import yangfentuozi.batteryrecorder.server.notification.server.stream.StreamReader
import yangfentuozi.batteryrecorder.shared.util.Handlers
import yangfentuozi.batteryrecorder.shared.util.LoggerX
import yangfentuozi.hiddenapi.compat.ServiceManagerCompat
import java.io.IOException
import kotlin.system.exitProcess

class NotificationServer {

    private val tag = "NotificationServer"

    @Volatile
    var isStopped = false

    @Volatile
    private var cleanedUp = false

    val notificationUtil: NotificationUtil
    var socket: LocalSocket? = null
    var reader: StreamReader? = null
    val serverSocket: LocalServerSocket
    val serverThread = Thread({
        try {
            LoggerX.i(tag, "@serverRunnable: 等待客户端")
            socket = serverSocket.accept()
            LoggerX.i(tag, "@serverRunnable: 接受客户端")
            reader = StreamReader(socket!!.inputStream)
            while (!isStopped) {
                when (val message = reader!!.readNext() ?: break) {
                    is NotificationStreamMessage.Data -> notificationUtil.updateNotification(message.info)
                    NotificationStreamMessage.CancelNotification -> notificationUtil.cancelNotification()
                    is NotificationStreamMessage.SetCompatibilityMode ->
                        notificationUtil.setCompatibilityModeEnabled(message.enabled)
                    NotificationStreamMessage.Stop -> {
                        isStopped = true
                        Handlers.main.post { exitProcess(0) }
                    }
                }
            }
        } catch (e: IOException) {
            if (!isStopped) LoggerX.e(tag, "@serverRunnable: 处理客户端请求时出现异常", tr = e)
        } finally {
            reader?.let { runCatching { it.close() } }
            socket?.let { runCatching { it.close() } }
        }
        reader = null
        socket = null
        notificationUtil.cancelNotification()
        exitProcess(0)
    }, "ServerSocketThread")

    init {
        LoggerX.i(
            tag,
            "init: NotificationServer 初始化开始, uid=${Os.getuid()}"
        )
        if (Looper.getMainLooper() == null) {
            @Suppress("DEPRECATION")
            Looper.prepareMainLooper()
        }
        Handlers.initMainThread()

        if (Os.getuid() != 2000) {
            LoggerX.i(tag, "init: uid 不为 2000, 执行降权")
            @Suppress("DEPRECATION")
            Os.setuid(2000)
        }

        LoggerX.i(tag, "init: 等待 notification, activity 服务")
        ServiceManagerCompat.waitService("notification")
        ServiceManagerCompat.waitService("activity")
        notificationUtil = LocalNotificationUtil()

        LoggerX.i(tag, "init: 创建 LocalServerSocket 通信服务")
        serverSocket = LocalServerSocket(SOCKET_NAME)

        Runtime.getRuntime().addShutdownHook(Thread { this.stopServiceImmediately() })
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            LoggerX.a(thread.name, "NotificationServer crashed", tr = throwable)
            LoggerX.writer?.close()
        }
        serverThread.start()
        Looper.loop()
    }

    private fun stopServiceImmediately() {
        if (cleanedUp) return
        LoggerX.i(tag, "停止服务")
        cleanedUp = true
        isStopped = true
        runCatching {
            serverThread.interrupt()
            reader?.let { runCatching { it.close() } }
            socket?.let { runCatching { it.close() } }
            notificationUtil.cancelNotification()
            serverSocket.close()
        }
    }

    companion object {
        const val SOCKET_NAME = "BatteryRecorder_NotificationServer"
    }
}
