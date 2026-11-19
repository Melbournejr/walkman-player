package com.sony.walkmanpro

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

data class OtaReleaseInfo(
    val versionTag: String,
    val cleanVersion: String,
    val releaseTitle: String,
    val changelog: String,
    val apkDownloadUrl: String,
    val apkSizeBytes: Long,
    val apkSha256: String,
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
    private const val KEY_BETA = "ota_include_prereleases"
    private const val MAX_METADATA_CHARS = 2 * 1024 * 1024
    internal const val MAX_APK_BYTES = 200L * 1024 * 1024
    private val cdnHosts = setOf("objects.githubusercontent.com", "release-assets.githubusercontent.com")
    private val sha256Pattern = Regex("[0-9a-f]{64}")

    private fun prefs(context: Context) = context.getSharedPreferences(PREF_OTA, Context.MODE_PRIVATE)

    fun isAutoCheckEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO_CHECK, true)

    fun setAutoCheckEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_CHECK, enabled).apply()
    }

    fun isBetaEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_BETA, false)

    fun setBetaEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_BETA, enabled).apply()
    }

    fun isRemoteVersionNewer(currentVersion: String, remoteVersion: String): Boolean =
        OtaVersion.parse(remoteVersion)?.let { remote ->
            OtaVersion.parse(currentVersion)?.let { remote > it }
        } ?: false

    suspend fun checkForUpdates(context: Context): OtaCheckResult = withContext(Dispatchers.IO) {
        val current = BuildConfig.VERSION_NAME
        try {
            val apiUrl = URL("https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases?per_page=30")
            val connection = connect(apiUrl, setOf("api.github.com"), followRedirects = false)
            val releases = try {
                val json = connection.inputStream.bufferedReader().use { reader ->
                    val buffer = CharArray(8192)
                    buildString {
                        while (true) {
                            val count = reader.read(buffer)
                            if (count < 0) break
                            check(length + count <= MAX_METADATA_CHARS) { "Phản hồi OTA quá lớn." }
                            append(buffer, 0, count)
                        }
                    }
                }
                JSONArray(json)
            } finally {
                connection.disconnect()
            }

            val beta = isBetaEnabled(context)
            var best: OtaReleaseInfo? = null
            var bestVersion = checkNotNull(OtaVersion.parse(current))
            var missingDigest = false
            for (i in 0 until releases.length()) {
                val release = releases.getJSONObject(i)
                if (release.optBoolean("draft")) continue
                val tag = release.optString("tag_name")
                val version = OtaVersion.parse(tag) ?: continue
                if ((!beta && (release.optBoolean("prerelease") || version.isPrerelease)) || version <= bestVersion) continue
                val assets = release.optJSONArray("assets") ?: continue
                for (j in 0 until assets.length()) {
                    val asset = assets.getJSONObject(j)
                    val name = asset.optString("name")
                    if (!name.endsWith(".apk", ignoreCase = true) || asset.optString("state") != "uploaded") continue
                    val url = asset.optString("browser_download_url")
                    if (!isTrustedAssetUrl(url, tag)) continue
                    val size = asset.optLong("size")
                    if (size !in 1..MAX_APK_BYTES) continue
                    val rawDigest = asset.optString("digest")
                    val digest = rawDigest.removePrefix("sha256:")
                    if (!rawDigest.startsWith("sha256:") || !sha256Pattern.matches(digest)) {
                        missingDigest = true
                        continue
                    }
                    best = OtaReleaseInfo(
                        tag, tag.removePrefix("v"),
                        release.optString("name").ifBlank { "Walkman $tag" },
                        release.optString("body").takeUnless { it == "null" }.orEmpty(),
                        url, size, digest, release.optString("published_at").take(10)
                    )
                    bestVersion = version
                    break
                }
            }
            best?.let { OtaCheckResult.UpdateAvailable(it) }
                ?: if (missingDigest) OtaCheckResult.Error("Bản cập nhật thiếu mã SHA-256 từ GitHub.")
                else OtaCheckResult.UpToDate(current)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            OtaCheckResult.Error(e.localizedMessage ?: "Không thể kiểm tra cập nhật.")
        }
    }

    internal fun isTrustedAssetUrl(rawUrl: String, tag: String): Boolean = runCatching {
        val url = URL(rawUrl)
        val prefix = "/${BuildConfig.GITHUB_REPO}/releases/download/$tag/"
        trustedHttps(url, setOf("github.com")) && url.query == null &&
            url.path.startsWith(prefix) && url.path.substringAfter(prefix).let {
                it.endsWith(".apk", ignoreCase = true) && '/' !in it &&
                    !it.contains("%2f", ignoreCase = true) && !it.contains("%5c", ignoreCase = true)
            }
    }.getOrDefault(false)

    private fun trustedHttps(url: URL, hosts: Set<String>) =
        url.protocol == "https" && url.host in hosts && url.port in setOf(-1, 443) &&
            url.userInfo == null && url.ref == null

    private fun connect(url: URL, hosts: Set<String>, followRedirects: Boolean): HttpsURLConnection {
        var next = url
        repeat(6) {
            check(trustedHttps(next, hosts)) { "Nguồn OTA không đáng tin cậy." }
            val connection = (next.openConnection() as HttpsURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("User-Agent", "Walkman-OTA/${BuildConfig.VERSION_NAME}")
                if (!followRedirects) setRequestProperty("Accept", "application/vnd.github+json")
            }
            var handedOff = false
            try {
                val status = connection.responseCode
                if (status == 200) {
                    handedOff = true
                    return connection
                }
                check(followRedirects && status in listOf(301, 302, 303, 307, 308)) { "Lỗi OTA HTTP $status." }
                val location = checkNotNull(connection.getHeaderField("Location"))
                next = URL(next, location)
                check(trustedHttps(next, cdnHosts)) { "Chuyển hướng OTA không đáng tin cậy." }
            } finally {
                // A successful connection belongs to the caller.
                if (!handedOff) connection.disconnect()
            }
        }
        error("OTA chuyển hướng quá nhiều lần.")
    }

    suspend fun downloadUpdateApk(
        context: Context,
        release: OtaReleaseInfo,
        onProgress: (downloadedBytes: Long, totalBytes: Long, percent: Int) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        var partial: File? = null
        try {
            check(isTrustedAssetUrl(release.apkDownloadUrl, release.versionTag))
            check(release.apkSizeBytes in 1..MAX_APK_BYTES && sha256Pattern.matches(release.apkSha256))
            val incoming = File(context.cacheDir, "ota/incoming")
            val ready = File(context.cacheDir, "ota/ready")
            for (directory in listOf(incoming, ready)) {
                check(directory.mkdirs() || directory.isDirectory) { "Không thể mở bộ nhớ đệm OTA." }
                directory.listFiles()?.filter { it.lastModified() < System.currentTimeMillis() - 86_400_000L }
                    ?.forEach { it.delete() }
            }
            val file = File.createTempFile("walkman-", ".apk", incoming)
            partial = file
            val connection = connect(URL(release.apkDownloadUrl), cdnHosts + "github.com", followRedirects = true)
            try {
                val size = release.apkSizeBytes
                check(connection.contentLengthLong in listOf(-1L, size)) { "Kích thước APK không khớp." }
                var downloaded = 0L
                var lastPercent = -1
                connection.inputStream.use { input ->
                    file.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            downloaded += count
                            check(downloaded <= size) { "APK lớn hơn kích thước đã công bố." }
                            output.write(buffer, 0, count)
                            val percent = (downloaded * 100 / size).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                withContext(Dispatchers.Main) { onProgress(downloaded, size, percent) }
                            }
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }
            verifyApk(context, file, release)
            currentCoroutineContext().ensureActive()
            val apk = File(ready, file.name)
            check(file.renameTo(apk)) { "Không thể lưu APK." }
            partial = null
            Result.success(apk)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            partial?.delete()
        }
    }

    internal fun matchesApkBytes(file: File, size: Long, sha256: String): Boolean {
        if (size !in 1..MAX_APK_BYTES || file.length() != size || !sha256Pattern.matches(sha256)) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            var bytesRead = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                bytesRead += count
                if (bytesRead > size) return false
                digest.update(buffer, 0, count)
            }
            if (bytesRead != size) return false
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) } == sha256
    }

    @Suppress("DEPRECATION")
    private fun verifyApk(context: Context, apk: File, release: OtaReleaseInfo) {
        check(matchesApkBytes(apk, release.apkSizeBytes, release.apkSha256)) { "SHA-256 hoặc kích thước APK không khớp." }
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val update = checkNotNull(pm.getPackageArchiveInfo(apk.absolutePath, flags)) { "APK không hợp lệ." }
        val installed = pm.getPackageInfo(context.packageName, flags)
        check(update.packageName == installed.packageName) { "APK thuộc ứng dụng khác." }
        check(versionCode(update) > versionCode(installed) &&
            update.versionName == release.cleanVersion &&
            isRemoteVersionNewer(installed.versionName.orEmpty(), release.cleanVersion)) { "Phiên bản APK không hợp lệ." }
        val currentSigners = signers(installed)
        check(currentSigners.isNotEmpty() && signers(update) == currentSigners) { "Chứng thư ký APK không khớp." }
    }

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo) = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo) =
        (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures).orEmpty().toSet()

    suspend fun triggerApkInstallation(context: Context, apkFile: File, release: OtaReleaseInfo): Result<Unit> {
        return try {
            withContext(Dispatchers.IO) { verifyApk(context, apkFile, release) }
            if (!context.packageManager.canRequestPackageInstalls()) {
                context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                return Result.failure(IllegalStateException("Cấp quyền cài đặt ứng dụng rồi bấm Cài đặt lại."))
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
            context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
