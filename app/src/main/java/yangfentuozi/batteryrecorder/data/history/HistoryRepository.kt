package yangfentuozi.batteryrecorder.data.history

import android.content.Context
import android.net.Uri
import yangfentuozi.batteryrecorder.data.model.ChartPoint
import yangfentuozi.batteryrecorder.ipc.Service
import yangfentuozi.batteryrecorder.shared.Constants
import yangfentuozi.batteryrecorder.shared.data.BatteryStatus
import yangfentuozi.batteryrecorder.shared.data.LineRecord
import yangfentuozi.batteryrecorder.shared.data.RecordFileParser
import yangfentuozi.batteryrecorder.shared.data.RecordsFile
import yangfentuozi.batteryrecorder.shared.data.RecordsStats
import yangfentuozi.batteryrecorder.shared.util.LoggerX
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class HistoryRecord(
    val name: String,
    val type: BatteryStatus,
    val stats: RecordsStats,
    val lastModified: Long
) {
    fun asRecordsFile(): RecordsFile =
        RecordsFile(type, name)
}

data class HistorySummary(
    val type: BatteryStatus,
    val recordCount: Int,
    val averagePower: Double,
    val totalDurationMs: Long,
    val totalScreenOnMs: Long,
    val totalScreenOffMs: Long
)

object HistoryRepository {

    // 记录文件名由“起始时间戳.txt”组成；无法解析的文件必须显式告警并从记录链路中过滤。
    private fun recordFileTimestampOrNull(file: File): Long? =
        file.name.substringBeforeLast('.').toLongOrNull()

    private fun listSortedRecordFiles(dir: File): List<File> =
        dir.listFiles()
            ?.asSequence()
            ?.filter { it.isFile }
            ?.mapNotNull { file ->
                val timestamp = recordFileTimestampOrNull(file)
                if (timestamp == null) {
                    LoggerX.w<HistoryRepository>("[记录] 跳过非法记录文件: ${file.absolutePath}")
                    return@mapNotNull null
                }
                file to timestamp
            }
            ?.sortedByDescending { it.second }
            ?.map { it.first }
            ?.toList()
            ?: emptyList()

    fun RecordsFile.toFile(context: Context): File? {
        val dataDir = dataDir(context, type)
        return validFile(dataDir, name)
    }

    // 确保目录存在且为目录，返回目录对象
    private fun dataDir(context: Context, type: BatteryStatus): File =
        File(File(context.dataDir, Constants.APP_POWER_DATA_PATH), type.dataDirName).apply {
            if (!isDirectory) delete()
            if (!exists()) mkdirs()
        }

    // 验证文件有效性，返回 null 表示无效
    private fun validFile(dir: File, name: String): File? =
        File(dir, name).takeIf { it.isFile }

    private fun buildHistoryRecord(file: File, stats: RecordsStats): HistoryRecord {
        return HistoryRecord(
            name = file.name,
            type = BatteryStatus.fromDataDirName(file.parentFile?.name),
            stats = stats,
            lastModified = file.lastModified()
        )
    }

    /** 加载统计数据并构建 HistoryRecord */
    fun loadStats(
        context: Context,
        file: File,
        needCaching: Boolean
    ): HistoryRecord? {
        val cacheFile = getPowerStatsCacheFile(context.cacheDir, file.name)
        LoggerX.d<HistoryRepository>(
            "[历史] 加载记录统计: file=${file.name} needCaching=$needCaching cache=${cacheFile.name}"
        )
        val stats = runCatching {
            RecordsStats.getCachedStats(
                cacheFile = cacheFile,
                sourceFile = file,
                needCaching = needCaching
            )
        }.onFailure { error ->
            LoggerX.e<HistoryRepository>("[历史] 加载记录统计失败: ${file.absolutePath}", tr = error)
        }.getOrNull() ?: return null

        return buildHistoryRecord(file, stats)
    }

    /** 仅列出文件，按记录起始时间倒序，不加载 stats */
    fun listRecordFiles(context: Context, type: BatteryStatus): List<File> {
        val files = listSortedRecordFiles(dataDir(context, type))
        LoggerX.d<HistoryRepository>("[历史] 列出记录文件: type=$type count=${files.size}")
        return files
    }

    /** 加载指定类型的所有记录，按记录起始时间倒序 */
    fun loadRecords(context: Context, type: BatteryStatus): List<HistoryRecord> {
        val files = listSortedRecordFiles(dataDir(context, type))
        if (files.isEmpty()) {
            LoggerX.d<HistoryRepository>("[历史] 无记录文件: type=$type")
            return emptyList()
        }
        val latestFile = files.firstOrNull()
        LoggerX.d<HistoryRepository>("[历史] 加载记录列表: type=$type count=${files.size} latest=${latestFile?.name}")

        return files.mapNotNull { loadStats(context, it, it != latestFile) }
    }

    /** 加载指定名称的单条记录 */
    @Throws(Exception::class)
    fun loadRecord(context: Context, file: File): HistoryRecord {
        val dataDir = file.parentFile!!
        val latestFile = listSortedRecordFiles(dataDir).firstOrNull()
        val cacheFile = getPowerStatsCacheFile(context.cacheDir, file.name)
        val stats = RecordsStats.getCachedStats(
            cacheFile = cacheFile,
            sourceFile = file,
            needCaching = latestFile != file
        )
        return buildHistoryRecord(file, stats)
    }

    /** 加载记录的图表数据点，用于绘制功率曲线 */
    @Throws(FileNotFoundException::class)
    fun loadRecordPoints(context: Context, recordsFile: RecordsFile): List<ChartPoint> {
        val file = recordsFile.toFile(context)
            ?: throw FileNotFoundException("Record file not found: ${recordsFile.name}")
        return loadRecordPoints(file)
    }

    fun loadRecordPoints(file: File): List<ChartPoint> {
        return loadLineRecords(file).map { record ->
            ChartPoint(
                timestamp = record.timestamp,
                power = record.power.toDouble(),
                packageName = record.packageName,
                capacity = record.capacity,
                isDisplayOn = record.isDisplayOn == 1,
                temp = record.temp
            )
        }
    }

    fun loadLineRecords(file: File): List<LineRecord> =
        RecordFileParser.parseToList(file)

    /** 从 service.currRecordsFile 加载当前记录 */
    fun loadLatestRecord(
        context: Context
    ): HistoryRecord? {
        val service = Service.service ?: return null
        val serviceFile = runCatching { service.currRecordsFile }
            .getOrNull()
            ?.toFile(context)
        LoggerX.d<HistoryRepository>("[历史] 加载当前记录: serviceFile=${serviceFile?.name}")
        return serviceFile?.let { file ->
            loadStats(context, file, false)
        }
    }

    /** 加载统计摘要，按时长加权计算平均功率 */
    fun loadSummary(
        context: Context,
        type: BatteryStatus,
        avgPowerLimit: Int = 20
    ): HistorySummary? {
        val dataDir = dataDir(context, type)
        val files = listSortedRecordFiles(dataDir)

        val recordCount = files.size
        if (recordCount == 0) {
            LoggerX.d<HistoryRepository>("[历史] 汇总为空: type=$type")
            return null
        }

        val latestFile = files.first()
        val sampleFiles = files.take(avgPowerLimit.coerceAtLeast(0))
        if (sampleFiles.isEmpty()) {
            LoggerX.w<HistoryRepository>("[历史] 汇总样本为空: type=$type avgPowerLimit=$avgPowerLimit")
            return null
        }

        val records = sampleFiles.mapNotNull { loadStats(context, it, it != latestFile) }
        if (records.isEmpty()) {
            LoggerX.w<HistoryRepository>("[历史] 汇总记录加载失败: type=$type sampleCount=${sampleFiles.size}")
            return null
        }

        var totalDurationMs = 0L
        var weightedPowerSum = 0.0
        var totalScreenOnMs = 0L
        var totalScreenOffMs = 0L

        records.forEach { record ->
            val stats = record.stats
            val durationMs = (stats.endTime - stats.startTime).coerceAtLeast(0)
            totalDurationMs += durationMs
            weightedPowerSum += stats.averagePower * durationMs
            totalScreenOnMs += stats.screenOnTimeMs
            totalScreenOffMs += stats.screenOffTimeMs
        }

        val averagePower = if (totalDurationMs > 0) {
            weightedPowerSum / totalDurationMs
        } else {
            records.map { it.stats.averagePower }.average()
        }

        val summary = HistorySummary(
            type = type,
            recordCount = recordCount,
            averagePower = averagePower,
            totalDurationMs = totalDurationMs,
            totalScreenOnMs = totalScreenOnMs,
            totalScreenOffMs = totalScreenOffMs
        )
        LoggerX.d<HistoryRepository>(
            "[历史] 汇总完成: type=$type recordCount=$recordCount durationMs=$totalDurationMs avgPower=$averagePower"
        )
        return summary
    }

    /** 删除记录及其缓存文件 */
    fun deleteRecord(context: Context, recordsFile: RecordsFile): Boolean {

        if (runCatching { recordsFile.toFile(context)!!.delete() }.getOrDefault(false)) {
            // 同步删除缓存文件
            runCatching { getPowerStatsCacheFile(context.cacheDir, recordsFile.name).delete() }
            LoggerX.i<HistoryRepository>("[历史] 删除记录成功: ${recordsFile.name}")
            return true
        }
        LoggerX.w<HistoryRepository>("[历史] 删除记录失败: ${recordsFile.name}")
        return false
    }

    /** 导出单条记录到用户选择位置 */
    @Throws(IOException::class)
    fun exportRecord(
        context: Context,
        recordsFile: RecordsFile,
        destinationUri: Uri
    ) {
        val sourceFile = recordsFile.toFile(context)
            ?: throw FileNotFoundException("Record file not found: ${recordsFile.name}")
        // 目标由 SAF 提供，必须通过 ContentResolver 流写入而不是直接按文件路径访问
        val outputStream = context.contentResolver.openOutputStream(destinationUri, "w")
            ?: throw IOException("Failed to open destination: $destinationUri")

        sourceFile.inputStream().use { input ->
            outputStream.use { output ->
                input.copyTo(output)
            }
        }
        LoggerX.i<HistoryRepository>("[历史] 导出记录成功: source=${recordsFile.name} destination=$destinationUri")
    }

    /** 导出当前列表内的所有记录到 ZIP */
    @Throws(IOException::class)
    fun exportRecordsZip(
        context: Context,
        recordsFiles: List<RecordsFile>,
        destinationUri: Uri
    ) {
        val outputStream = context.contentResolver.openOutputStream(destinationUri, "w")
            ?: throw IOException("Failed to open destination: $destinationUri")

        outputStream.use { rawOutput ->
            ZipOutputStream(rawOutput).use { zipOutput ->
                recordsFiles.forEach { recordsFile ->
                    val sourceFile = recordsFile.toFile(context)
                        ?: throw FileNotFoundException("Record file not found: ${recordsFile.name}")
                    zipOutput.putNextEntry(ZipEntry(sourceFile.name))
                    sourceFile.inputStream().use { input ->
                        input.copyTo(zipOutput)
                    }
                    zipOutput.closeEntry()
                }
            }
        }
        LoggerX.i<HistoryRepository>("[历史] 导出记录压缩包成功: count=${recordsFiles.size} destination=$destinationUri")
    }
}
