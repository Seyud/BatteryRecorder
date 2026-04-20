package yangfentuozi.batteryrecorder.ui.guide

import android.content.SharedPreferences
import androidx.core.content.edit
import yangfentuozi.batteryrecorder.shared.data.BatteryStatus
import kotlin.math.abs

internal const val STARTUP_PROMPT_PREFS = "startup_prompt"
internal const val KEY_STARTUP_GUIDE_COMPLETED = "startup_guide_completed"

internal const val KEY_STARTUP_POWER_CALIBRATION_DETECTED = "startup_power_calibration_detected"
private const val DISCHARGE_POWER_THRESHOLD = 40_000_000_000L
private const val APPLY_STABLE_COUNT = 4
private const val COMPLETE_STABLE_COUNT = 5

internal enum class StartupPowerCalibrationCompletionState {
    Pending,
    Detected,
    Skipped
}

internal enum class StartupPowerCalibrationPhase {
    WaitingForService,
    RemoveCharger,
    WaitingForDischarge,
    Detecting,
    Completed
}

internal data class StartupPowerCalibrationUiState(
    val lastStatus: BatteryStatus = BatteryStatus.Unknown,
    val candidate: Int? = null,
    val stableCount: Int = 0,
    val completionState: StartupPowerCalibrationCompletionState =
        StartupPowerCalibrationCompletionState.Pending
) {
    val isCompleted: Boolean
        get() = completionState != StartupPowerCalibrationCompletionState.Pending
}

internal data class StartupPowerCalibrationSampleResult(
    val state: StartupPowerCalibrationUiState,
    val calibrationToApply: Int? = null,
    val completedNow: Boolean = false
)

/**
 * 解析当前自动探测 UI 应展示的阶段。
 *
 * @param serviceConnected 当前 Binder 是否已经连接。
 * @return 返回引导第三页的自动探测阶段。
 */
internal fun StartupPowerCalibrationUiState.resolvePhase(
    serviceConnected: Boolean
): StartupPowerCalibrationPhase {
    return when {
        isCompleted -> StartupPowerCalibrationPhase.Completed
        !serviceConnected -> StartupPowerCalibrationPhase.WaitingForService
        lastStatus == BatteryStatus.Charging || lastStatus == BatteryStatus.Full ->
            StartupPowerCalibrationPhase.RemoveCharger

        lastStatus == BatteryStatus.Discharging -> StartupPowerCalibrationPhase.Detecting
        else -> StartupPowerCalibrationPhase.WaitingForDischarge
    }
}

/**
 * 首次引导第三页使用的功率倍率自动探测器。
 *
 * 它只负责根据放电态 `power` 样本推断 `calibrationValue`，并在稳定后落库完成标记。
 *
 * @param prefs 首次引导共用的 SharedPreferences。
 */
internal class StartupGuidePowerCalibrationDetector(
    private val prefs: SharedPreferences
) {
    private var state = StartupPowerCalibrationUiState(
        completionState = if (prefs.getBoolean(KEY_STARTUP_POWER_CALIBRATION_DETECTED, false)) {
            StartupPowerCalibrationCompletionState.Detected
        } else {
            StartupPowerCalibrationCompletionState.Pending
        }
    )

    /**
     * 读取当前探测状态快照。
     *
     * @return 返回当前内存态对应的 UI 状态。
     */
    fun snapshot(): StartupPowerCalibrationUiState = state

    /**
     * 仅根据外部观测到的电池状态刷新 UI 状态。
     *
     * 这个入口只负责把 `ACTION_BATTERY_CHANGED` 之类的状态同步到引导页。
     * 当设备离开放电态时，同步清空本轮内存中的候选倍率与连续计数，避免旧进度穿透到下一轮探测。
     *
     * @param status 当前观测到的电池状态。
     * @return 返回更新后的 UI 状态。
     */
    fun observeStatus(status: BatteryStatus): StartupPowerCalibrationUiState {
        if (state.lastStatus == status) return state
        state = if (!state.isCompleted && status != BatteryStatus.Discharging) {
            state.copy(
                lastStatus = status,
                candidate = null,
                stableCount = 0
            )
        } else {
            state.copy(lastStatus = status)
        }
        return state
    }

    /**
     * 强制跳过自动探测，直接解锁后续引导与手动校准。
     *
     * @return 返回跳过后的 UI 状态。
     */
    fun skipDetection(): StartupPowerCalibrationUiState {
        if (state.completionState == StartupPowerCalibrationCompletionState.Skipped) {
            return state
        }
        state = state.copy(
            candidate = null,
            stableCount = 0,
            completionState = StartupPowerCalibrationCompletionState.Skipped
        )
        return state
    }

    /**
     * 消费一条实时功率样本并推进探测状态。
     *
     * 只有放电态样本才参与推断；其余状态会清空当前连续计数，但不会回滚完成标记。
     *
     * @param status 当前电池状态。
     * @param power 当前回调里的原始功率值。
     * @param currentCalibrationValue 当前已保存的校准倍率。
     * @return 返回本次样本处理后的完整结果。
     */
    fun onSample(
        status: BatteryStatus,
        power: Long,
        currentCalibrationValue: Int
    ): StartupPowerCalibrationSampleResult {
        if (state.isCompleted) {
            state = state.copy(lastStatus = status)
            return StartupPowerCalibrationSampleResult(state = state)
        }

        if (status != BatteryStatus.Discharging || power == 0L) {
            state = state.copy(
                lastStatus = status,
                candidate = null,
                stableCount = 0
            )
            return StartupPowerCalibrationSampleResult(state = state)
        }

        val candidate = inferCalibrationValue(power)
        val nextStableCount = if (state.candidate == candidate) {
            state.stableCount + 1
        } else {
            1
        }
        val completedNow = nextStableCount == COMPLETE_STABLE_COUNT
        if (completedNow) {
            prefs.edit {
                putBoolean(KEY_STARTUP_POWER_CALIBRATION_DETECTED, true)
            }
        }
        state = StartupPowerCalibrationUiState(
            lastStatus = status,
            candidate = candidate,
            stableCount = nextStableCount,
            completionState = if (completedNow) {
                StartupPowerCalibrationCompletionState.Detected
            } else {
                StartupPowerCalibrationCompletionState.Pending
            }
        )
        val calibrationToApply = candidate.takeIf {
            nextStableCount == APPLY_STABLE_COUNT && currentCalibrationValue != it
        }
        return StartupPowerCalibrationSampleResult(
            state = state,
            calibrationToApply = calibrationToApply,
            completedNow = completedNow
        )
    }

    /**
     * 根据原始功率值推断校准倍率。
     *
     * 约定：
     * 1. 放电态原始功率为正时，展示倍率应为负。
     * 2. 小于放电阈值时按 `1000` 级联放大倍率，直到达到目标量级。
     *
     * @param power 当前放电样本的原始功率值。
     * @return 返回推断出的校准倍率。
     */
    private fun inferCalibrationValue(power: Long): Int {
        var candidate = if (power > 0L) -1 else 1
        var absPower = abs(power)
        while (absPower in 1 until DISCHARGE_POWER_THRESHOLD) {
            absPower *= 1000L
            candidate *= 1000
        }
        return candidate
    }
}
