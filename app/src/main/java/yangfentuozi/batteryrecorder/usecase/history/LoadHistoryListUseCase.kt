package yangfentuozi.batteryrecorder.usecase.history

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import yangfentuozi.batteryrecorder.data.history.HistoryRecord
import yangfentuozi.batteryrecorder.data.history.HistoryRepository
import yangfentuozi.batteryrecorder.shared.config.SharedSettings
import yangfentuozi.batteryrecorder.shared.config.SettingsConstants
import yangfentuozi.batteryrecorder.shared.data.BatteryStatus
import yangfentuozi.batteryrecorder.shared.data.RecordFileNames
import yangfentuozi.batteryrecorder.shared.data.RecordsFile
import yangfentuozi.batteryrecorder.ui.mapper.PowerDisplayMapper
import java.io.File

/**
 * 历史列表分页会话状态。
 */
internal data class HistoryListSession(
    val listFiles: List<File> = emptyList(),
    val allListRecordsCache: List<HistoryRecord>? = null,
    val pagedSourceRecords: List<HistoryRecord>? = null,
    val latestListFile: File? = null,
    val loadedRecordCount: Int = 0,
    val listDischargeDisplayPositive: Boolean = SettingsConstants.dischargeDisplayPositive.def,
    val currentListType: BatteryStatus = BatteryStatus.Unknown,
    val hasInitializedListContext: Boolean = false
)

/**
 * 历史列表视图结果。
 */
internal data class HistoryListUiResult(
    val session: HistoryListSession,
    val records: List<HistoryRecord>,
    val hasMoreRecords: Boolean
)

private const val PAGE_SIZE = 10

/**
 * 统一处理历史列表加载、分页、筛选和回前台刷新。
 */
internal object LoadHistoryListUseCase {

    /**
     * 首次加载或整页重载历史列表。
     *
     * @param context 应用上下文。
     * @param type 当前列表类型。
     * @param previousSession 旧列表会话。
     * @param chargeCapacityChangeFilter 当前充电容量筛选值。
     * @return 返回新的列表会话与首屏数据。
     */
    suspend fun reload(
        context: Context,
        type: BatteryStatus,
        previousSession: HistoryListSession,
        chargeCapacityChangeFilter: Int?
    ): HistoryListUiResult {
        val dischargeDisplayPositive =
            SharedSettings.readAppSettings(context).dischargeDisplayPositive
        val files = withContext(Dispatchers.IO) {
            HistoryRepository.listRecordFiles(context, type)
        }
        var session = previousSession.copy(
            listFiles = files,
            allListRecordsCache = null,
            pagedSourceRecords = null,
            latestListFile = files.firstOrNull(),
            loadedRecordCount = 0,
            listDischargeDisplayPositive = dischargeDisplayPositive,
            currentListType = type,
            hasInitializedListContext = true
        )
        if (chargeCapacityChangeFilter != null) {
            val allRecords = ensureAllListRecordsCache(context, session)
            session = session.copy(
                allListRecordsCache = allRecords,
                pagedSourceRecords = allRecords.filter { record ->
                    computeChargingCapacityChange(record) >= chargeCapacityChangeFilter
                }
            )
        }
        return loadNextPage(
            context = context,
            session = session,
            displayedRecords = emptyList()
        )
    }

    /**
     * 重新进入前台时刷新当前上下文。
     *
     * @param context 应用上下文。
     * @param type 当前列表类型。
     * @param session 当前列表会话。
     * @param displayedRecords 当前已展示列表。
     * @param chargeCapacityChangeFilter 当前充电容量筛选值。
     * @return 返回刷新后的列表会话与展示结果。
     */
    suspend fun refreshOnEnter(
        context: Context,
        type: BatteryStatus,
        session: HistoryListSession,
        displayedRecords: List<HistoryRecord>,
        chargeCapacityChangeFilter: Int?
    ): HistoryListUiResult {
        if (session.currentListType != type || !session.hasInitializedListContext) {
            return reload(
                context = context,
                type = type,
                previousSession = session,
                chargeCapacityChangeFilter = chargeCapacityChangeFilter
            )
        }

        val dischargeDisplayPositive =
            SharedSettings.readAppSettings(context).dischargeDisplayPositive
        val latestFiles = withContext(Dispatchers.IO) {
            HistoryRepository.listRecordFiles(context, type)
        }
        if (
            shouldReloadListOnEnter(
                session = session,
                latestFiles = latestFiles,
                displayedRecords = displayedRecords,
                chargeCapacityChangeFilter = chargeCapacityChangeFilter,
                dischargeDisplayPositive = dischargeDisplayPositive
            )
        ) {
            return reload(
                context = context,
                type = type,
                previousSession = session,
                chargeCapacityChangeFilter = chargeCapacityChangeFilter
            )
        }

        val updatedSession = session.copy(
            listFiles = latestFiles,
            latestListFile = latestFiles.firstOrNull(),
            listDischargeDisplayPositive = dischargeDisplayPositive
        )
        val latestFile = updatedSession.latestListFile ?: return HistoryListUiResult(
            session = updatedSession,
            records = displayedRecords,
            hasMoreRecords = updatedSession.loadedRecordCount < currentSourceCount(updatedSession)
        )
        val currentFirstRecord = displayedRecords.firstOrNull() ?: return HistoryListUiResult(
            session = updatedSession,
            records = displayedRecords,
            hasMoreRecords = updatedSession.loadedRecordCount < currentSourceCount(updatedSession)
        )
        if (currentFirstRecord.lastModified == latestFile.lastModified()) {
            return HistoryListUiResult(
                session = updatedSession,
                records = displayedRecords,
                hasMoreRecords = updatedSession.loadedRecordCount < currentSourceCount(updatedSession)
            )
        }

        val refreshedRecord = withContext(Dispatchers.IO) {
            buildHistoryRecord(
                context = context,
                file = latestFile,
                latestFile = latestFile,
                dischargeDisplayPositive = dischargeDisplayPositive
            )
        } ?: return reload(
            context = context,
            type = type,
            previousSession = session,
            chargeCapacityChangeFilter = chargeCapacityChangeFilter
        )

        return HistoryListUiResult(
            session = replaceCachedRecord(updatedSession, refreshedRecord),
            records = displayedRecords.map { record ->
                if (record.name == refreshedRecord.name) refreshedRecord else record
            },
            hasMoreRecords = updatedSession.loadedRecordCount < currentSourceCount(updatedSession)
        )
    }

    /**
     * 加载下一页。
     *
     * @param context 应用上下文。
     * @param session 当前列表会话。
     * @param displayedRecords 当前已展示列表。
     * @return 返回追加分页后的列表结果。
     */
    suspend fun loadNextPage(
        context: Context,
        session: HistoryListSession,
        displayedRecords: List<HistoryRecord>
    ): HistoryListUiResult {
        val startIndex = session.loadedRecordCount
        val sourceRecords = session.pagedSourceRecords
        val sourceSize = sourceRecords?.size ?: session.listFiles.size
        val endExclusive = (startIndex + PAGE_SIZE).coerceAtMost(sourceSize)
        if (startIndex >= endExclusive) {
            return HistoryListUiResult(
                session = session,
                records = displayedRecords,
                hasMoreRecords = false
            )
        }
        val nextPageRecords =
            sourceRecords?.subList(startIndex, endExclusive) ?: withContext(Dispatchers.IO) {
                val filesToLoad = session.listFiles.subList(startIndex, endExclusive).toList()
                filesToLoad.mapNotNull { file ->
                    buildHistoryRecord(
                        context = context,
                        file = file,
                        latestFile = session.latestListFile,
                        dischargeDisplayPositive = session.listDischargeDisplayPositive
                    )
                }
            }
        val nextSession = session.copy(loadedRecordCount = endExclusive)
        return HistoryListUiResult(
            session = nextSession,
            records = displayedRecords + nextPageRecords,
            hasMoreRecords = nextSession.loadedRecordCount < currentSourceCount(nextSession)
        )
    }

    /**
     * 更新充电容量筛选并重新生成列表。
     *
     * @param context 应用上下文。
     * @param session 当前列表会话。
     * @param chargeCapacityChangeFilter 当前充电容量筛选值。
     * @return 返回筛选后的列表结果。
     */
    suspend fun applyChargeCapacityChangeFilter(
        context: Context,
        session: HistoryListSession,
        chargeCapacityChangeFilter: Int?
    ): HistoryListUiResult {
        val nextSession = when (chargeCapacityChangeFilter) {
            null -> session.copy(
                pagedSourceRecords = null,
                loadedRecordCount = 0
            )

            else -> {
                val allRecords = ensureAllListRecordsCache(context, session)
                session.copy(
                    allListRecordsCache = allRecords,
                    pagedSourceRecords = allRecords.filter { record ->
                        computeChargingCapacityChange(record) >= chargeCapacityChangeFilter
                    },
                    loadedRecordCount = 0
                )
            }
        }
        return loadNextPage(
            context = context,
            session = nextSession,
            displayedRecords = emptyList()
        )
    }

    /**
     * 删除后同步修正分页会话与当前展示列表。
     *
     * @param session 当前列表会话。
     * @param displayedRecords 当前已展示列表。
     * @param deletedRecordName 被删除的逻辑记录名。
     * @return 返回删除后的列表结果。
     */
    fun removeDeletedRecord(
        session: HistoryListSession,
        displayedRecords: List<HistoryRecord>,
        deletedRecordName: String
    ): HistoryListUiResult {
        val deletedSourceIndex = findSourceIndex(session, deletedRecordName)
        val nextSession = session.copy(
            listFiles = session.listFiles.filter { it.logicalRecordName() != deletedRecordName },
            latestListFile = session.listFiles
                .filter { it.logicalRecordName() != deletedRecordName }
                .firstOrNull(),
            allListRecordsCache = session.allListRecordsCache?.filter { record ->
                record.name != deletedRecordName
            },
            pagedSourceRecords = session.pagedSourceRecords?.filter { record ->
                record.name != deletedRecordName
            },
            loadedRecordCount = session.loadedRecordCount.let { loadedCount ->
                val nextLoadedCount = if (deletedSourceIndex in 0 until loadedCount) {
                    loadedCount - 1
                } else {
                    loadedCount
                }
                nextLoadedCount.coerceAtMost(
                    currentSourceCount(
                        session.copy(
                            listFiles = session.listFiles.filter {
                                it.logicalRecordName() != deletedRecordName
                            },
                            allListRecordsCache = session.allListRecordsCache?.filter { record ->
                                record.name != deletedRecordName
                            },
                            pagedSourceRecords = session.pagedSourceRecords?.filter { record ->
                                record.name != deletedRecordName
                            }
                        )
                    )
                )
            }
        )
        return HistoryListUiResult(
            session = nextSession,
            records = displayedRecords.filter { it.name != deletedRecordName },
            hasMoreRecords = nextSession.loadedRecordCount < currentSourceCount(nextSession)
        )
    }

    /**
     * 返回当前上下文下可导出的记录列表。
     *
     * @param session 当前列表会话。
     * @param type 目标记录类型。
     * @return 当前上下文匹配时返回记录列表，否则返回空值。
     */
    fun resolveCurrentExportRecords(
        session: HistoryListSession,
        type: BatteryStatus
    ): List<RecordsFile>? {
        if (session.currentListType != type) return null
        session.pagedSourceRecords?.let { records ->
            return records.map { record -> record.asRecordsFile() }
        }
        return session.listFiles.map { file -> RecordsFile.fromFile(file) }
    }

    private suspend fun ensureAllListRecordsCache(
        context: Context,
        session: HistoryListSession
    ): List<HistoryRecord> {
        session.allListRecordsCache?.let { return it }
        return withContext(Dispatchers.IO) {
            session.listFiles.mapNotNull { file ->
                buildHistoryRecord(
                    context = context,
                    file = file,
                    latestFile = session.latestListFile,
                    dischargeDisplayPositive = session.listDischargeDisplayPositive
                )
            }
        }
    }

    private fun shouldReloadListOnEnter(
        session: HistoryListSession,
        latestFiles: List<File>,
        displayedRecords: List<HistoryRecord>,
        chargeCapacityChangeFilter: Int?,
        dischargeDisplayPositive: Boolean
    ): Boolean {
        if (chargeCapacityChangeFilter != null) return true
        if (session.listDischargePositiveChanged(dischargeDisplayPositive)) return true
        if (latestFiles.size != session.listFiles.size) return true
        if (latestFiles.indices.any { index ->
                latestFiles[index].logicalRecordName() != session.listFiles[index].logicalRecordName()
            }
        ) {
            return true
        }

        val latestFile = latestFiles.firstOrNull() ?: return displayedRecords.isNotEmpty()
        val currentFirstRecord = displayedRecords.firstOrNull() ?: return true
        return currentFirstRecord.name != latestFile.logicalRecordName()
    }

    private fun HistoryListSession.listDischargePositiveChanged(
        dischargeDisplayPositive: Boolean
    ): Boolean = listDischargeDisplayPositive != dischargeDisplayPositive

    private fun replaceCachedRecord(
        session: HistoryListSession,
        refreshedRecord: HistoryRecord
    ): HistoryListSession {
        return session.copy(
            allListRecordsCache = session.allListRecordsCache?.map { record ->
                if (record.name == refreshedRecord.name) refreshedRecord else record
            },
            pagedSourceRecords = session.pagedSourceRecords?.map { record ->
                if (record.name == refreshedRecord.name) refreshedRecord else record
            }
        )
    }

    private fun buildHistoryRecord(
        context: Context,
        file: File,
        latestFile: File?,
        dischargeDisplayPositive: Boolean
    ): HistoryRecord? {
        return HistoryRepository.loadStats(context, file, file != latestFile)
            ?.let { historyRecord ->
                PowerDisplayMapper.mapHistoryRecord(historyRecord, dischargeDisplayPositive)
            }
    }

    private fun computeChargingCapacityChange(record: HistoryRecord): Int =
        record.stats.endCapacity - record.stats.startCapacity

    private fun findSourceIndex(session: HistoryListSession, recordName: String): Int {
        val sourceRecords = session.pagedSourceRecords
        if (sourceRecords != null) {
            return sourceRecords.indexOfFirst { record -> record.name == recordName }
        }
        return session.listFiles.indexOfFirst { file -> file.logicalRecordName() == recordName }
    }

    private fun currentSourceCount(session: HistoryListSession): Int =
        session.pagedSourceRecords?.size ?: session.listFiles.size

    private fun File.logicalRecordName(): String =
        RecordFileNames.logicalNameOrNull(name)
            ?: throw IllegalArgumentException("Invalid record file name: $name")
}
