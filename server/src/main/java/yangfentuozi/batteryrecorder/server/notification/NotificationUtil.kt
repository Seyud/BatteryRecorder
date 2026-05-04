package yangfentuozi.batteryrecorder.server.notification

import yangfentuozi.batteryrecorder.shared.config.dataclass.ServerSettings

interface NotificationUtil {
    fun syncSettings(settings: ServerSettings)

    fun updateNotification(info: NotificationInfo)
    fun cancelNotification()
}
