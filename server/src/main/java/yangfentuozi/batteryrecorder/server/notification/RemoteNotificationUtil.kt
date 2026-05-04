package yangfentuozi.batteryrecorder.server.notification

import yangfentuozi.batteryrecorder.server.notification.server.ChildServerBridge
import yangfentuozi.batteryrecorder.shared.config.dataclass.ServerSettings

class RemoteNotificationUtil(private val bridge: ChildServerBridge) : NotificationUtil {
    private val lock = Any()

    override fun syncSettings(settings: ServerSettings) {
        synchronized(lock) {
            runCatching {
                bridge.writer?.writeSettings(settings)
            }
        }
    }

    override fun updateNotification(info: NotificationInfo) {
        synchronized(lock) {
            runCatching {
                bridge.writer?.write(info)
            }
        }
    }

    override fun cancelNotification() {
        synchronized(lock) {
            runCatching {
                bridge.writer?.writeCancel()
            }
        }
    }
}
