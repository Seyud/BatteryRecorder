package yangfentuozi.batteryrecorder.data.history

import yangfentuozi.batteryrecorder.shared.data.LineRecord
import yangfentuozi.batteryrecorder.shared.util.LoggerX

private const val TAG = "RecordDetailPowerStats"

private enum class CurrentUnit {
    MilliAmpere,
    MicroAmpere
}

data class RecordDetailPowerStats(
    val averagePowerRaw: Double,
    val screenOnAveragePowerRaw: Double?,
    val screenOffAveragePowerRaw: Double?,
    val totalTransferredMahBaseSigned: Double,
    val screenOnConsumedMahBase: Double,
    val screenOffConsumedMahBase: Double
)

object RecordDetailPowerStatsComputer {

    private const val MICRO_AMPERE_DIGITS_THRESHOLD = 10_000L
    private const val MILLIAMPERE_HOUR_DIVISOR = 3_600_000.0
    private const val MICROAMPERE_HOUR_DIVISOR = 3_600_000_000.0

    /**
     * 按记录文件的真实采样区间计算详情页功耗统计。
     *
     * @param records 已通过解析得到的有效记录点列表，要求时间戳按文件原始顺序传入
     * @return 返回总平均、亮屏平均、息屏平均三项原始功率，以及净 mAh 变化量和亮屏/息屏耗电量；若有效区间不足则返回 null
     */
    fun compute(records: List<LineRecord>): RecordDetailPowerStats? {
        if (records.size < 2) return null

        val currentUnit = inferCurrentUnit(records)
        var totalDurationMs = 0L
        var totalEnergyRawMs = 0.0
        var totalTransferredMahBaseSigned = 0.0
        var screenOnDurationMs = 0L
        var screenOnEnergyRawMs = 0.0
        var screenOnConsumedMahBase = 0.0
        var screenOffDurationMs = 0L
        var screenOffEnergyRawMs = 0.0
        var screenOffConsumedMahBase = 0.0

        var previous: LineRecord? = null
        records.forEach { current ->
            val previousRecord = previous
            previous = current
            if (previousRecord == null) return@forEach

            val durationMs = current.timestamp - previousRecord.timestamp
            if (durationMs <= 0L) return@forEach

            val energyRawMs =
                (previousRecord.power.toDouble() + current.power.toDouble()) * 0.5 * durationMs
            val consumedMahBase = computeConsumedMahBase(
                previousCurrent = previousRecord.current,
                currentCurrent = current.current,
                durationMs = durationMs,
                currentUnit = currentUnit
            )
            val transferredMahBaseSigned = computeTransferredMahBaseSigned(
                previousCurrent = previousRecord.current,
                currentCurrent = current.current,
                durationMs = durationMs,
                currentUnit = currentUnit
            )
            totalDurationMs += durationMs
            totalEnergyRawMs += energyRawMs
            totalTransferredMahBaseSigned += transferredMahBaseSigned

            if (previousRecord.isDisplayOn == 1) {
                screenOnDurationMs += durationMs
                screenOnEnergyRawMs += energyRawMs
                screenOnConsumedMahBase += consumedMahBase
                return@forEach
            }

            screenOffDurationMs += durationMs
            screenOffEnergyRawMs += energyRawMs
            screenOffConsumedMahBase += consumedMahBase
        }

        if (totalDurationMs <= 0L) return null

        val stats = RecordDetailPowerStats(
            averagePowerRaw = totalEnergyRawMs / totalDurationMs.toDouble(),
            screenOnAveragePowerRaw = screenOnDurationMs.takeIf { it > 0L }?.let {
                screenOnEnergyRawMs / it.toDouble()
            },
            screenOffAveragePowerRaw = screenOffDurationMs.takeIf { it > 0L }?.let {
                screenOffEnergyRawMs / it.toDouble()
            },
            totalTransferredMahBaseSigned = totalTransferredMahBaseSigned,
            screenOnConsumedMahBase = screenOnConsumedMahBase,
            screenOffConsumedMahBase = screenOffConsumedMahBase
        )
        LoggerX.d(
            TAG,
            "[记录详情] mAh 统计完成: unit=$currentUnit totalSignedMahBase=${stats.totalTransferredMahBaseSigned} screenOnMahBase=${stats.screenOnConsumedMahBase} screenOffMahBase=${stats.screenOffConsumedMahBase}"
        )
        return stats
    }

    /**
     * 根据记录文件中的电流数量级识别当前文件的电流单位。
     *
     * 按已确认的业务规则：
     * - 4 位及以下视为 mA
     * - 5 位及以上视为 uA
     *
     * 这里使用整条记录的最大绝对电流值统一判定，避免同一文件内逐点切换口径。
     *
     * @param records 当前记录文件的有效采样点
     * @return 返回用于本次 mAh 积分的电流单位
     */
    private fun inferCurrentUnit(records: List<LineRecord>): CurrentUnit {
        val maxAbsCurrent = records.maxOfOrNull { absCurrent(it.current) } ?: 0L
        val unit = if (maxAbsCurrent >= MICRO_AMPERE_DIGITS_THRESHOLD) {
            CurrentUnit.MicroAmpere
        } else {
            CurrentUnit.MilliAmpere
        }
        LoggerX.d(
            TAG,
            "[记录详情] 电流单位识别: maxAbsCurrent=$maxAbsCurrent unit=$unit"
        )
        return unit
    }

    /**
     * 按相邻两点的真实采样区间计算基础 mAh 消耗量。
     *
     * 这里不应用双电芯倍率：
     * - 统计器只负责产出单路基础值
     * - 展示层再根据 `dualCellEnabled` 统一决定是否乘 2
     *
     * @param previousCurrent 区间起点电流
     * @param currentCurrent 区间终点电流
     * @param durationMs 区间时长，单位毫秒
     * @param currentUnit 当前记录文件识别出的电流单位
     * @return 返回该区间对应的基础 mAh 消耗量
     */
    private fun computeConsumedMahBase(
        previousCurrent: Long,
        currentCurrent: Long,
        durationMs: Long,
        currentUnit: CurrentUnit
    ): Double {
        val averageAbsCurrent = (absCurrent(previousCurrent) + absCurrent(currentCurrent)) * 0.5
        val divisor = when (currentUnit) {
            CurrentUnit.MilliAmpere -> MILLIAMPERE_HOUR_DIVISOR
            CurrentUnit.MicroAmpere -> MICROAMPERE_HOUR_DIVISOR
        }
        return averageAbsCurrent * durationMs / divisor
    }

    /**
     * 按相邻两点的真实采样区间计算带符号的基础 mAh 变化量。
     *
     * 这里保留电流原始符号：
     * - 正值表示该区间净流入电池
     * - 负值表示该区间净流出电池
     *
     * 充电记录详情页的“电量变化”需要展示净充入量，因此不能沿用绝对值积分。
     *
     * @param previousCurrent 区间起点电流
     * @param currentCurrent 区间终点电流
     * @param durationMs 区间时长，单位毫秒
     * @param currentUnit 当前记录文件识别出的电流单位
     * @return 返回该区间对应的带符号基础 mAh 变化量
     */
    private fun computeTransferredMahBaseSigned(
        previousCurrent: Long,
        currentCurrent: Long,
        durationMs: Long,
        currentUnit: CurrentUnit
    ): Double {
        val averageCurrent = (previousCurrent.toDouble() + currentCurrent.toDouble()) * 0.5
        val divisor = when (currentUnit) {
            CurrentUnit.MilliAmpere -> MILLIAMPERE_HOUR_DIVISOR
            CurrentUnit.MicroAmpere -> MICROAMPERE_HOUR_DIVISOR
        }
        return averageCurrent * durationMs / divisor
    }

    /**
     * 返回 Long 电流值的安全绝对值。
     *
     * @param current 原始电流值
     * @return 返回绝对值；遇到 Long.MIN_VALUE 时退回 Long.MAX_VALUE，避免溢出
     */
    private fun absCurrent(current: Long): Long {
        if (current == Long.MIN_VALUE) return Long.MAX_VALUE
        return kotlin.math.abs(current)
    }
}
