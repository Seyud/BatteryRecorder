package yangfentuozi.batteryrecorder.server.notification

import yangfentuozi.batteryrecorder.server.notification.server.ChildServerBridge
import yangfentuozi.batteryrecorder.server.notification.server.stream.StreamWriter

class RemoteNotificationUtil(
    val bridge: ChildServerBridge,
    initialCompatibilityModeEnabled: Boolean
) : NotificationUtil {

    private val lock = Any()
    private var compatibilityModeEnabled = initialCompatibilityModeEnabled
    private var compatibilityConfigDirty = true
    private var configuredWriter: StreamWriter? = null

    /**
     * 更新通知子进程的兼容模式配置。
     *
     * @param enabled `true` 表示每次更新通知都新建 Builder；`false` 表示继续复用 Builder。
     * @return 无。
     */
    override fun setCompatibilityModeEnabled(enabled: Boolean) {
        synchronized(lock) {
            if (compatibilityModeEnabled != enabled) {
                compatibilityModeEnabled = enabled
                compatibilityConfigDirty = true
            }
            flushCompatibilityConfigLocked()
        }
    }

    override fun updateNotification(info: NotificationInfo) {
        synchronized(lock) {
            flushCompatibilityConfigLocked()
            bridge.writer?.write(info)
        }
    }

    override fun cancelNotification() {
        synchronized(lock) {
            flushCompatibilityConfigLocked()
            bridge.writer?.writeCancel()
        }
    }

    private fun flushCompatibilityConfigLocked() {
        val writer = bridge.writer ?: return
        if (!compatibilityConfigDirty && writer === configuredWriter) return
        writer.writeCompatibilityModeEnabled(compatibilityModeEnabled)
        configuredWriter = writer
        compatibilityConfigDirty = false
    }
}
