package yangfentuozi.batteryrecorder.data.history

import yangfentuozi.batteryrecorder.shared.data.LineRecord

data class RecordDetailPowerStats(
    val averagePowerRaw: Double,
    val screenOnAveragePowerRaw: Double?,
    val screenOffAveragePowerRaw: Double?
)

object RecordDetailPowerStatsComputer {

    /**
     * 按记录文件的真实采样区间计算详情页功耗统计。
     *
     * @param records 已通过解析得到的有效记录点列表，要求时间戳按文件原始顺序传入
     * @return 返回总平均、亮屏平均、息屏平均三项原始功率；若有效区间不足则返回 null
     */
    fun compute(records: List<LineRecord>): RecordDetailPowerStats? {
        if (records.size < 2) return null

        var totalDurationMs = 0L
        var totalEnergyRawMs = 0.0
        var screenOnDurationMs = 0L
        var screenOnEnergyRawMs = 0.0
        var screenOffDurationMs = 0L
        var screenOffEnergyRawMs = 0.0

        var previous: LineRecord? = null
        records.forEach { current ->
            val previousRecord = previous
            previous = current
            if (previousRecord == null) return@forEach

            val durationMs = current.timestamp - previousRecord.timestamp
            if (durationMs <= 0L) return@forEach

            val energyRawMs =
                (previousRecord.power.toDouble() + current.power.toDouble()) * 0.5 * durationMs
            totalDurationMs += durationMs
            totalEnergyRawMs += energyRawMs

            if (previousRecord.isDisplayOn == 1) {
                screenOnDurationMs += durationMs
                screenOnEnergyRawMs += energyRawMs
                return@forEach
            }

            screenOffDurationMs += durationMs
            screenOffEnergyRawMs += energyRawMs
        }

        if (totalDurationMs <= 0L) return null

        return RecordDetailPowerStats(
            averagePowerRaw = totalEnergyRawMs / totalDurationMs.toDouble(),
            screenOnAveragePowerRaw = screenOnDurationMs.takeIf { it > 0L }?.let {
                screenOnEnergyRawMs / it.toDouble()
            },
            screenOffAveragePowerRaw = screenOffDurationMs.takeIf { it > 0L }?.let {
                screenOffEnergyRawMs / it.toDouble()
            }
        )
    }
}
