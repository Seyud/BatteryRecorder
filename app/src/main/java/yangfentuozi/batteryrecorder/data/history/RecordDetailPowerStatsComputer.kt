package yangfentuozi.batteryrecorder.data.history

import yangfentuozi.batteryrecorder.shared.data.BatteryStatus
import yangfentuozi.batteryrecorder.shared.data.LineRecord
import yangfentuozi.batteryrecorder.shared.util.LoggerX
import kotlin.math.abs

private const val TAG = "RecordDetailPowerStats"
private const val SCREEN_OFF_DISPLAY_POWER_MIN_PERCENTILE = 0.90
private const val SCREEN_OFF_DISPLAY_POWER_MAX_PERCENTILE = 0.95
private const val SCREEN_OFF_WH_BASELINE_MIN_PERCENTILE = 0.30
private const val SCREEN_OFF_WH_BASELINE_MAX_PERCENTILE = 0.50
private const val SCREEN_OFF_HIGH_CONFIDENCE_DURATION_MS = 7_200_000L
private const val SCREEN_OFF_HIGH_CONFIDENCE_SAMPLE_COUNT = 1_000
private const val SCREEN_OFF_EXACT_AVERAGE_MIN_INTERVAL_RATIO = 0.90

/**
 * 记录详情页电量变化拆分结果。
 *
 * @param totalPercent 当前记录的总电量变化百分比。
 * @param screenOffPercent 息屏区间累计的电量变化百分比。
 * @param screenOnPercent 亮屏区间累计的电量变化百分比。
 */
data class CapacityChange(
    val totalPercent: Int,
    val screenOffPercent: Int,
    val screenOnPercent: Int
)

/**
 * 详情页功耗统计结果。
 *
 * 统计器只负责提供原始功率均值、时长拆分与电量变化拆分。
 *
 * 约束：
 * 1. `averagePowerRaw` 当前承载“总功耗展示值”，
 *    它由亮屏/息屏展示功耗按时长加权回推，确保三项功耗展示口径一致。
 * 2. `screenOffAveragePowerRaw` 当前承载“息屏功耗展示值”：
 *    当息屏区间里至少 `90%` 的间隔都落在 confident 阈值内时，直接回到精确平均；
 *    否则退化为基于 confident 样本的自适应高分位代表功率。
 * 3. `screenOffWhDisplayEnergyRawMs` 专门服务息屏 Wh 展示，
 *    会对长间隔做覆盖率缩放后的弱外推，且其基线分位独立于功耗展示值。
 */
data class RecordDetailPowerStats(
    val averagePowerRaw: Double,
    val screenOnAveragePowerRaw: Double?,
    val screenOffAveragePowerRaw: Double?,
    val totalConfidentEnergyRawMs: Double,
    val screenOnConfidentEnergyRawMs: Double,
    val screenOffConfidentEnergyRawMs: Double,
    val screenOffWhDisplayEnergyRawMs: Double,
    val totalDurationMs: Long,
    val screenOnDurationMs: Long,
    val screenOffDurationMs: Long,
    val capacityChange: CapacityChange
)

object RecordDetailPowerStatsComputer {

    /**
     * 按记录文件的真实采样区间计算详情页功耗统计。
     *
     * @param detailType 当前详情页记录类型，只接受充电和放电。
     * @param recordIntervalMs 当前详情页采样间隔配置，超过 `30x` 的区间视为长间隔；
     * 息屏功耗展示值会基于 confident 样本数量和时长在 `P90~P95` 之间自适应选取代表分位，
     * 息屏 Wh 会基于同一置信度在 `P30~P50` 之间选取弱外推基线。
     * @param records 已通过解析得到的有效记录点列表，要求时间戳按文件原始顺序传入
     * @return 返回总平均、亮屏平均、息屏平均三项原始功率，以及总/亮屏/息屏时长和电量变化拆分；若有效区间不足则返回 null
     */
    fun compute(
        detailType: BatteryStatus,
        recordIntervalMs: Long,
        records: List<LineRecord>
    ): RecordDetailPowerStats? {
        if (records.size < 2) return null

        val confidenceThresholdMs = recordIntervalMs * 30L
        var totalDurationMs = 0L
        var totalEnergyRawMs = 0.0
        var totalConfidentEnergyRawMs = 0.0
        var screenOnDurationMs = 0L
        var screenOnEnergyRawMs = 0.0
        var screenOnConfidentEnergyRawMs = 0.0
        var screenOnCapacityDropPercent = 0
        var screenOffDurationMs = 0L
        var screenOffEnergyRawMs = 0.0
        var screenOffConfidentEnergyRawMs = 0.0
        var screenOffConfidentDurationMs = 0L
        var screenOffCapacityDropPercent = 0
        var screenOffIntervalCount = 0
        var screenOffConfidentIntervalCount = 0
        val screenOffShortIntervalPowers = mutableListOf<WeightedPowerSample>()

        var previous: LineRecord? = null
        records.forEach { current ->
            val previousRecord = previous
            previous = current
            if (previousRecord == null) return@forEach

            val durationMs = current.timestamp - previousRecord.timestamp
            if (durationMs <= 0L) return@forEach

            val energyRawMs =
                (previousRecord.power.toDouble() + current.power.toDouble()) * 0.5 * durationMs
            val capacityDelta = computeCapacityDelta(
                detailType = detailType,
                previousCapacity = previousRecord.capacity,
                currentCapacity = current.capacity
            )
            totalDurationMs += durationMs
            totalEnergyRawMs += energyRawMs

            if (previousRecord.isDisplayOn == 1) {
                screenOnDurationMs += durationMs
                screenOnEnergyRawMs += energyRawMs
                if (durationMs <= confidenceThresholdMs) {
                    totalConfidentEnergyRawMs += energyRawMs
                    screenOnConfidentEnergyRawMs += energyRawMs
                }
                screenOnCapacityDropPercent += capacityDelta
                return@forEach
            }

            screenOffDurationMs += durationMs
            screenOffEnergyRawMs += energyRawMs
            screenOffIntervalCount++
            if (durationMs <= confidenceThresholdMs) {
                totalConfidentEnergyRawMs += energyRawMs
                screenOffConfidentEnergyRawMs += energyRawMs
                screenOffConfidentDurationMs += durationMs
                screenOffConfidentIntervalCount++
                screenOffShortIntervalPowers += WeightedPowerSample(
                    powerMagnitudeRaw = abs(energyRawMs / durationMs.toDouble()),
                    durationMs = durationMs
                )
            }
            screenOffCapacityDropPercent += capacityDelta
        }

        if (totalDurationMs <= 0L) return null

        val screenOffAveragePowerRaw = computeScreenOffDisplayPowerRaw(
            screenOffDurationMs = screenOffDurationMs,
            screenOffEnergyRawMs = screenOffEnergyRawMs,
            screenOffConfidentDurationMs = screenOffConfidentDurationMs,
            screenOffConfidentEnergyRawMs = screenOffConfidentEnergyRawMs,
            screenOffIntervalCount = screenOffIntervalCount,
            screenOffConfidentIntervalCount = screenOffConfidentIntervalCount,
            screenOffShortIntervalPowers = screenOffShortIntervalPowers
        )
        val screenOffWhDisplayEnergyRawMs = computeScreenOffWhDisplayEnergyRawMs(
            screenOffDurationMs = screenOffDurationMs,
            screenOffEnergyRawMs = screenOffEnergyRawMs,
            screenOffConfidentDurationMs = screenOffConfidentDurationMs,
            screenOffConfidentEnergyRawMs = screenOffConfidentEnergyRawMs,
            screenOffShortIntervalPowers = screenOffShortIntervalPowers
        )

        val screenOnAveragePowerRaw = screenOnDurationMs.takeIf { it > 0L }?.let {
            screenOnEnergyRawMs / it.toDouble()
        }
        val averagePowerRaw = computeTotalDisplayPowerRaw(
            totalDurationMs = totalDurationMs,
            totalEnergyRawMs = totalEnergyRawMs,
            screenOnDurationMs = screenOnDurationMs,
            screenOnAveragePowerRaw = screenOnAveragePowerRaw,
            screenOffDurationMs = screenOffDurationMs,
            screenOffAveragePowerRaw = screenOffAveragePowerRaw
        )
        val capacityChange = CapacityChange(
            totalPercent = screenOffCapacityDropPercent + screenOnCapacityDropPercent,
            screenOffPercent = screenOffCapacityDropPercent,
            screenOnPercent = screenOnCapacityDropPercent
        )
        val stats = RecordDetailPowerStats(
            averagePowerRaw = averagePowerRaw,
            screenOnAveragePowerRaw = screenOnAveragePowerRaw,
            screenOffAveragePowerRaw = screenOffAveragePowerRaw,
            totalConfidentEnergyRawMs = totalConfidentEnergyRawMs,
            screenOnConfidentEnergyRawMs = screenOnConfidentEnergyRawMs,
            screenOffConfidentEnergyRawMs = screenOffConfidentEnergyRawMs,
            screenOffWhDisplayEnergyRawMs = screenOffWhDisplayEnergyRawMs,
            totalDurationMs = totalDurationMs,
            screenOnDurationMs = screenOnDurationMs,
            screenOffDurationMs = screenOffDurationMs,
            capacityChange = capacityChange
        )
        LoggerX.d(
            TAG,
            "[记录详情] 统计完成: totalDurationMs=${stats.totalDurationMs} screenOnDurationMs=${stats.screenOnDurationMs} screenOffDurationMs=${stats.screenOffDurationMs} totalCapacity=${stats.capacityChange.totalPercent} screenOnCapacity=${stats.capacityChange.screenOnPercent} screenOffCapacity=${stats.capacityChange.screenOffPercent} thresholdMs=$confidenceThresholdMs confidentOffDurationMs=$screenOffConfidentDurationMs averagePowerRaw=${stats.averagePowerRaw} screenOffAveragePowerRaw=${stats.screenOffAveragePowerRaw} screenOffWhDisplayEnergyRawMs=${stats.screenOffWhDisplayEnergyRawMs}"
        )
        return stats
    }

    private data class WeightedPowerSample(
        val powerMagnitudeRaw: Double,
        val durationMs: Long
    )

    /**
     * 计算详情页息屏功耗展示值。
     *
     * 设计依据：
     * 只要息屏区间里至少 `90%` 的间隔都满足 confident 阈值，就说明这条记录基本完整，
     * 此时直接使用全量息屏区间的精确平均值。
     *
     * 只有当长间隔比例明显偏高时，数学平均值才会被严重稀释，无法反映用户真正关心的
     * “当前这条记录的典型息屏耗电水平”。这时才退化为：
     * 1. 仅使用 confident 息屏样本；
     * 2. 根据 confident 样本时长与样本数量计算置信度；
     * 3. 在 `P90~P95` 之间自适应选择代表分位。
     *
     * @param screenOffDurationMs 当前记录的总息屏时长。
     * @param screenOffEnergyRawMs 当前记录的总息屏积分能量，作为无 confident 区间时的回退值。
     * @param screenOffConfidentDurationMs 当前记录中落在 confident 阈值内的息屏时长。
     * @param screenOffConfidentEnergyRawMs 当前记录中落在 confident 阈值内的息屏积分能量。
     * @param screenOffIntervalCount 当前记录的息屏区间总数。
     * @param screenOffConfidentIntervalCount 当前记录中落在 confident 阈值内的息屏区间数量。
     * @param screenOffShortIntervalPowers 当前记录 confident 息屏区间的功率样本。
     * @return 返回用于详情页展示的息屏功耗原始值；无息屏数据时返回 `null`。
     */
    private fun computeScreenOffDisplayPowerRaw(
        screenOffDurationMs: Long,
        screenOffEnergyRawMs: Double,
        screenOffConfidentDurationMs: Long,
        screenOffConfidentEnergyRawMs: Double,
        screenOffIntervalCount: Int,
        screenOffConfidentIntervalCount: Int,
        screenOffShortIntervalPowers: List<WeightedPowerSample>
    ): Double? {
        if (screenOffDurationMs <= 0L) return null
        if (screenOffConfidentDurationMs <= 0L) {
            return screenOffEnergyRawMs / screenOffDurationMs.toDouble()
        }
        if (
            screenOffIntervalCount > 0 &&
            screenOffConfidentIntervalCount.toDouble() / screenOffIntervalCount.toDouble() >=
            SCREEN_OFF_EXACT_AVERAGE_MIN_INTERVAL_RATIO
        ) {
            return screenOffEnergyRawMs / screenOffDurationMs.toDouble()
        }

        val confidenceScore = computeScreenOffConfidenceScore(
            screenOffConfidentDurationMs = screenOffConfidentDurationMs,
            screenOffConfidentSampleCount = screenOffShortIntervalPowers.size
        )
        val displayPercentile = interpolatePercentile(
            minPercentile = SCREEN_OFF_DISPLAY_POWER_MIN_PERCENTILE,
            maxPercentile = SCREEN_OFF_DISPLAY_POWER_MAX_PERCENTILE,
            confidenceScore = confidenceScore
        )
        val representativePowerRaw = weightedPercentile(
            samples = screenOffShortIntervalPowers,
            percentile = displayPercentile
        ) ?: return screenOffConfidentEnergyRawMs / screenOffConfidentDurationMs.toDouble()
        return observedEnergyDirection(
            primaryEnergy = screenOffConfidentEnergyRawMs,
            fallbackEnergy = screenOffEnergyRawMs
        ) * representativePowerRaw
    }

    /**
     * 计算详情页总功耗展示值。
     *
     * 设计依据：
     * 当亮屏和息屏已经使用展示口径时，总功耗不能继续保留原始数学平均值，
     * 否则三项展示功耗会互相打架。这里统一改为由亮屏/息屏展示值按时长加权回推。
     *
     * @param totalDurationMs 当前记录总时长。
     * @param totalEnergyRawMs 当前记录总积分能量；当分区展示值缺失时作为回退值。
     * @param screenOnDurationMs 当前记录亮屏时长。
     * @param screenOnAveragePowerRaw 当前记录亮屏展示功耗。
     * @param screenOffDurationMs 当前记录息屏时长。
     * @param screenOffAveragePowerRaw 当前记录息屏展示功耗。
     * @return 返回用于详情页展示的总功耗原始值。
     */
    private fun computeTotalDisplayPowerRaw(
        totalDurationMs: Long,
        totalEnergyRawMs: Double,
        screenOnDurationMs: Long,
        screenOnAveragePowerRaw: Double?,
        screenOffDurationMs: Long,
        screenOffAveragePowerRaw: Double?
    ): Double {
        if (totalDurationMs <= 0L) return 0.0

        var weightedEnergyRawMs = 0.0
        var weightedDurationMs = 0L
        if (screenOnDurationMs > 0L && screenOnAveragePowerRaw != null) {
            weightedEnergyRawMs += screenOnAveragePowerRaw * screenOnDurationMs.toDouble()
            weightedDurationMs += screenOnDurationMs
        }
        if (screenOffDurationMs > 0L && screenOffAveragePowerRaw != null) {
            weightedEnergyRawMs += screenOffAveragePowerRaw * screenOffDurationMs.toDouble()
            weightedDurationMs += screenOffDurationMs
        }
        if (weightedDurationMs <= 0L) {
            return totalEnergyRawMs / totalDurationMs.toDouble()
        }
        return weightedEnergyRawMs / weightedDurationMs.toDouble()
    }

    /**
     * 计算息屏 Wh 展示使用的原始能量。
     *
     * 设计依据：
     * 纯 confident 积分会系统性低估长间隔记录，而旧版整段补算又会系统性高估。
     * 这里对长间隔只做覆盖率缩放后的弱外推，且基线分位会随 confident 质量
     * 在 `P30~P50` 之间自适应变化：
     * `confidentEnergy + baselinePower * longGapDuration * confidentCoverage`。
     *
     * @param screenOffDurationMs 当前记录的总息屏时长。
     * @param screenOffEnergyRawMs 当前记录的总息屏积分能量，作为无 confident 区间时的回退值。
     * @param screenOffConfidentDurationMs 当前记录中落在 confident 阈值内的息屏时长。
     * @param screenOffConfidentEnergyRawMs 当前记录中落在 confident 阈值内的息屏积分能量。
     * @param screenOffShortIntervalPowers 当前记录 confident 息屏区间的功率样本，用于提取稳健基线。
     * @return 返回用于详情页息屏 Wh 展示的原始能量值。
     */
    private fun computeScreenOffWhDisplayEnergyRawMs(
        screenOffDurationMs: Long,
        screenOffEnergyRawMs: Double,
        screenOffConfidentDurationMs: Long,
        screenOffConfidentEnergyRawMs: Double,
        screenOffShortIntervalPowers: List<WeightedPowerSample>
    ): Double {
        if (screenOffDurationMs <= 0L) return 0.0
        if (screenOffConfidentDurationMs <= 0L) return screenOffEnergyRawMs

        val longGapDurationMs = screenOffDurationMs - screenOffConfidentDurationMs
        if (longGapDurationMs <= 0L) return screenOffConfidentEnergyRawMs

        val confidenceScore = computeScreenOffConfidenceScore(
            screenOffConfidentDurationMs = screenOffConfidentDurationMs,
            screenOffConfidentSampleCount = screenOffShortIntervalPowers.size
        )
        val baselinePercentile = interpolatePercentile(
            minPercentile = SCREEN_OFF_WH_BASELINE_MIN_PERCENTILE,
            maxPercentile = SCREEN_OFF_WH_BASELINE_MAX_PERCENTILE,
            confidenceScore = confidenceScore
        )
        val baselinePowerRaw = weightedPercentile(
            samples = screenOffShortIntervalPowers,
            percentile = baselinePercentile
        ) ?: return screenOffEnergyRawMs

        val confidentCoverage =
            screenOffConfidentDurationMs.toDouble() / screenOffDurationMs.toDouble()
        val extrapolatedLongGapEnergyRawMs =
            observedEnergyDirection(screenOffConfidentEnergyRawMs, screenOffEnergyRawMs) *
                baselinePowerRaw *
                longGapDurationMs.toDouble() *
                confidentCoverage
        return screenOffConfidentEnergyRawMs + extrapolatedLongGapEnergyRawMs
    }

    /**
     * 计算息屏 confident 样本的综合置信度。
     *
     * 设计依据：
     * 单纯依赖一条记录的高分位很容易被短样本带偏，因此这里同时要求：
     * 1. confident 息屏时长足够长；
     * 2. confident 息屏样本数量足够多。
     * 两者都足够时才允许展示值和 Wh 基线向更激进的高分位靠近。
     *
     * @param screenOffConfidentDurationMs 当前记录中落在 confident 阈值内的息屏时长。
     * @param screenOffConfidentSampleCount 当前记录中落在 confident 阈值内的息屏样本数量。
     * @return 返回 `0.0~1.0` 的置信度分数；越接近 `1.0` 代表越可以信任高分位。
     */
    private fun computeScreenOffConfidenceScore(
        screenOffConfidentDurationMs: Long,
        screenOffConfidentSampleCount: Int
    ): Double {
        if (screenOffConfidentDurationMs <= 0L || screenOffConfidentSampleCount <= 0) return 0.0
        val durationScore = (
            screenOffConfidentDurationMs.toDouble() /
                SCREEN_OFF_HIGH_CONFIDENCE_DURATION_MS.toDouble()
            ).coerceIn(0.0, 1.0)
        val sampleScore = (
            screenOffConfidentSampleCount.toDouble() /
                SCREEN_OFF_HIGH_CONFIDENCE_SAMPLE_COUNT.toDouble()
            ).coerceIn(0.0, 1.0)
        return kotlin.math.sqrt(durationScore * sampleScore)
    }

    /**
     * 按置信度在线性区间内插值得到目标分位。
     *
     * @param minPercentile 当前口径允许的最小分位。
     * @param maxPercentile 当前口径允许的最大分位。
     * @param confidenceScore `computeScreenOffConfidenceScore()` 产生的综合置信度。
     * @return 返回插值后的目标分位，范围始终落在 `[minPercentile, maxPercentile]`。
     */
    private fun interpolatePercentile(
        minPercentile: Double,
        maxPercentile: Double,
        confidenceScore: Double
    ): Double {
        return minPercentile + (maxPercentile - minPercentile) * confidenceScore.coerceIn(0.0, 1.0)
    }

    /**
     * 推导当前记录观测到的能量方向。
     *
     * @param primaryEnergy 优先使用的观测能量。
     * @param fallbackEnergy 主观测能量为 0 时的回退能量。
     * @return 返回 `1.0`、`-1.0` 或 `0.0`。
     */
    private fun observedEnergyDirection(
        primaryEnergy: Double,
        fallbackEnergy: Double
    ): Double {
        if (primaryEnergy > 0.0) return 1.0
        if (primaryEnergy < 0.0) return -1.0
        if (fallbackEnergy > 0.0) return 1.0
        if (fallbackEnergy < 0.0) return -1.0
        return 0.0
    }

    /**
     * 按区间时长加权计算功率分位数。
     *
     * 这里使用加权分位而不是均值，目的是降低短暂唤醒峰值对息屏基线的污染。
     */
    private fun weightedPercentile(
        samples: List<WeightedPowerSample>,
        percentile: Double
    ): Double? {
        if (samples.isEmpty()) return null

        val sorted = samples.sortedBy { it.powerMagnitudeRaw }
        val totalWeight = sorted.sumOf { it.durationMs.toDouble() }
        if (totalWeight <= 0.0) return null

        val targetWeight = totalWeight * percentile.coerceIn(0.0, 1.0)
        var accumulatedWeight = 0.0
        sorted.forEach { sample ->
            accumulatedWeight += sample.durationMs.toDouble()
            if (accumulatedWeight >= targetWeight) {
                return sample.powerMagnitudeRaw
            }
        }
        return sorted.last().powerMagnitudeRaw
    }

    /**
     * 按记录类型计算当前区间的电量变化百分比。
     *
     * @param detailType 当前详情页记录类型，只接受充电和放电。
     * @param previousCapacity 区间起点电量百分比。
     * @param currentCapacity 区间终点电量百分比。
     * @return 返回当前区间在正确语义下的正向电量变化值；方向不一致时返回 0。
     */
    private fun computeCapacityDelta(
        detailType: BatteryStatus,
        previousCapacity: Int,
        currentCapacity: Int
    ): Int {
        val rawDelta = when (detailType) {
            BatteryStatus.Discharging -> previousCapacity - currentCapacity
            BatteryStatus.Charging -> currentCapacity - previousCapacity
            else -> throw IllegalArgumentException("Unsupported detail type: $detailType")
        }
        return rawDelta.coerceAtLeast(0)
    }
}
