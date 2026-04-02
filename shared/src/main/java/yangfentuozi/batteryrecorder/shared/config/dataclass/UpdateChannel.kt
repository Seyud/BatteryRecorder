package yangfentuozi.batteryrecorder.shared.config.dataclass

/**
 * 更新检测通道。
 *
 * Stable 只跟踪正式版 release；
 * Prerelease 跟踪最新非 draft 发布，允许命中预发布与正式版。
 *
 * @param persistedValue 持久化到 SharedPreferences 的稳定整数值。
 */
enum class UpdateChannel(val persistedValue: Int) {
    Stable(0),
    Prerelease(1);

    companion object {
        /**
         * 根据持久化值恢复更新通道。
         *
         * @param value SharedPreferences 中存储的整数值。
         * @return 对应的更新通道；不存在时返回 null。
         */
        fun fromPersistedValue(value: Int): UpdateChannel? =
            entries.firstOrNull { it.persistedValue == value }
    }
}
