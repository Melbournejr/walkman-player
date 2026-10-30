package com.sony.walkmanpro

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class OtaReleaseInfo(
    val versionTag: String,
    val cleanVersion: String,
    val releaseTitle: String,
    val changelog: String,
    val apkDownloadUrl: String,
    val apkSizeBytes: Long,
    val publishedAt: String
)

sealed class OtaCheckResult {
    data class UpdateAvailable(val release: OtaReleaseInfo) : OtaCheckResult()
    data class UpToDate(val currentVersion: String) : OtaCheckResult()
    data class Error(val message: String) : OtaCheckResult()
}

object OtaUpdateManager {
    private const val PREF_OTA = "walkman_ota_prefs"
    private const val KEY_AUTO_CHECK = "ota_auto_check_on_start"
    private const val KEY_CUSTOM_REPO = "ota_custom_github_repo"

    fun isAutoCheckEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREF_OTA, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_CHECK, true)
    }

    fun setAutoCheckEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREF_OTA, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AUTO_CHECK, enabled).apply()
    }

    fun getConfiguredRepo(context: Context): String {
        val saved = context.getSharedPreferences(PREF_OTA, Context.MODE_PRIVATE)
            .getString(KEY_CUSTOM_REPO, null)
        return if (!saved.isNullOrBlank() && saved.contains("/")) {
            saved.trim()
        } else {
            BuildConfig.GITHUB_REPO
        }
    }

    fun setConfiguredRepo(context: Context, repoSlug: String) {
        context.getSharedPreferences(PREF_OTA, Context.MODE_PRIVATE)
            .edit().putString(KEY_CUSTOM_REPO, repoSlug.trim()).apply()
    }

    /**
     * Chuẩn hóa chuỗi phiên bản (ví dụ "v11.1.0-Release" -> [11, 1, 0]) để so sánh chính xác.
     */
    private fun parseSemVerParts(versionStr: String): List<Int> {
        val regex = Regex("""(\d+)\.(\d+)(?:\.(\d+))?""")
        val match = regex.find(versionStr) ?: return listOf(0, 0, 0)
        val major = match.groupValues.getOrNull(1)?.toIntOrNull() ?: 0
        val minor = match.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
        val patch = match.groupValues.getOrNull(3)?.toIntOrNull() ?: 0
        return listOf(major, minor, patch)
    }

    fun isRemoteVersionNewer(currentVersion: String, remoteVersion: String): Boolean {
        val curr = parseSemVerParts(currentVersion)
        val rem = parseSemVerParts(remoteVersion)
        for (i in 0..2) {
            if (rem[i] > curr[i]) return true
            if (rem[i] < curr[i]) return false
        }
        return false
    }

    /**
     * Kiểm tra bản phát hành mới nhất từ GitHub Releases API.
     */
    suspend fun checkForUpdates(context: Context): OtaCheckResult = withContext(Dispatchers.IO) {
        val repo = getConfiguredRepo(context)
        val currentVer = BuildConfig.VERSION_NAME

        if (repo.isBlank() || !repo.contains("/") || repo.startsWith("owner/")) {
            return@withContext OtaCheckResult.Error("Chưa cấu hình địa chỉ GitHub Repo hợp lệ ($repo).")
        }

        var conn: HttpURLConnection? = null
        try {
            val apiUrl = URL("https://api.github.com/repos/$repo/releases")
            conn = (apiUrl.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 10000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "Walkman-Signature-OTA/${BuildConfig.VERSION_NAME}")
            }

            val code = conn.responseCode
            if (code == 404) {
                return@withContext OtaCheckResult.Error("Không tìm thấy mục Releases trên kho GitHub: $repo")
            }
            if (code !in 200..299) {
                return@withContext OtaCheckResult.Error("Máy chủ GitHub trả về mã lỗi HTTP $code")
            }

            val responseText = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val releasesArr = JSONArray(responseText)
            if (releasesArr.length() == 0) {
                return@withContext OtaCheckResult.UpToDate(currentVer)
            }

            // Tìm bản Release đầu tiên có đính kèm file .apk
            for (i in 0 until releasesArr.length()) {
                val relObj = releasesArr.getJSONObject(i)
                if (relObj.optBoolean("draft", false)) continue

                val tagName = relObj.optString("tag_name", "")
                val relName = relObj.optString("name", tagName)
                val body = relObj.optString("body", "Cải thiện hiệu năng giải mã DSD và nâng cấp giao diện.")
                val published = relObj.optString("published_at", "").take(10)

                val assets = relObj.optJSONArray("assets") ?: JSONArray()
                var apkUrl = ""
                var apkSize = 0L

                for (a in 0 until assets.length()) {
                    val assetObj = assets.getJSONObject(a)
                    val assetName = assetObj.optString("name", "")
                    if (assetName.endsWith(".apk", ignoreCase = true)) {
                        apkUrl = assetObj.optString("browser_download_url", "")
                        apkSize = assetObj.optLong("size", 0L)
                        break
                    }
                }

                if (apkUrl.isNotBlank()) {
                    val candidateVersionStr = if (tagName.any { it.isDigit() }) tagName else relName
                    val cleanRemoteVer = Regex("""\d+\.\d+(?:\.\d+)?""").find(candidateVersionStr)?.value ?: candidateVersionStr

                    return@withContext if (isRemoteVersionNewer(currentVer, cleanRemoteVer)) {
                        OtaCheckResult.UpdateAvailable(
                            OtaReleaseInfo(
                                versionTag = tagName,
                                cleanVersion = cleanRemoteVer,
                                releaseTitle = relName.ifBlank { "Walkman $cleanRemoteVer" },
                                changelog = body.ifBlank { "• Tối ưu hóa bộ lọc DSD 1024-Tap Kaiser FIR.\n• Cải thiện chuyển động vật lý băng Cassette." },
                                apkDownloadUrl = apkUrl,
                                apkSizeBytes = apkSize,
                                publishedAt = published
                            )
                        )
                    } else {
                        OtaCheckResult.UpToDate(currentVer)
                    }
                }
            }

            return@withContext OtaCheckResult.UpToDate(currentVer)
        } catch (e: Exception) {
            return@withContext OtaCheckResult.Error("Lỗi kết nối mạng: ${e.localizedMessage ?: e.javaClass.simpleName}")
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }

    /**
     * Tải file APK về bộ nhớ đệm với callback tiến trình % và tự động kiểm tra tính toàn vẹn.
     */
    suspend fun downloadUpdateApk(
        context: Context,
        release: OtaReleaseInfo,
        onProgress: (downloadedBytes: Long, totalBytes: Long, percent: Int) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val cacheDir = context.externalCacheDir ?: context.cacheDir
            // Xóa các bản APK cập nhật cũ trong cache
            cacheDir.listFiles()?.forEach { f ->
                if (f.name.startsWith("Walkman_Update_") && f.name.endsWith(".apk")) {
                    try { f.delete() } catch (_: Throwable) {}
                }
            }

            val targetFile = File(cacheDir, "Walkman_Update_${release.cleanVersion}.apk")
            var currentUrl = release.apkDownloadUrl
            var redirectCount = 0

            // Xử lý chuyển hướng 302 của GitHub Releases CDN (objects.githubusercontent.com)
            while (redirectCount < 5) {
                conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 15000
                    readTimeout = 20000
                    setRequestProperty("User-Agent", "Walkman-Signature-OTA/${BuildConfig.VERSION_NAME}")
                }
                val status = conn.responseCode
                if (status in 301..308) {
                    val nextUrl = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (nextUrl.isNullOrBlank()) break
                    currentUrl = nextUrl
                    redirectCount++
                } else {
                    break
                }
            }

            val finalConn = conn ?: return@withContext Result.failure(Exception("Không thể mở kết nối tải file."))
            if (finalConn.responseCode !in 200..299) {
                return@withContext Result.failure(Exception("Lỗi tải file APK (HTTP ${finalConn.responseCode})"))
            }

            val totalBytes = finalConn.contentLengthLong.takeIf { it > 0L } ?: release.apkSizeBytes.coerceAtLeast(1L)
            var downloaded = 0L

            finalConn.inputStream.use { input ->
                FileOutputStream(targetFile).use { output ->
                    val buffer = ByteArray(16384)
                    var read: Int
                    var lastReportedPercent = -1
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloaded += read
                        val pct = if (totalBytes > 0L) ((downloaded * 100L) / totalBytes).toInt().coerceIn(0, 100) else 0
                        if (pct != lastReportedPercent) {
                            lastReportedPercent = pct
                            onProgress(downloaded, totalBytes, pct)
                        }
                    }
                    output.flush()
                }
            }

            if (targetFile.length() < 100_000L) {
                return@withContext Result.failure(Exception("File APK tải về bị lỗi hoặc không đầy đủ."))
            }

            Result.success(targetFile)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }

    /**
     * Kiểm tra quyền cài đặt ứng dụng từ nguồn không xác định (Android 8.0+) và mở trình cài đặt APK qua FileProvider.
     */
    fun canRequestPackageInstalls(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    fun triggerApkInstallation(context: Context, apkFile: File): Result<Unit> {
        return try {
            if (!canRequestPackageInstalls(context)) {
                openInstallPermissionSettings(context)
                return Result.failure(Exception("Vui lòng cấp quyền 'Cho phép cài đặt ứng dụng' rồi bấm Cài đặt lại."))
            }

            val apkUri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(installIntent)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
