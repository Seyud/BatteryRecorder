package yangfentuozi.batteryrecorder.server.notification

import android.app.INotificationManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.RemoteException
import android.os.ServiceManager
import yangfentuozi.batteryrecorder.server.fakecontext.FakeContext
import yangfentuozi.batteryrecorder.shared.Constants
import yangfentuozi.batteryrecorder.shared.config.SettingsConstants
import yangfentuozi.batteryrecorder.shared.config.dataclass.ServerSettings
import yangfentuozi.batteryrecorder.shared.util.LoggerX
import yangfentuozi.hiddenapi.compat.NotificationManagerCompat
import java.util.Locale

private const val TAG = "LocalNotificationUtil"

class LocalNotificationUtil: NotificationUtil {

    private val lock = Any()
    private val notificationManager: INotificationManager =
        INotificationManager.Stub.asInterface(ServiceManager.getService("notification"))
            ?: throw IllegalStateException("notification 服务未就绪")
    private val context = FakeContext()

    @Volatile
    private var compatibilityModeEnabled: Boolean = SettingsConstants.notificationCompatModeEnabled.def
    @Volatile
    private var iconCompatibilityModeEnabled: Boolean = SettingsConstants.notificationIconCompatModeEnabled.def

    init {
        synchronized(lock) {
            try {
                NotificationManagerCompat.createChannel(
                    notificationManager,
                    SHELL_PACKAGE_NAME,
                    SHELL_UID,
                    NotificationChannel(
                        CHANNEL_ID,
                        CHANNEL_NAME,
                        NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        enableLights(false)
                        enableVibration(false)
                    }
                )
                LoggerX.i(TAG, "init: 通知渠道创建成功")
            } catch (e: RemoteException) {
                LoggerX.e(TAG, "init: 通知渠道创建失败", tr = e)
            }
        }
    }

    override fun syncSettings(settings: ServerSettings) {
        synchronized(lock) {
            if (iconCompatibilityModeEnabled == settings.notificationIconCompatModeEnabled) {
                LoggerX.i(
                    TAG,
                    "onSettingsUpdate: iconCompatibilityModeEnabled $iconCompatibilityModeEnabled -> ${settings.notificationIconCompatModeEnabled}"
                )
                iconCompatibilityModeEnabled = settings.notificationIconCompatModeEnabled
            }

            if (compatibilityModeEnabled != settings.notificationCompatModeEnabled) {
                LoggerX.i(
                    TAG,
                    "onSettingsUpdate: compatibilityModeEnabled $compatibilityModeEnabled -> ${settings.notificationCompatModeEnabled}"
                )
                compatibilityModeEnabled = settings.notificationCompatModeEnabled
            }
        }
    }

    override fun updateNotification(info: NotificationInfo) {
        synchronized(lock) {
            try {
                NotificationManagerCompat.enqueueNotification(
                    notificationManager,
                    SHELL_PACKAGE_NAME,
                    NOTIFICATION_TAG,
                    NOTIFICATION_ID,
                    buildNotification(info),
                    0
                )
            } catch (e: RemoteException) {
                LoggerX.e(TAG, "updateNotification: 发送通知失败", tr = e)
            }
        }
    }

    override fun cancelNotification() {
        synchronized(lock) {
            NotificationManagerCompat.cancelNotification(
                notificationManager,
                SHELL_PACKAGE_NAME,
                NOTIFICATION_TAG,
                NOTIFICATION_ID,
                0
            )
        }
    }

    private fun buildNotification(info: NotificationInfo): Notification {
        val contentText = String.format(
            Locale.getDefault(),
            NOTIFICATION_CONTENT_FORMAT,
            info.power,
            info.temp / 10.0,
            info.capacity
        )
        val builder = if (compatibilityModeEnabled) createBaseBuilder() else reusableBuilder
        return builder.setContentText(contentText)
            .setTicker(contentText)
            .build().apply {
                @Suppress("DEPRECATION")
                if (iconCompatibilityModeEnabled) {
                    contentView = null
                    bigContentView = null
                    headsUpContentView = null
                    color = Color.TRANSPARENT
                }
            }
    }

    private val cachedIcon = buildSmallIcon()

    private fun buildSmallIcon(): Icon {
        val defaultIcon = Icon.createWithResource("android", android.R.drawable.stat_notify_sync)
        return try {
            val appInfo = context.packageManager.getApplicationInfo(Constants.APP_PACKAGE_NAME, 0)
            val iconResId = appInfo.icon
            if (iconResId == 0) {
                LoggerX.w(TAG, "buildSmallIcon: 应用图标资源为空，回退系统图标")
                defaultIcon
            } else {
                Icon.createWithResource(Constants.APP_PACKAGE_NAME, iconResId)
            }
        } catch (e: PackageManager.NameNotFoundException) {
            LoggerX.w(TAG, "buildSmallIcon: 未找到应用包，回退系统图标", tr = e)
            defaultIcon
        } catch (e: Throwable) {
            LoggerX.e(TAG, "buildSmallIcon: 读取应用图标失败，回退系统图标", tr = e)
            defaultIcon
        }
    }

    private fun createBaseBuilder(): Notification.Builder =
        Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(cachedIcon)
            .setContentTitle(NOTIFICATION_TITLE)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(pendingIntent)

    private val pendingIntent by lazy {
        PendingIntent.getActivity(
            context,
            0,
            Intent().apply {
                component = ComponentName(
                    Constants.APP_PACKAGE_NAME,
                    "yangfentuozi.batteryrecorder.ui.MainActivity"
                )
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("open_current_record_detail", true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private val reusableBuilder by lazy(LazyThreadSafetyMode.NONE) { createBaseBuilder() }

    companion object {
        private const val SHELL_PACKAGE_NAME = "com.android.shell"
        private const val SHELL_UID = 2000
        private const val CHANNEL_ID = "batteryrecorder_notification"
        private const val CHANNEL_NAME = "BatteryRecorder"
        private const val NOTIFICATION_TAG = "batteryrecorder_notification"
        private const val NOTIFICATION_ID = 10086
        private const val NOTIFICATION_TITLE = "BatteryRecorder"
        private const val NOTIFICATION_CONTENT_FORMAT = "%.2f W | %.1f℃ | %d%%"
    }

}
