package yangfentuozi.batteryrecorder.ui.viewmodel

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import yangfentuozi.batteryrecorder.data.history.BatteryPredictor
import yangfentuozi.batteryrecorder.data.history.HistoryRecord
import yangfentuozi.batteryrecorder.data.history.HistoryRepository
import yangfentuozi.batteryrecorder.data.history.HistorySummary
import yangfentuozi.batteryrecorder.data.history.PredictionResult
import yangfentuozi.batteryrecorder.data.history.SceneStats
import yangfentuozi.batteryrecorder.data.history.SceneStatsComputer
import yangfentuozi.batteryrecorder.data.history.StatisticsRequest
import yangfentuozi.batteryrecorder.data.history.SyncUtil
import yangfentuozi.batteryrecorder.data.log.LogRepository
import yangfentuozi.batteryrecorder.ipc.Service
import yangfentuozi.batteryrecorder.shared.data.BatteryStatus
import yangfentuozi.batteryrecorder.shared.util.LoggerX

class MainViewModel : ViewModel() {
    private val _serviceConnected = MutableStateFlow(false)
    val serviceConnected: StateFlow<Boolean> = _serviceConnected.asStateFlow()

    private val _showStopDialog = MutableStateFlow(false)
    val showStopDialog: StateFlow<Boolean> = _showStopDialog.asStateFlow()

    private val _showAboutDialog = MutableStateFlow(false)
    val showAboutDialog: StateFlow<Boolean> = _showAboutDialog.asStateFlow()

    private val _userMessage = MutableStateFlow<String?>(null)
    val userMessage: StateFlow<String?> = _userMessage.asStateFlow()

    private val _chargeSummary = MutableStateFlow<HistorySummary?>(null)
    val chargeSummary: StateFlow<HistorySummary?> = _chargeSummary.asStateFlow()

    private val _dischargeSummary = MutableStateFlow<HistorySummary?>(null)
    val dischargeSummary: StateFlow<HistorySummary?> = _dischargeSummary.asStateFlow()

    private val _currentRecord = MutableStateFlow<HistoryRecord?>(null)
    val currentRecord: StateFlow<HistoryRecord?> = _currentRecord.asStateFlow()

    private val _isLoadingStats = MutableStateFlow(false)
    val isLoadingStats: StateFlow<Boolean> = _isLoadingStats.asStateFlow()

    private val _sceneStats = MutableStateFlow<SceneStats?>(null)
    val sceneStats: StateFlow<SceneStats?> = _sceneStats.asStateFlow()

    private val _prediction = MutableStateFlow<PredictionResult?>(null)
    val prediction: StateFlow<PredictionResult?> = _prediction.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())

    private var statisticsJob: Job? = null
    private var statisticsGeneration: Long = 0L

    private val serviceListener = object : Service.ServiceConnection {
        override fun onServiceConnected() {
            LoggerX.i<MainViewModel>("[首页] 服务已连接")
            mainHandler.post {
                _serviceConnected.value = true
            }
        }

        override fun onServiceDisconnected() {
            LoggerX.w<MainViewModel>("[首页] 服务已断开")
            mainHandler.post {
                _serviceConnected.value = false
            }
        }
    }

    init {
        Service.addListener(serviceListener)
        _serviceConnected.value = Service.service != null
        LoggerX.d<MainViewModel>("[首页] MainViewModel 初始化: serviceConnected=${_serviceConnected.value}")
    }

    override fun onCleared() {
        Service.removeListener(serviceListener)
        mainHandler.removeCallbacksAndMessages(null)
    }

    fun showStopDialog() {
        _showStopDialog.value = true
    }

    fun dismissStopDialog() {
        _showStopDialog.value = false
    }

    fun stopService() {
        if (Service.service == null) {
            LoggerX.w<MainViewModel>("[首页] 用户请求停止服务，但服务未连接")
        } else {
            LoggerX.i<MainViewModel>("[首页] 用户请求停止服务")
        }
        Thread {
            Service.service?.stopService()
        }.start()
    }

    fun showAboutDialog() {
        _showAboutDialog.value = true
    }

    fun dismissAboutDialog() {
        _showAboutDialog.value = false
    }

    /**
     * 导出首页日志 ZIP。
     *
     * @param context 应用上下文。
     * @param destinationUri SAF 目标 URI。
     * @return 无返回值。
     */
    fun exportLogs(context: Context, destinationUri: Uri) {
        viewModelScope.launch {
            try {
                LoggerX.i<MainViewModel>("[导出] 开始导出首页日志")
                withContext(Dispatchers.IO) {
                    LogRepository.exportLogsZip(
                        context = context,
                        destinationUri = destinationUri
                    )
                }
                LoggerX.i<MainViewModel>("[导出] 首页日志导出成功")
                _userMessage.value = "导出成功"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LoggerX.e<MainViewModel>("[导出] 日志导出失败", tr = e)
                _userMessage.value = "导出失败"
            }
        }
    }

    fun consumeUserMessage() {
        _userMessage.value = null
    }

    fun loadStatistics(
        context: Context,
        request: StatisticsRequest = StatisticsRequest()
    ) {
        if (_isLoadingStats.value) {
            LoggerX.v<MainViewModel>("[首页] loadStatistics 已在进行，跳过重复请求")
            return
        }

        startLoadStatistics(
            context = context,
            request = request
        )
    }

    fun refreshStatistics(
        context: Context,
        request: StatisticsRequest = StatisticsRequest()
    ) {
        if (_isLoadingStats.value) {
            LoggerX.v<MainViewModel>("[首页] refreshStatistics 已在进行，跳过")
            return
        }
        _chargeSummary.value = null
        _dischargeSummary.value = null
        _currentRecord.value = null
        _sceneStats.value = null
        _prediction.value = null
        loadStatistics(
            context = context,
            request = request
        )
    }

    fun forceRefreshStatistics(
        context: Context,
        request: StatisticsRequest = StatisticsRequest()
    ) {
        statisticsJob?.cancel()
        _chargeSummary.value = null
        _dischargeSummary.value = null
        _currentRecord.value = null
        _sceneStats.value = null
        _prediction.value = null
        startLoadStatistics(
            context = context,
            request = request
        )
    }

    fun refreshCurrentRecord(context: Context) {
        viewModelScope.launch {
            val dischargeDisplayPositive = getDischargeDisplayPositive(context)
            _currentRecord.value = loadLatestRecordForDisplay(context, dischargeDisplayPositive)
        }
    }

    suspend fun onLiveStatusChanged(
        context: Context,
        liveStatus: BatteryStatus?,
        intervalMs: Long
    ) {
        if (liveStatus == null) return

        val dischargeDisplayPositive = getDischargeDisplayPositive(context)
        delay((intervalMs * 2).coerceAtLeast(800L))

        repeat(3) {
            withContext(Dispatchers.IO) {
                runCatching { SyncUtil.sync(context) }
            }
            val loaded = loadLatestRecordForDisplay(context, dischargeDisplayPositive)
            if (loaded != null && loaded.type == liveStatus) {
                _currentRecord.value = loaded
                return
            }
            delay(350L)
        }
    }

    private suspend fun loadLatestRecordForDisplay(
        context: Context,
        dischargeDisplayPositive: Boolean
    ): HistoryRecord? {
        return withContext(Dispatchers.IO) {
            HistoryRepository.loadLatestRecord(context)
        }?.let { mapHistoryRecordForDisplay(it, dischargeDisplayPositive) }
    }

    private fun startLoadStatistics(
        context: Context,
        request: StatisticsRequest
    ) {
        val generation = (++statisticsGeneration)
        LoggerX.i<MainViewModel>(
            "[首页] 开始加载统计: generation=$generation recentFileCount=${request.sceneStatsRecentFileCount} intervalMs=${request.recordIntervalMs}"
        )
        _isLoadingStats.value = true
        val job = viewModelScope.launch {
            try {
                val dischargeDisplayPositive = getDischargeDisplayPositive(context)

                withContext(Dispatchers.IO) {
                    LoggerX.d<MainViewModel>("[首页] 统计前触发同步")
                    runCatching { SyncUtil.sync(context) }
                }

                val chargeSummary = withContext(Dispatchers.IO) {
                    HistoryRepository.loadSummary(context, BatteryStatus.Charging)
                }
                val dischargeSummary = withContext(Dispatchers.IO) {
                    HistoryRepository.loadSummary(context, BatteryStatus.Discharging)
                }
                val currentRecord = loadLatestRecordForDisplay(context, dischargeDisplayPositive)

                if (generation == statisticsGeneration) {
                    LoggerX.d<MainViewModel>(
                        "[首页] 基础统计加载完成: generation=$generation chargeSummary=${chargeSummary != null} " +
                            "dischargeSummary=${dischargeSummary != null} currentRecord=${currentRecord != null}"
                    )
                    _chargeSummary.value =
                        chargeSummary?.let {
                            mapHistorySummaryForDisplay(
                                it,
                                dischargeDisplayPositive
                            )
                        }
                    _dischargeSummary.value =
                        dischargeSummary?.let {
                            mapHistorySummaryForDisplay(
                                it,
                                dischargeDisplayPositive
                            )
                        }
                    _currentRecord.value = currentRecord
                }

                val stats = withContext(Dispatchers.IO) {
                    val currentDischargeFileName = currentRecord
                        ?.takeIf { it.type == BatteryStatus.Discharging }
                        ?.name
                    SceneStatsComputer.compute(
                        context = context,
                        request = request,
                        currentDischargeFileName = currentDischargeFileName,
                    )
                }

                if (generation == statisticsGeneration) {
                    _sceneStats.value = stats?.displayStats
                    val soc = currentRecord?.stats?.endCapacity ?: 0
                    _prediction.value =
                        BatteryPredictor.predict(
                            stats?.predictionStats, soc, stats?.medianK,
                            kCV = stats?.kCV,
                            kEffectiveN = stats?.kEffectiveN ?: 0.0,
                            upstreamInsufficientReason = stats?.insufficientReason
                        )
                    LoggerX.i<MainViewModel>(
                        "[首页] 场景统计与预测完成: generation=$generation sceneStats=${stats?.displayStats != null} prediction=${_prediction.value != null}"
                    )
                }
            } finally {
                if (generation == statisticsGeneration) {
                    LoggerX.d<MainViewModel>("[首页] 统计任务结束: generation=$generation")
                    _isLoadingStats.value = false
                }
            }
        }
        statisticsJob = job
    }
}
