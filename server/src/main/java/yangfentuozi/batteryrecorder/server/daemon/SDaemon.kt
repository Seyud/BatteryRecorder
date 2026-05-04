package yangfentuozi.batteryrecorder.server.daemon

import android.content.pm.PackageManager
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Bundle
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import yangfentuozi.batteryrecorder.server.Server
import yangfentuozi.batteryrecorder.server.notification.server.ChildServerBridge
import yangfentuozi.batteryrecorder.server.sampler.DumpsysSampler
import yangfentuozi.batteryrecorder.server.sampler.SysfsSampler
import yangfentuozi.batteryrecorder.server.stream.StreamReader
import yangfentuozi.batteryrecorder.server.stream.StreamWriter
import yangfentuozi.batteryrecorder.server.util.changeOwnerRecursively
import yangfentuozi.batteryrecorder.server.writer.PowerRecordWriter
import yangfentuozi.batteryrecorder.shared.Constants
import yangfentuozi.batteryrecorder.shared.config.ConfigUtil
import yangfentuozi.batteryrecorder.shared.config.SettingsConstants
import yangfentuozi.batteryrecorder.shared.config.dataclass.ServerSettings
import yangfentuozi.batteryrecorder.shared.sync.PfdFileSender
import yangfentuozi.batteryrecorder.shared.util.Handlers
import yangfentuozi.batteryrecorder.shared.util.LoggerX
import yangfentuozi.hiddenapi.compat.ActivityManagerCompat
import yangfentuozi.hiddenapi.compat.PackageManagerCompat
import yangfentuozi.hiddenapi.compat.ServiceManagerCompat
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.nio.file.Files
import java.util.Scanner
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

private const val TAG = "SDaemon"
private const val SERVER_PROCESS_NAME = "batteryrecorder_server"
private const val SOCKET_NAME = "BatteryRecorder_Server"

// server daemon
class SDaemon : Daemon(SERVER_PROCESS_NAME) {

    private var cachedSettings: ServerSettings = ServerSettings()
    private var serverSocket: LocalServerSocket? = null
    private val appReinstallObserver: AppReinstallObserver

    private var appConfigFile: File
    private var appPowerDataDir: File
    private var shellPowerDataDir: File

    private val appReinstalled = AtomicBoolean(false)

    private val server: Server

    init {
        val isRoot = Os.getuid() == 0
        LoggerX.i(TAG, "init: 初始化 SDaemon")

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            LoggerX.a(thread.name, "Server crashed", tr = throwable)
            Handlers.main.post { exitProcess(255) }
        }

        ServiceManagerCompat.waitService("activity_task")
        ServiceManagerCompat.waitService("display")
        ServiceManagerCompat.waitService("power")
        ServiceManagerCompat.waitService("notification")

        val appInfo = try {
            PackageManagerCompat.getApplicationInfo(Constants.APP_PACKAGE_NAME, 0L, 0)
        } catch (e: RemoteException) {
            throw RuntimeException(
                "Failed to get application info for package: ${Constants.APP_PACKAGE_NAME}",
                e
            )
        } catch (e: PackageManager.NameNotFoundException) {
            throw RuntimeException("${Constants.APP_PACKAGE_NAME} is not installed", e)
        }
        appConfigFile = File("${appInfo.dataDir}/shared_prefs/${SettingsConstants.PREFS_NAME}.xml")
        appPowerDataDir = File("${appInfo.dataDir}/${Constants.APP_POWER_DATA_PATH}")

        appReinstallObserver = AppReinstallObserver(File(appInfo.sourceDir)) { appInfo ->
            if (!appReinstalled.compareAndSet(false, true)) return@AppReinstallObserver
            if (appInfo == null || appInfo.sourceDir == null || appInfo.nativeLibraryDir == null) {
                LoggerX.i(TAG, "@onAppReinstall: App 已被卸载, 退出服务")
                Handlers.main.postDelayed({ exitProcess(0) }, 100)
            } else {
                LoggerX.i(TAG, "@onAppReinstall: App 已被更新, 重启服务")
                restartServer(appInfo.nativeLibraryDir)
            }
            appReinstallObserver.stopWatching()
        }
        appReinstallObserver.startWatching()

        val sampler = if (SysfsSampler.init(appInfo)) SysfsSampler else DumpsysSampler()
        LoggerX.i(TAG, "init: 采样器选择完成, sampler=${sampler::class.java.simpleName}")

        shellPowerDataDir =
            File("${Constants.SHELL_DATA_DIR_PATH}/${Constants.SHELL_POWER_DATA_PATH}")

        if (Os.getuid() == 0) {
            shellPowerDataDir.let { shellPowerDataDir ->
                appPowerDataDir.let { appPowerDataDir ->
                    if (shellPowerDataDir.exists() && shellPowerDataDir.isDirectory) {
                        LoggerX.i(TAG, "init: root 模式迁移 shell 历史记录到 app 目录")
                        shellPowerDataDir.copyRecursively(
                            target = appPowerDataDir,
                            overwrite = true
                        )
                        shellPowerDataDir.deleteRecursively()
                        appPowerDataDir.changeOwnerRecursively(appInfo.uid)
                    }
                }
            }
            LoggerX.fixFileOwner = { it.changeOwnerRecursively(2000) }
        }

        var writerStatusData: PowerRecordWriter.WriterStatusData? = null

        run {
            LocalSocket().use { socket ->
                runCatching {
                    socket.connect(LocalSocketAddress(SOCKET_NAME))
                    LoggerX.i(TAG, "已连接旧 server, 准备接收状态数据")
                    Thread.sleep(200)
                    StreamReader(socket.inputStream).use { streamReader ->
                        repeat(5) {
                            if (writerStatusData == null) {
                                Thread.sleep(200)
                                writerStatusData = streamReader.readNext()
                            }
                        }
                        LoggerX.i(TAG, "已接收状态数据: $writerStatusData")
                    }
                }
            }
        }

        server = object : Server(
            powerDir = if (isRoot) appPowerDataDir else shellPowerDataDir,
            writerStatusData = writerStatusData,
            fixFileOwner = if (isRoot) {
                { it.changeOwnerRecursively(appInfo.uid) }
            } else {
                {}
            },
            sampler = sampler
        ) {
            override fun stopServer() = this@SDaemon.stopServer()

            override fun restartServer(nativeLibraryDir: String) =
                this@SDaemon.restartServer(nativeLibraryDir)

            override fun syncSettingsBlocking(settings: ServerSettings) {
                super.syncSettingsBlocking(settings)

                LoggerX.maxHistoryDays = settings.maxHistoryDays
                LoggerX.logLevel = settings.logLevel

                unlockOPlusSampleTimeLimit(settings.recordIntervalMs.coerceAtLeast(200))

                cachedSettings = settings
            }


            override fun sync(): ParcelFileDescriptor? {
                writer.flushBufferBlocking()
                if (Os.getuid() == 0) {
                    LoggerX.d(TAG, "sync: root 模式不需要同步文件, return null")
                    return null
                }

                val pipe = ParcelFileDescriptor.createPipe()
                val readEnd = pipe[0]
                val writeEnd = pipe[1]
                LoggerX.i(
                    TAG,
                    "sync: 开始同步 shell 记录目录, dir=${shellPowerDataDir.absolutePath}"
                )

                // 服务端在后台线程写入（发送）
                Thread {
                    try {
                        val currChargeDataPath =
                            if (writer.chargeDataWriter.needStartNewSegment(writer.chargeDataWriter.hasPendingStatusChange)) null
                            else writer.chargeDataWriter.segmentFile?.toPath()

                        val currDischargeDataPath =
                            if (writer.dischargeDataWriter.needStartNewSegment(writer.dischargeDataWriter.hasPendingStatusChange)) null
                            else writer.dischargeDataWriter.segmentFile?.toPath()
                        var sentCount = 0

                        PfdFileSender.sendFile(
                            writeEnd,
                            shellPowerDataDir
                        ) { file ->
                            sentCount += 1
                            LoggerX.d(TAG, "@sendFileCallback: 文件已发送, file=${file.name}")
                            if ((currChargeDataPath == null || !Files.isSameFile(
                                    file.toPath(),
                                    currChargeDataPath
                                )) &&
                                (currDischargeDataPath == null || !Files.isSameFile(
                                    file.toPath(),
                                    currDischargeDataPath
                                ))
                            ) file.delete()
                        }
                        LoggerX.i(TAG, "sync: 同步完成, sentCount=$sentCount")
                    } catch (e: Exception) {
                        LoggerX.e(TAG, "sync: 后台同步失败", tr = e)
                        try {
                            writeEnd.close()
                        } catch (_: Exception) {
                        }
                    }
                }.start()

                // 返回给客户端用于读取
                return readEnd
            }

            /**
             * 导出当前已落盘的服务端日志目录。
             *
             * 导出前会先同步 flush LoggerX，尽量把最近的故障日志和本次导出相关日志一并落盘；
             * App 侧会把该导出视为 best-effort，失败时显式降级为仅导出 App 日志。
             *
             * @return 用于读取日志目录文件流的管道读端。
             */
            override fun exportLogs(): ParcelFileDescriptor {
                val logDir =
                    File("${Constants.SHELL_DATA_DIR_PATH}/${Constants.SHELL_LOG_DIR_PATH}")
                LoggerX.i(TAG, "exportLogs: 收到服务端日志导出请求", notWrite = true)
                LoggerX.d(
                    TAG,
                    "exportLogs: 服务端日志目录 dir=${logDir.absolutePath}",
                    notWrite = true
                )

                if (!logDir.exists() || !logDir.isDirectory) {
                    LoggerX.w(
                        TAG,
                        "exportLogs: 服务端日志目录不可用 dir=${logDir.absolutePath}",
                        notWrite = true
                    )
                    throw RemoteException("服务端日志目录不存在: ${logDir.absolutePath}")
                }

                try {
                    LoggerX.flushBlocking()
                } catch (e: Exception) {
                    LoggerX.e(TAG, "exportLogs: 刷新服务端日志失败", tr = e, notWrite = true)
                    throw RemoteException("刷新服务端日志失败: ${e.message}").apply { initCause(e) }
                }

                if (!logDir.walkTopDown().any { it.isFile }) {
                    LoggerX.w(
                        TAG,
                        "exportLogs: 服务端日志目录为空 dir=${logDir.absolutePath}",
                        notWrite = true
                    )
                    throw RemoteException("服务端日志目录为空: ${logDir.absolutePath}")
                }

                val pipe = try {
                    ParcelFileDescriptor.createPipe()
                } catch (e: IOException) {
                    LoggerX.e(TAG, "exportLogs: 创建导出管道失败", tr = e, notWrite = true)
                    throw RemoteException("创建导出管道失败: ${e.message}").apply { initCause(e) }
                }
                val readEnd = pipe[0]
                val writeEnd = pipe[1]
                LoggerX.i(TAG, "exportLogs: 开始导出服务端日志")
                LoggerX.d(TAG, "exportLogs: 导出管道创建完成")
                try {
                    LoggerX.flushBlocking()
                } catch (e: Exception) {
                    runCatching { readEnd.close() }
                    runCatching { writeEnd.close() }
                    LoggerX.e(TAG, "exportLogs: 刷新服务端日志失败", tr = e, notWrite = true)
                    throw RemoteException("刷新服务端日志失败: ${e.message}").apply { initCause(e) }
                }

                Thread {
                    try {
                        var sentCount = 0
                        PfdFileSender.sendFile(writeEnd, logDir) { file ->
                            sentCount += 1
                            LoggerX.d(TAG, "exportLogs: 已发送日志文件 file=${file.name}")
                        }
                        LoggerX.i(TAG, "exportLogs: 服务端日志导出完成 sentCount=$sentCount")
                    } catch (e: Exception) {
                        LoggerX.e(TAG, "exportLogs: 服务端日志导出失败", tr = e)
                        try {
                            writeEnd.close()
                        } catch (_: Exception) {
                        }
                    }
                }.start()

                return readEnd
            }
        }

        Runtime.getRuntime().addShutdownHook(Thread {
            server.onStop()
            serverSocket?.close()
        })

        if (Os.getuid() == 0) {
            server.monitor.bridge = ChildServerBridge(appInfo.sourceDir) { // onFail
                LoggerX.w(TAG, "@onFail: Bridge 初始化失败")
                server.monitor.bridge = null
            }
            server.monitor.bridge!!.onWriterConnected = {
                it.writeSettings(cachedSettings)
            }
        }

        val serverSettings = if (Os.getuid() == 0) {
            LoggerX.i(
                TAG,
                "init: 通过 SharedPreferences XML 读取配置, path=${appConfigFile.absolutePath}"
            )
            ConfigUtil.readServerSettingsByReading(appConfigFile)
        } else {
            LoggerX.i(TAG, "init: 通过 ConfigProvider 读取配置")
            ConfigUtil.getServerSettingsByContentProvider()
        }

        serverSettings?.let { server.syncSettingsBlocking(it) }
            ?: LoggerX.w(TAG, "init: 未读取到配置, 使用当前默认值")


        LoggerX.i(TAG, "init: 初始化 BinderSender")
        BinderSender(appInfo.uid, ::sendBinder)

        server.onStart()

        Thread({
            Thread.sleep(1000)
            // 强制杀死状态异常的 server
            killOtherServersExceptSelf()

            try {
                serverSocket = LocalServerSocket(SOCKET_NAME)
            } catch (e: IOException) {
                throw RuntimeException(e)
            }
            runCatching {
                serverSocket?.let {
                    val socket = it.accept()
                    LoggerX.i(TAG, "新 server 已启动, 准备发送状态数据然后退出")
                    StreamWriter(socket.outputStream).use { streamWriter ->
                        streamWriter.write(server.writer.currWriterStatusAndClose())
                    }
                    LoggerX.i(TAG, "已发送状态数据")
                    socket.close()
                    it.close()
                    Handlers.main.postDelayed({ exitProcess(0) }, 100)
                }
            }
        }, "ServerSocketThread").start()
        Thread({
            try {
                val scanner = Scanner(System.`in`)
                var line: String
                while ((scanner.nextLine().also { line = it }) != null) {
                    if (line.trim { it <= ' ' } == "exit") {
                        stopServer()
                    }
                }
                scanner.close()
            } catch (_: Throwable) {
            }
        }, "InputHandler").start()
        Looper.loop()
    }

    fun stopServer() {
        Handlers.main.postDelayed({ exitProcess(0) }, 100)
    }

    fun restartServer(nativeLibraryDir: String) {
        ProcessBuilder("$nativeLibraryDir/libstarter.so").start()
    }

    private fun sendBinder() {
        LoggerX.d(TAG, "sendBinder: 开始向 App 推送 Binder")
        try {
            val reply = ActivityManagerCompat.contentProviderCall(
                "yangfentuozi.batteryrecorder.binderProvider",
                "setBinder",
                null,
                Bundle().apply {
                    putBinder("binder", server)
                }
            )
            if (reply == null) {
                LoggerX.w(TAG, "sendBinder: Binder 推送失败, reply == null")
            } else {
                LoggerX.i(TAG, "sendBinder: Binder 推送成功")
            }
        } catch (e: RemoteException) {
            LoggerX.w(TAG, "sendBinder: Binder 推送失败", tr = e)
        }
    }

    private fun unlockOPlusSampleTimeLimit(intervalMs: Long) {
        fun readFd(fd: FileDescriptor): String {
            val buffer = ByteArray(1024)
            val len = Os.read(fd, buffer, 0, buffer.size)
            return String(buffer, 0, len)
        }

        fun writeFd(fd: FileDescriptor, content: String) {
            val buffer = content.toByteArray()
            var toWrite = buffer.size
            var offset = 0
            while (toWrite > 0) {
                val len = Os.write(fd, buffer, offset, toWrite)
                toWrite -= len
                offset += len
            }
        }

        val forceActive = "/proc/oplus-votable/GAUGE_UPDATE/force_active"
        val forceVal = "/proc/oplus-votable/GAUGE_UPDATE/force_val"
        val perm = "666".toInt(8)

        var forceActiveFd: FileDescriptor? = null
        var forceValFd: FileDescriptor? = null

        try {
            if (try {
                    Os.access(forceActive, OsConstants.F_OK)
                } catch (_: ErrnoException) {
                    false
                }
            ) {
                LoggerX.i(TAG, "unlockOPlusSampleTimeLimit: 欧加功率采样频率解限文件存在")

                Os.chmod(forceActive, perm)
                Os.chmod(forceVal, perm)

                forceActiveFd = Os.open(forceActive, OsConstants.O_RDWR, perm)
                forceValFd = Os.open(forceVal, OsConstants.O_RDWR, perm)

                val nowValue = readFd(forceValFd).trim().toLong()
                val nowActive = readFd(forceActiveFd).trim().toInt() == 1
                if (!nowActive || nowValue > intervalMs || nowValue == 0L) {
                    LoggerX.i(
                        TAG,
                        "unlockOPlusSampleTimeLimit: 解锁欧加功率采样频率, target=${intervalMs}ms nowValue=${nowValue}ms nowActive=$nowActive"
                    )
                    writeFd(forceValFd, "$intervalMs\n")
                    writeFd(forceActiveFd, "1\n")
                }
            }
        } catch (e: Exception) {
            LoggerX.w(TAG, "unlockOPlusSampleTimeLimit: 解锁欧加功率采样频率限制失败", tr = e)
        } finally {
            if (forceActiveFd != null) Os.close(forceActiveFd)
            if (forceValFd != null) Os.close(forceValFd)
        }
    }
}
