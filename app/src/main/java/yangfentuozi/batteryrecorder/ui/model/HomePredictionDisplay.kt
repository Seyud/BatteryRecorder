package yangfentuozi.batteryrecorder.ui.model

/**
 * 首页预测卡片使用的轻量展示数据。
 *
 * 说明：
 * - 该类型只承载首页卡片渲染所需字段，不暴露评分换挡规则。
 * - 当 [confidenceLevel] 为空时，界面应展示 [insufficientReason]。
 */
data class HomePredictionDisplay(
    val confidenceLevel: PredictionConfidenceLevel? = null,
    val insufficientReason: String? = null,
    val screenOffCurrentHours: Double? = null,
    val screenOffFullHours: Double? = null,
    val screenOnDailyCurrentHours: Double? = null,
    val screenOnDailyFullHours: Double? = null,
    val score: Double? = null
)
