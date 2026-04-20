package yangfentuozi.batteryrecorder.ui.guide

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import yangfentuozi.batteryrecorder.R
import yangfentuozi.batteryrecorder.ipc.Service
import yangfentuozi.batteryrecorder.server.recorder.IRecordListener
import yangfentuozi.batteryrecorder.shared.config.SettingsConstants
import yangfentuozi.batteryrecorder.shared.data.BatteryStatus
import yangfentuozi.batteryrecorder.shared.data.RecordsFile
import yangfentuozi.batteryrecorder.shared.util.LoggerX
import yangfentuozi.batteryrecorder.startup.RootServerStarter
import yangfentuozi.batteryrecorder.ui.dialog.settings.CalibrationDialog
import yangfentuozi.batteryrecorder.ui.theme.AppShape
import yangfentuozi.batteryrecorder.ui.viewmodel.SettingsViewModel
import yangfentuozi.batteryrecorder.utils.batteryRecorderScaffoldInsets

private const val TAG = "StartupGuideScreen"

private data class AdbCommandItem(
    val title: String,
    val command: String
)

/**
 * 首次启动引导页。
 *
 * @param settingsViewModel 用于读写双电芯与电流校准设置。
 * @param onGuideCompleted 引导完成后的回调。
 * @param modifier 外层修饰符。
 * @return 无，直接渲染引导界面。
 */
@Composable
fun StartupGuideScreen(
    settingsViewModel: SettingsViewModel,
    onGuideCompleted: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val dualCellEnabled by settingsViewModel.dualCellEnabled.collectAsState()
    val calibrationValue by settingsViewModel.calibrationValue.collectAsState()
    val latestCalibrationValue by rememberUpdatedState(calibrationValue)
    var currentStep by rememberSaveable { mutableStateOf(StartupGuideStep.INTRO) }
    var serviceConnected by rememberSaveable { mutableStateOf(Service.service != null) }
    var showCalibrationDialog by rememberSaveable { mutableStateOf(false) }
    val startupPrefs = remember(context.applicationContext) {
        context.applicationContext.getSharedPreferences(STARTUP_PROMPT_PREFS, Context.MODE_PRIVATE)
    }
    val calibrationDetector = remember(startupPrefs) {
        StartupGuidePowerCalibrationDetector(startupPrefs)
    }
    var calibrationDetectionState by remember {
        mutableStateOf(calibrationDetector.snapshot())
    }

    // 把外部状态源拆成具名副作用块，主流程只负责拼装引导状态与页面切换。
    ObserveStartupGuideBatteryStatus(appContext = appContext) { status ->
        calibrationDetectionState = calibrationDetector.observeStatus(status)
    }

    val recordListener = remember(calibrationDetector, settingsViewModel, scope) {
        object : IRecordListener.Stub() {
            override fun onRecord(
                timestamp: Long,
                power: Long,
                status: BatteryStatus,
                temp: Int
            ) {
                // IRecordListener 回调来自 Binder 线程，这里统一切回主线程再更新 Compose state 和设置项。
                scope.launch(Dispatchers.Main.immediate) {
                    val result = calibrationDetector.onSample(
                        status = status,
                        power = power,
                        currentCalibrationValue = latestCalibrationValue
                    )
                    calibrationDetectionState = result.state
                    result.calibrationToApply?.let { detectedValue ->
                        LoggerX.i(TAG, "[引导] 自动应用校准倍率: calibration=$detectedValue")
                        settingsViewModel.setCalibrationValue(detectedValue)
                    }
                    if (result.completedNow) {
                        LoggerX.i(TAG, "[引导] 电流校准自动探测完成")
                    }
                }
            }

            override fun onChangedCurrRecordsFile(recordsFile: RecordsFile) = Unit
        }
    }

    ObserveStartupGuideServiceConnection { connected ->
        if (connected) {
            LoggerX.i(TAG, "[引导] Binder 已连接")
        } else {
            LoggerX.w(TAG, "[引导] Binder 已断开")
        }
        serviceConnected = connected
    }

    val shouldObserveCalibrationSamples = currentStep == StartupGuideStep.CALIBRATION
    ObserveStartupGuideCalibrationSamples(
        lifecycleOwner = lifecycleOwner,
        enabled = shouldObserveCalibrationSamples,
        serviceConnected = serviceConnected,
        recordListener = recordListener
    )

    val nextEnabled = when (currentStep) {
        StartupGuideStep.INTRO -> true
        StartupGuideStep.START_SERVICE -> serviceConnected
        StartupGuideStep.CALIBRATION -> calibrationDetectionState.isCompleted
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = batteryRecorderScaffoldInsets(),
        bottomBar = {
            StartupGuideBottomBar(
                currentStep = currentStep,
                nextEnabled = nextEnabled,
                onBack = {
                    currentStep = when (currentStep) {
                        StartupGuideStep.START_SERVICE -> StartupGuideStep.INTRO
                        StartupGuideStep.CALIBRATION -> StartupGuideStep.START_SERVICE
                        StartupGuideStep.INTRO -> StartupGuideStep.INTRO
                    }
                },
                onNext = {
                    when (currentStep) {
                        StartupGuideStep.INTRO -> currentStep = StartupGuideStep.START_SERVICE
                        StartupGuideStep.START_SERVICE -> {
                            if (serviceConnected) {
                                currentStep = StartupGuideStep.CALIBRATION
                            }
                        }

                        StartupGuideStep.CALIBRATION -> onGuideCompleted()
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
        ) {
            AnimatedContent(
                targetState = currentStep,
                transitionSpec = {
                    fadeIn(animationSpec = tween(240)) togetherWith
                        fadeOut(animationSpec = tween(240))
                },
                label = "startup_guide_step_transition"
            ) { step ->
                when (step) {
                    StartupGuideStep.INTRO -> IntroContent()

                    StartupGuideStep.START_SERVICE -> StartServiceContent(
                        serviceConnected = serviceConnected,
                        onStartRoot = {
                            LoggerX.i(TAG, "[引导] 用户点击 ROOT 启动")
                            Thread {
                                RootServerStarter.start(
                                    context = context,
                                    source = "首次引导"
                                )
                            }.start()
                        }
                    )

                    StartupGuideStep.CALIBRATION -> CalibrationContent(
                        dualCellEnabled = dualCellEnabled,
                        calibrationValue = calibrationValue,
                        serviceConnected = serviceConnected,
                        calibrationDetectionState = calibrationDetectionState,
                        onDualCellChange = settingsViewModel::setDualCellEnabled,
                        onAdjustCalibration = { showCalibrationDialog = true },
                        onSkipDetection = {
                            calibrationDetectionState = calibrationDetector.skipDetection()
                        }
                    )
                }
            }
        }
    }

    if (showCalibrationDialog) {
        CalibrationDialog(
            currentValue = calibrationValue,
            dualCellEnabled = dualCellEnabled,
            serviceConnected = serviceConnected,
            onDismiss = { showCalibrationDialog = false },
            onSave = { value ->
                settingsViewModel.setCalibrationValue(value)
                showCalibrationDialog = false
            },
            onReset = {
                settingsViewModel.setCalibrationValue(SettingsConstants.calibrationValue.def)
                showCalibrationDialog = false
            }
        )
    }
}

@Composable
private fun IntroContent() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 40.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.app_name),
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = stringResource(R.string.startup_guide_intro_subtitle),
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(80.dp))
        Column(horizontalAlignment = Alignment.Start) {
            StartupGuideFeatureItem(
                icon = Icons.Default.AdminPanelSettings,
                title = stringResource(R.string.startup_guide_feature_start_title),
                description = stringResource(R.string.startup_guide_feature_start_desc)
            )
            Spacer(Modifier.height(18.dp))
            StartupGuideFeatureItem(
                icon = Icons.Default.Bolt,
                title = stringResource(R.string.startup_guide_feature_record_title),
                description = stringResource(R.string.startup_guide_feature_record_desc)
            )
            Spacer(Modifier.height(18.dp))
            StartupGuideFeatureItem(
                icon = Icons.Default.AutoGraph,
                title = stringResource(R.string.startup_guide_feature_calibration_title),
                description = stringResource(R.string.startup_guide_feature_calibration_desc)
            )
        }
    }
}

@Composable
private fun StartServiceContent(
    serviceConnected: Boolean,
    onStartRoot: () -> Unit
) {
    val context = LocalContext.current
    val shellCommand = remember(context) {
        "${context.applicationInfo.nativeLibraryDir}/libstarter.so"
    }
    val commandItems = remember(shellCommand, context) {
        listOf(
            AdbCommandItem(
                title = context.getString(R.string.adb_guide_pc_title),
                command = "adb shell \"$shellCommand\""
            ),
            AdbCommandItem(
                title = context.getString(R.string.adb_guide_shell_title),
                command = shellCommand
            )
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.startup_guide_start_title),
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(28.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = AppShape.large,
            color = if (serviceConnected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = stringResource(R.string.startup_guide_service_status_title),
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = if (serviceConnected) {
                        stringResource(R.string.startup_guide_service_status_connected)
                    } else {
                        stringResource(R.string.startup_guide_service_status_waiting)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (serviceConnected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = AppShape.large,
            tonalElevation = 2.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.home_action_start_root),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.home_start_service_title),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(16.dp))
                Button(
                    onClick = onStartRoot,
                    enabled = !serviceConnected,
                    shape = AppShape.SplicedGroup.single
                ) {
                    Text(stringResource(R.string.home_action_start_service))
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = AppShape.large,
            tonalElevation = 2.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.home_action_start_adb),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = stringResource(R.string.adb_guide_step_enable_debug),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = stringResource(R.string.adb_guide_step_run_command),
                    style = MaterialTheme.typography.bodyMedium
                )
                commandItems.forEach { item ->
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.bodySmall
                    )
                    StartupGuideCommandBox(
                        command = item.command,
                        onCopy = { copyCommand(context, item.command) }
                    )
                }
            }
        }
    }
}

@Composable
private fun CalibrationContent(
    dualCellEnabled: Boolean,
    calibrationValue: Int,
    serviceConnected: Boolean,
    calibrationDetectionState: StartupPowerCalibrationUiState,
    onDualCellChange: (Boolean) -> Unit,
    onAdjustCalibration: () -> Unit,
    onSkipDetection: () -> Unit
) {
    val phase = calibrationDetectionState.resolvePhase(serviceConnected)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.startup_guide_calibration_title),
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = stringResource(R.string.startup_guide_calibration_subtitle),
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(28.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = AppShape.large,
            tonalElevation = 2.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.startup_guide_detection_title),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = when (phase) {
                        StartupPowerCalibrationPhase.WaitingForService ->
                            stringResource(R.string.startup_guide_detection_wait_service)

                        StartupPowerCalibrationPhase.RemoveCharger ->
                            stringResource(R.string.startup_guide_detection_remove_charger)

                        StartupPowerCalibrationPhase.WaitingForDischarge ->
                            stringResource(R.string.startup_guide_detection_wait_discharge)

                        StartupPowerCalibrationPhase.Detecting ->
                            stringResource(R.string.startup_guide_detection_running)

                        StartupPowerCalibrationPhase.Completed -> when (
                            calibrationDetectionState.completionState
                        ) {
                            StartupPowerCalibrationCompletionState.Skipped ->
                                stringResource(R.string.startup_guide_detection_skipped)

                            else -> stringResource(R.string.startup_guide_detection_completed)
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (phase == StartupPowerCalibrationPhase.Detecting) {
                    Text(
                        text = calibrationDetectionState.candidate?.let {
                            stringResource(
                                R.string.startup_guide_detection_candidate_value,
                                it
                            )
                        } ?: stringResource(R.string.startup_guide_detection_candidate_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(
                            R.string.startup_guide_detection_stable_count,
                            calibrationDetectionState.stableCount
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (
                    phase == StartupPowerCalibrationPhase.Completed &&
                    calibrationDetectionState.completionState ==
                    StartupPowerCalibrationCompletionState.Detected
                ) {
                    Text(
                        text = stringResource(
                            R.string.startup_guide_detection_result_value,
                            calibrationValue
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = AppShape.large,
            tonalElevation = 2.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_dual_cell),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.startup_guide_dual_cell_summary),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(16.dp))
                Switch(
                    checked = dualCellEnabled,
                    onCheckedChange = onDualCellChange
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = AppShape.large,
            tonalElevation = 2.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.settings_calibration_title),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = stringResource(
                        R.string.startup_guide_calibration_current_value,
                        calibrationValue
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = when (calibrationDetectionState.completionState) {
                        StartupPowerCalibrationCompletionState.Detected ->
                            stringResource(R.string.startup_guide_calibration_completed_hint)

                        StartupPowerCalibrationCompletionState.Skipped ->
                            stringResource(R.string.startup_guide_calibration_skipped_hint)

                        StartupPowerCalibrationCompletionState.Pending ->
                            stringResource(R.string.startup_guide_calibration_locked_hint)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = onAdjustCalibration,
                        enabled = calibrationDetectionState.isCompleted,
                        modifier = Modifier.weight(1f),
                        shape = AppShape.large
                    ) {
                        Text(stringResource(R.string.startup_guide_adjust_calibration))
                    }
                    OutlinedButton(
                        onClick = onSkipDetection,
                        enabled = !calibrationDetectionState.isCompleted,
                        modifier = Modifier.weight(1f),
                        shape = AppShape.large,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        border = BorderStroke(
                            width = 1.dp,
                            color = if (calibrationDetectionState.isCompleted) {
                                MaterialTheme.colorScheme.outlineVariant
                            } else {
                                MaterialTheme.colorScheme.error
                            }
                        )
                    ) {
                        Text(stringResource(R.string.startup_guide_force_skip_detection))
                    }
                }
            }
        }
    }
}

@Composable
private fun StartupGuideCommandBox(
    command: String,
    onCopy: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = command,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState())
            )
            IconButton(
                onClick = onCopy,
                modifier = Modifier
                    .height(MaterialTheme.typography.bodySmall.lineHeight.value.dp)
                    .width(MaterialTheme.typography.bodySmall.lineHeight.value.dp + 7.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.ContentCopy,
                    contentDescription = stringResource(R.string.adb_guide_copy_command)
                )
            }
        }
    }
}

private fun copyCommand(context: Context, command: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("command", command))
    Toast.makeText(context, context.getString(R.string.adb_guide_copied), Toast.LENGTH_SHORT).show()
}

@Composable
private fun ObserveStartupGuideBatteryStatus(
    appContext: Context,
    onStatusObserved: (BatteryStatus) -> Unit
) {
    DisposableEffect(appContext) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val rawStatus = intent?.getIntExtra(
                    BatteryManager.EXTRA_STATUS,
                    BatteryManager.BATTERY_STATUS_UNKNOWN
                ) ?: BatteryManager.BATTERY_STATUS_UNKNOWN
                onStatusObserved(BatteryStatus.fromValue(rawStatus))
            }
        }
        // ACTION_BATTERY_CHANGED 是粘性广播，注册返回值就是当前快照，避免首次进入时先显示一拍旧状态。
        val initialIntent = ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiver.onReceive(appContext, initialIntent)
        onDispose {
            appContext.unregisterReceiver(receiver)
        }
    }
}

@Composable
private fun ObserveStartupGuideServiceConnection(
    onConnectionChanged: (Boolean) -> Unit
) {
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) {
        // Service 的监听回调可能来自 Binder/DeathRecipient 线程，线程切换收口在这里，避免主流程再包一层 launch。
        val listener = object : Service.ServiceConnection {
            override fun onServiceConnected() {
                scope.launch(Dispatchers.Main.immediate) {
                    onConnectionChanged(true)
                }
            }

            override fun onServiceDisconnected() {
                scope.launch(Dispatchers.Main.immediate) {
                    onConnectionChanged(false)
                }
            }
        }
        Service.addListener(listener)
        onDispose {
            Service.removeListener(listener)
        }
    }
}

@Composable
private fun ObserveStartupGuideCalibrationSamples(
    lifecycleOwner: LifecycleOwner,
    enabled: Boolean,
    serviceConnected: Boolean,
    recordListener: IRecordListener
) {
    DisposableEffect(lifecycleOwner, enabled, serviceConnected, recordListener) {
        // 只在校准步骤且页面处于前台时订阅采样，避免引导切页后继续持有监听器。
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    if (enabled && serviceConnected) {
                        Service.service?.registerRecordListener(recordListener)
                    }
                }

                Lifecycle.Event.ON_STOP -> Service.service?.unregisterRecordListener(recordListener)
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (
            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
            enabled &&
            serviceConnected
        ) {
            // DisposableEffect 可能创建于页面已经 STARTED 之后，这里补一次即时注册，避免错过当前会话采样。
            Service.service?.registerRecordListener(recordListener)
        }
        onDispose {
            Service.service?.unregisterRecordListener(recordListener)
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
}
