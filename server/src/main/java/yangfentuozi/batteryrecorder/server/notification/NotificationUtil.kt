package yangfentuozi.batteryrecorder.server.notification

interface NotificationUtil {
    /**
     * 更新通知兼容模式。
     *
     * @param enabled `true` 表示每次更新通知都新建 Builder；`false` 表示继续复用 Builder。
     * @return 无。
     */
    fun setCompatibilityModeEnabled(enabled: Boolean)

    fun updateNotification(info: NotificationInfo)
    fun cancelNotification()
}
