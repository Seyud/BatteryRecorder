package yangfentuozi.batteryrecorder.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import yangfentuozi.batteryrecorder.shared.config.dataclass.UpdateChannel
import yangfentuozi.batteryrecorder.shared.util.LoggerX
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "UpdateUtils"

data class AppUpdate(
    val versionName: String,
    val versionCode: Int,
    val body: String,
    val downloadUrl: String,
    val updateChannel: UpdateChannel
)

object UpdateUtils {
    private val versionCodeRegex = Regex("""<!--\s*versionCode:(\d+)\s*-->""")
    private val commitPrefixRegex =
        Regex("""(?m)^-\s+\[`[0-9a-f]{7,40}`]\(https://github\.com/Itosang/BatteryRecorder/commit/[0-9a-f]{7,40}\)\s+""")

    private const val GITHUB_LATEST_RELEASE_API_URL =
        "https://api.github.com/repos/Itosang/BatteryRecorder/releases/latest"
    private const val GITHUB_RELEASES_API_URL =
        "https://api.github.com/repos/Itosang/BatteryRecorder/releases?per_page=20"

    private fun parseVersionCode(body: String): Int {
        // versionCode 由 release notes 隐藏元数据提供。
        return versionCodeRegex.find(body)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: -1
    }

    private fun parseVersionName(tagName: String): String {
        // 新格式: v1.0.0-release -> 1.0.0-release
        return tagName.removePrefix("v")
    }

    private fun normalizeBody(body: String): String {
        return body
            .let { versionCodeRegex.replace(it, "") }
            .let { commitPrefixRegex.replace(it, "- ") }
            .trim()
    }

    /**
     * 根据通道从 GitHub 拉取最新可用更新。
     *
     * @param channel 当前设置的更新通道。
     * @return 成功时返回 `Result.success`；
     * 若接口请求成功但没有匹配的 release，则 success 值为 null；
     * 若请求或解析失败，则返回 `Result.failure`。
     */
    suspend fun fetchUpdate(channel: UpdateChannel): Result<AppUpdate?> = withContext(Dispatchers.IO) {
        runCatching {
            when (channel) {
                UpdateChannel.Stable -> {
                    LoggerX.v(TAG, "[更新] 准备请求 GitHub 最新稳定版 release")
                    requestJsonObject(GITHUB_LATEST_RELEASE_API_URL).let(::parseAppUpdate)
                }

                UpdateChannel.Prerelease -> {
                    LoggerX.v(TAG, "[更新] 准备请求 GitHub 发布列表（预发布通道）")
                    val releases = requestJsonArray(GITHUB_RELEASES_API_URL)
                    findLatestReleaseForPrereleaseChannel(releases)?.let(::parseAppUpdate)
                }
            }
        }.onFailure { error ->
            LoggerX.e(TAG, "[更新] GitHub 检查更新失败，channel=$channel", tr = error)
        }
    }

    private fun requestJsonObject(url: String): JSONObject {
        val responseBody = requestResponseBody(url)
        if (responseBody.isEmpty()) {
            error("GitHub 响应内容为空，url=$url")
        }
        return JSONObject(responseBody)
    }

    private fun requestJsonArray(url: String): JSONArray {
        val responseBody = requestResponseBody(url)
        if (responseBody.isEmpty()) {
            error("GitHub 响应内容为空，url=$url")
        }
        return JSONArray(responseBody)
    }

    private fun requestResponseBody(url: String): String {
        var connection: HttpURLConnection? = null
        try {
            connection = openConnection(url)
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                error("GitHub 获取更新信息失败，响应码=$responseCode url=$url")
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection?.disconnect()
        }
    }

    private fun openConnection(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10000
            readTimeout = 10000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "BatteryRecorder-App")
        }

    private fun findLatestReleaseForPrereleaseChannel(releases: JSONArray): JSONObject? {
        if (releases.length() == 0) {
            LoggerX.w(TAG, "[更新] GitHub 发布列表为空")
            return null
        }
        for (index in 0 until releases.length()) {
            val release = releases.optJSONObject(index) ?: continue
            if (release.optBoolean("draft", false)) continue
            return release
        }
        LoggerX.i(TAG, "[更新] 当前发布列表中没有可用的非 draft 版本")
        return null
    }

    private fun parseAppUpdate(json: JSONObject): AppUpdate {
        val tagName = json.optString("tag_name", "")
        val rawBody = json.optString("body", "")
        val versionCode = parseVersionCode(rawBody)
        val body = normalizeBody(rawBody)
        val versionName = parseVersionName(tagName)
        val downloadUrl = findApkDownloadUrl(json.optJSONArray("assets"))
        val updateChannel =
            if (json.optBoolean("prerelease", false)) UpdateChannel.Prerelease else UpdateChannel.Stable

        if (downloadUrl.isBlank()) {
            error("更新资源缺少下载地址，tag=$tagName")
        }

        check(versionCode > 0) { "解析 versionCode 失败，tag=$tagName" }
        LoggerX.d(
            TAG,
            "[更新] GitHub release 解析成功，tag=$tagName versionCode=$versionCode channel=$updateChannel"
        )
        return AppUpdate(
            versionName = versionName,
            versionCode = versionCode,
            body = body,
            downloadUrl = downloadUrl,
            updateChannel = updateChannel
        )
    }

    private fun findApkDownloadUrl(assets: org.json.JSONArray?): String {
        if (assets == null || assets.length() == 0) return ""
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            if (asset.optString("name", "").endsWith(".apk")) {
                return asset.optString("browser_download_url", "")
            }
        }
        return assets.getJSONObject(0).optString("browser_download_url", "")
    }
}
