package yangfentuozi.batteryrecorder.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.rememberNavController
import yangfentuozi.batteryrecorder.BuildConfig
import yangfentuozi.batteryrecorder.R
import yangfentuozi.batteryrecorder.shared.util.LoggerX
import yangfentuozi.batteryrecorder.ui.dialog.home.DocsIntroDialog
import yangfentuozi.batteryrecorder.ui.guide.KEY_STARTUP_GUIDE_COMPLETED
import yangfentuozi.batteryrecorder.ui.guide.STARTUP_PROMPT_PREFS
import yangfentuozi.batteryrecorder.ui.dialog.home.UpdateDialog
import yangfentuozi.batteryrecorder.ui.guide.StartupGuideScreen
import yangfentuozi.batteryrecorder.ui.navigation.BatteryRecorderNavHost
import yangfentuozi.batteryrecorder.ui.viewmodel.MainViewModel
import yangfentuozi.batteryrecorder.ui.viewmodel.SettingsViewModel
import yangfentuozi.batteryrecorder.ui.model.displayName
import yangfentuozi.batteryrecorder.utils.AppUpdate
import yangfentuozi.batteryrecorder.utils.UpdateUtils

private const val TAG = "BatteryRecorderApp"
private const val KEY_DOCS_INTRO_SHOWN = "docs_intro_shown"

@Composable
fun BatteryRecorderApp(
    mainViewModel: MainViewModel = viewModel(),
    settingsViewModel: SettingsViewModel = viewModel()
) {
    val context = LocalContext.current
    val appSettings by settingsViewModel.appSettings.collectAsState()
    val initialized by settingsViewModel.initialized.collectAsState()
    val startupPrefs = remember(context.applicationContext) {
        context.applicationContext.getSharedPreferences(STARTUP_PROMPT_PREFS, Context.MODE_PRIVATE)
    }
    var hasCheckedUpdateOnStartup by rememberSaveable { mutableStateOf(false) }
    var pendingUpdate by remember { mutableStateOf<AppUpdate?>(null) }
    var showStartupGuide by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var showDocsIntro by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(settingsViewModel, context) {
        settingsViewModel.init(context)
        val startupGuideCompleted = startupPrefs.getBoolean(KEY_STARTUP_GUIDE_COMPLETED, false)
        val docsIntroShown = startupPrefs.getBoolean(KEY_DOCS_INTRO_SHOWN, false)
        showStartupGuide = !startupGuideCompleted
        showDocsIntro = !docsIntroShown
        LoggerX.v(TAG, "首次启动引导状态: completed=$startupGuideCompleted")
        LoggerX.v(TAG, "文档引导弹窗状态: shown=$docsIntroShown")
    }

    LaunchedEffect(
        initialized,
        appSettings.checkUpdateOnStartup,
        appSettings.updateChannel,
        context
    ) {
        if (!initialized || hasCheckedUpdateOnStartup) return@LaunchedEffect
        hasCheckedUpdateOnStartup = true
        if (!appSettings.checkUpdateOnStartup) {
            LoggerX.d(TAG, "[更新] 启动更新检测已关闭，跳过检查")
            return@LaunchedEffect
        }
        LoggerX.d(TAG, "[更新] 启动更新检测开始，channel=${appSettings.updateChannel.displayName}")

        val update = UpdateUtils.fetchUpdate(appSettings.updateChannel).getOrElse {
            LoggerX.w(TAG, "[更新] 启动更新检测失败，未获取到可用更新信息")
            Toast.makeText(context, context.getString(R.string.update_check_failed), Toast.LENGTH_SHORT).show()
            return@LaunchedEffect
        }
        if (update == null) {
            LoggerX.i(TAG, "[更新] 启动更新检测完成，当前通道无可用 release，channel=${appSettings.updateChannel.displayName}")
            return@LaunchedEffect
        }
        if (BuildConfig.VERSION_CODE >= update.versionCode) {
            LoggerX.i(
                TAG,
                "[更新] 启动更新检测完成，无需更新，channel=${update.updateChannel.displayName} remote=${update.versionCode} local=${BuildConfig.VERSION_CODE}"
            )
            return@LaunchedEffect
        }

        LoggerX.i(
            TAG,
            "[更新] 启动更新检测完成，发现新版本，channel=${update.updateChannel.displayName} remote=${update.versionCode} local=${BuildConfig.VERSION_CODE}"
        )
        pendingUpdate = update
    }

    val shouldShowStartupGuide = showStartupGuide
    if (shouldShowStartupGuide == null) return

    if (shouldShowStartupGuide) {
        StartupGuideScreen(
            settingsViewModel = settingsViewModel,
            onGuideCompleted = {
                startupPrefs.edit {
                    putBoolean(KEY_STARTUP_GUIDE_COMPLETED, true)
                }
                LoggerX.i(TAG, "[引导] 首次启动引导已完成")
                showStartupGuide = false
            }
        )
    } else {
        val navController = rememberNavController()
        BatteryRecorderNavHost(
            navController = navController,
            mainViewModel = mainViewModel,
            settingsViewModel = settingsViewModel
        )
    }

    pendingUpdate?.let { update ->
        if (shouldShowStartupGuide == true || showDocsIntro) return@let
        UpdateDialog(
            update = update,
            onDismiss = { pendingUpdate = null }
        )
    }

    if (shouldShowStartupGuide != true && showDocsIntro) {
        DocsIntroDialog(
            onOpenDocs = {
                startupPrefs.edit {
                    putBoolean(KEY_DOCS_INTRO_SHOWN, true)
                }
                LoggerX.i(TAG, "[引导] 文档引导弹窗已完成")
                showDocsIntro = false
            }
        )
    }
}
