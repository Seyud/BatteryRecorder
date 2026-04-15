package yangfentuozi.batteryrecorder.server.notification.server.stream

import yangfentuozi.batteryrecorder.server.notification.NotificationInfo

object StreamProtocol {
    // 4 bytes 标志位
    const val MAGIC: Int = 0x4C524543 // "LREC"
    const val FLAG_DATA = 1
    const val FLAG_STOP = 2
    const val FLAG_CANCEL = 3
    const val FLAG_SET_COMPATIBILITY_MODE = 4
}

sealed interface NotificationStreamMessage {
    data class Data(val info: NotificationInfo) : NotificationStreamMessage
    data class SetCompatibilityMode(val enabled: Boolean) : NotificationStreamMessage
    data object CancelNotification : NotificationStreamMessage
    data object Stop : NotificationStreamMessage
}
