package yangfentuozi.batteryrecorder.server.notification.server.stream

import yangfentuozi.batteryrecorder.server.notification.NotificationInfo
import yangfentuozi.batteryrecorder.shared.config.dataclass.ServerSettings

object StreamProtocol {
    // 4 bytes 标志位
    const val MAGIC: Int = 0x4C524543 // "LREC"
    const val FLAG_DATA = 1
    const val FLAG_STOP = 2
    const val FLAG_CANCEL = 3
    const val FLAG_SETTINGS = 4
}

sealed interface NotificationStreamMessage {
    data class Data(val info: NotificationInfo) : NotificationStreamMessage
    data class Settings(val settings: ServerSettings?) : NotificationStreamMessage
    data object CancelNotification : NotificationStreamMessage
    data object Stop : NotificationStreamMessage
}
