package yangfentuozi.batteryrecorder.shared.data

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class LineRecord(
    /** 采样时间戳，单位毫秒。 */
    val timestamp: Long,
    /** 原始功率值，单位微瓦。 */
    val power: Long,
    /** 采样时的前台应用包名；为空表示未识别到前台应用。 */
    val packageName: String?,
    /** 采样时的电量百分比。 */
    val capacity: Int,
    /** 屏幕状态；`1` 表示亮屏，`0` 表示息屏。 */
    val isDisplayOn: Int,
    /** 当前电池状态，仅运行时使用，不参与记录文件落盘。 */
    val status: BatteryStatus,
    /** 电池温度，沿用系统原始单位。 */
    val temp: Int,
    /** 电池电压，单位微伏。 */
    val voltage: Long,
    /** 电池电流，单位微安。 */
    val current: Long
) : Parcelable {
    override fun toString(): String {
        return "$timestamp,$power,$packageName,$capacity,$isDisplayOn,$temp,$voltage,$current"
    }

    companion object {
        /** 当前记录文件固定列数。 */
        const val PERSISTED_COLUMN_COUNT = 8
        /** 当前记录文件时间戳字段的固定长度。 */
        const val PERSISTED_TIMESTAMP_LENGTH = 13

        /** 落盘格式中的时间戳列索引。 */
        private const val TIMESTAMP_INDEX = 0
        /** 落盘格式中的功率列索引。 */
        private const val POWER_INDEX = 1
        /** 落盘格式中的前台包名列索引。 */
        private const val PACKAGE_NAME_INDEX = 2
        /** 落盘格式中的电量列索引。 */
        private const val CAPACITY_INDEX = 3
        /** 落盘格式中的屏幕状态列索引。 */
        private const val DISPLAY_ON_INDEX = 4
        /** 落盘格式中的温度列索引。 */
        private const val TEMP_INDEX = 5
        /** 落盘格式中的电压列索引。 */
        private const val VOLTAGE_INDEX = 6
        /** 落盘格式中的电流列索引。 */
        private const val CURRENT_INDEX = 7

        /**
         * 从单行 CSV 记录解析出 `LineRecord`。
         *
         * @param line 原始记录行。
         * @return 解析成功返回 `LineRecord`，否则返回 `null`。
         */
        fun fromString(line: String) : LineRecord? =
            fromParts(line.split(","))

        /**
         * 从已经拆分好的字段列表解析出 `LineRecord`。
         *
         * 当前只接受最新的 8 列落盘格式；旧格式与损坏记录统一返回 `null`。
         *
         * @param parts 已按逗号拆分的字段列表。
         * @return 解析成功返回 `LineRecord`，否则返回 `null`。
         */
        internal fun fromParts(parts: List<String>) : LineRecord? {
            if (parts.size != PERSISTED_COLUMN_COUNT) return null

            val timestamp = parts[TIMESTAMP_INDEX].toLongOrNull() ?: return null
            val power = parts[POWER_INDEX].toLongOrNull() ?: return null
            val packageName = parts[PACKAGE_NAME_INDEX]
            val capacity = parts[CAPACITY_INDEX].toIntOrNull() ?: return null
            val isDisplayOn = parts[DISPLAY_ON_INDEX].toIntOrNull() ?: return null
            val temp = parts[TEMP_INDEX].toIntOrNull() ?: return null
            val voltage = parts[VOLTAGE_INDEX].toLongOrNull() ?: return null
            val current = parts[CURRENT_INDEX].toLongOrNull() ?: return null
            return LineRecord(
                timestamp, power, packageName, capacity, isDisplayOn, BatteryStatus.Unknown, temp, voltage, current
            )
        }
    }
}
