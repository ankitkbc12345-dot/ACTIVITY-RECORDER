package com.example.updates

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.example.BuildConfig
import com.example.connectivity.UpdateCheckResult
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

sealed interface ApkDownloadState {
  data object Idle : ApkDownloadState

  data class Downloading(
    val progressPercent: Int,
    val downloadedBytes: Long,
    val totalBytes: Long,
  ) : ApkDownloadState

  data class PermissionRequired(
    val apkFilePath: String,
    val versionLabel: String,
  ) : ApkDownloadState

  data class ReadyToInstall(
    val apkFilePath: String,
    val versionLabel: String,
  ) : ApkDownloadState

  data class Failed(
    val message: String,
  ) : ApkDownloadState
}

data class GitHubReleaseInfo(
  val tagName: String,
  val releaseName: String,
  val versionName: String,
  val versionCode: Int,
  val apkDownloadUrl: String,
  val apkAssetName: String,
  val apkSizeBytes: Long,
  val releaseNotes: String,
)

object GitHubReleaseParser {

  private val VERSION_CODE_REGEX =
    Regex("""(?:versionCode|build)\s*[:=#-]?\s*(\d+)""", RegexOption.IGNORE_CASE)
  private val TAG_BUILD_SUFFIX_REGEX = Regex("""\+b?(\d+)$""", RegexOption.IGNORE_CASE)

  fun resolveEndpointUrl(
    configuredUrl: String,
    defaultRepoSlug: String = BuildConfig.GITHUB_REPO,
  ): String {
    val trimmed = configuredUrl.trim()
    if (trimmed.isBlank()) {
      return "https://api.github.com/repos/${defaultRepoSlug.trim('/')}/releases/latest"
    }
    if (trimmed.startsWith("https://api.github.com/repos/")) {
      return if (trimmed.contains("/releases")) trimmed else "${trimmed.trimEnd('/')}/releases/latest"
    }
    if (trimmed.startsWith("https://github.com/")) {
      val path = trimmed.removePrefix("https://github.com/").trim('/')
      val parts = path.split('/').filter { it.isNotBlank() }
      if (parts.size >= 2) {
        return "https://api.github.com/repos/${parts[0]}/${parts[1]}/releases/latest"
      }
    }
    if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://") && trimmed.contains('/')) {
      return "https://api.github.com/repos/${trimmed.trim('/')}/releases/latest"
    }
    return trimmed
  }

  fun parseGitHubReleaseJson(rawJson: String): GitHubReleaseInfo? {
    return try {
      val json = JSONObject(rawJson)
      // Support custom manifest JSON as well as GitHub Releases REST payload
      if (json.has("versionCode") && (json.has("apkUrl") || json.has("downloadUrl"))) {
        val code = json.optInt("versionCode", 0)
        val verName = cleanVersionName(json.optString("versionName", "1.0"))
        val apkUrl =
          json.optString("apkUrl").ifBlank { json.optString("downloadUrl") }
        val assetName = json.optString("assetName", "app-release.apk")
        val sizeBytes = json.optLong("apkSizeBytes", 0L)
        val notes = json.optString("releaseNotes", "")
        return GitHubReleaseInfo(
          tagName = "v$verName",
          releaseName = "v$verName",
          versionName = verName,
          versionCode = code,
          apkDownloadUrl = apkUrl,
          apkAssetName = assetName,
          apkSizeBytes = sizeBytes,
          releaseNotes = notes,
        )
      }

      val tagName = json.optString("tag_name", "").trim()
      val releaseName = json.optString("name", tagName).trim()
      val body = json.optString("body", "").trim()
      if (tagName.isBlank() && releaseName.isBlank()) return null

      val assets = json.optJSONArray("assets") ?: JSONArray()
      var bestAssetUrl = ""
      var bestAssetName = ""
      var bestAssetSize = 0L

      for (i in 0 until assets.length()) {
        val asset = assets.optJSONObject(i) ?: continue
        val name = asset.optString("name", "").trim()
        val url = asset.optString("browser_download_url", "").trim()
        val size = asset.optLong("size", 0L)
        if (name.endsWith(".apk", ignoreCase = true) && url.isNotBlank()) {
          bestAssetUrl = url
          bestAssetName = name
          bestAssetSize = size
          if (!name.contains("unsigned", ignoreCase = true)) {
            break
          }
        }
      }

      val versionName = cleanVersionName(tagName.ifBlank { releaseName })
      val versionCode = extractVersionCode(tagName, releaseName, bestAssetName, body)

      GitHubReleaseInfo(
        tagName = tagName.ifBlank { "v$versionName" },
        releaseName = releaseName.ifBlank { "v$versionName" },
        versionName = versionName,
        versionCode = versionCode,
        apkDownloadUrl = bestAssetUrl,
        apkAssetName = bestAssetName,
        apkSizeBytes = bestAssetSize,
        releaseNotes = body,
      )
    } catch (_: Exception) {
      null
    }
  }

  fun cleanVersionName(raw: String): String {
    val noPrefix = raw.trim().removePrefix("v").removePrefix("V")
    return noPrefix.substringBefore("+").substringBefore(" ").ifBlank { "1.0" }
  }

  fun extractVersionCode(
    tagName: String,
    releaseName: String,
    assetName: String,
    body: String,
  ): Int {
    VERSION_CODE_REGEX.find(body)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
    TAG_BUILD_SUFFIX_REGEX.find(tagName)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
    VERSION_CODE_REGEX.find(releaseName)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
    VERSION_CODE_REGEX.find(assetName)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
    val digits = cleanVersionName(tagName).split('.').mapNotNull { part ->
      part.takeWhile { it.isDigit() }.toIntOrNull()
    }
    if (digits.isNotEmpty()) {
      return when (digits.size) {
        1 -> digits[0]
        2 -> digits[0] * 100 + digits[1]
        else -> digits[0] * 10000 + digits[1] * 100 + digits[2]
      }
    }
    return 0
  }

  fun isNewerVersion(
    remoteVersionCode: Int,
    remoteVersionName: String,
    currentVersionCode: Int,
    currentVersionName: String,
  ): Boolean {
    if (remoteVersionCode > currentVersionCode) return true
    return compareSemanticVersions(remoteVersionName, currentVersionName) > 0
  }

  fun compareSemanticVersions(remote: String, current: String): Int {
    val rParts =
      cleanVersionName(remote)
        .split('.')
        .map { it.takeWhile { ch -> ch.isDigit() }.toIntOrNull() ?: 0 }
    val cParts =
      cleanVersionName(current)
        .split('.')
        .map { it.takeWhile { ch -> ch.isDigit() }.toIntOrNull() ?: 0 }
    val maxLen = maxOf(rParts.size, cParts.size)
    for (i in 0 until maxLen) {
      val r = rParts.getOrElse(i) { 0 }
      val c = cParts.getOrElse(i) { 0 }
      if (r != c) return r.compareTo(c)
    }
    return 0
  }
}

class AppUpdateManager(
  private val context: Context,
  private val isInternetAvailable: () -> Boolean,
  client: OkHttpClient? = null,
) {
  private val httpClient: OkHttpClient =
    client
      ?: OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

  suspend fun checkLatestRelease(configuredUrl: String): UpdateCheckResult =
    withContext(Dispatchers.IO) {
      if (!isInternetAvailable()) {
        return@withContext UpdateCheckResult.Offline
      }

      val targetUrl = GitHubReleaseParser.resolveEndpointUrl(configuredUrl)
      try {
        val request =
          Request.Builder()
            .url(targetUrl)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "DeviceRecorder-Android/${BuildConfig.VERSION_NAME}")
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
          if (response.code == 404) {
            return@withContext UpdateCheckResult.Checked(
              latestVersionName = BuildConfig.VERSION_NAME,
              latestVersionCode = BuildConfig.VERSION_CODE,
              updateAvailable = false,
              releaseNotes = "",
            )
          }

          if (!response.isSuccessful) {
            return@withContext UpdateCheckResult.Failed("HTTP ${response.code}: Unable to fetch latest release.")
          }

          val rawBody = response.body?.string().orEmpty()
          val parsed =
            GitHubReleaseParser.parseGitHubReleaseJson(rawBody)
              ?: return@withContext UpdateCheckResult.Failed("Invalid GitHub release metadata received.")

          val isNewer =
            GitHubReleaseParser.isNewerVersion(
              remoteVersionCode = parsed.versionCode,
              remoteVersionName = parsed.versionName,
              currentVersionCode = BuildConfig.VERSION_CODE,
              currentVersionName = BuildConfig.VERSION_NAME,
            )

          val canDownloadApk = parsed.apkDownloadUrl.isNotBlank()
          val updateAvailable = isNewer && canDownloadApk

          val notes =
            when {
              isNewer && !canDownloadApk ->
                "Release v${parsed.versionName} found, but no .apk asset was attached."
              else -> parsed.releaseNotes
            }

          UpdateCheckResult.Checked(
            latestVersionName = parsed.versionName,
            latestVersionCode = parsed.versionCode,
            updateAvailable = updateAvailable,
            releaseNotes = notes,
            apkDownloadUrl = parsed.apkDownloadUrl,
            apkAssetName = parsed.apkAssetName,
            apkSizeBytes = parsed.apkSizeBytes,
          )
        }
      } catch (e: Exception) {
        UpdateCheckResult.Failed(e.message ?: "Network error while checking updates.")
      }
    }

  suspend fun downloadApk(
    apkUrl: String,
    versionLabel: String,
    onProgress: (ApkDownloadState.Downloading) -> Unit,
  ): Result<File> =
    withContext(Dispatchers.IO) {
      if (apkUrl.isBlank()) {
        return@withContext Result.failure(IllegalArgumentException("Missing APK download URL."))
      }
      if (!isInternetAvailable()) {
        return@withContext Result.failure(IllegalStateException("Internet connection unavailable."))
      }

      val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
      updatesDir.listFiles()?.forEach { old ->
        if (old.isFile) old.delete()
      }

      val safeVersion = versionLabel.replace(Regex("[^a-zA-Z0-9._-]"), "_")
      val partFile = File(updatesDir, "device-recorder-$safeVersion.apk.part")
      val finalFile = File(updatesDir, "device-recorder-$safeVersion.apk")

      try {
        val request =
          Request.Builder()
            .url(apkUrl)
            .header("Accept", "application/octet-stream")
            .header("User-Agent", "DeviceRecorder-Android/${BuildConfig.VERSION_NAME}")
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
          if (!response.isSuccessful) {
            return@withContext Result.failure(
              IllegalStateException("Download failed (HTTP ${response.code}).")
            )
          }
          val body =
            response.body
              ?: return@withContext Result.failure(IllegalStateException("Empty APK response body."))
          val totalBytes = body.contentLength()
          var downloaded = 0L
          var lastReportedPercent = -2

          onProgress(
            ApkDownloadState.Downloading(
              progressPercent = if (totalBytes > 0L) 0 else -1,
              downloadedBytes = 0L,
              totalBytes = totalBytes,
            )
          )

          body.byteStream().use { input ->
            FileOutputStream(partFile).use { output ->
              val buffer = ByteArray(16 * 1024)
              while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                output.write(buffer, 0, read)
                downloaded += read
                val pct =
                  if (totalBytes > 0L) ((downloaded * 100L) / totalBytes).toInt().coerceIn(0, 100)
                  else -1
                if (pct != lastReportedPercent) {
                  lastReportedPercent = pct
                  onProgress(
                    ApkDownloadState.Downloading(
                      progressPercent = pct,
                      downloadedBytes = downloaded,
                      totalBytes = totalBytes,
                    )
                  )
                }
              }
              output.flush()
            }
          }
        }

        if (!partFile.exists() || partFile.length() == 0L) {
          partFile.delete()
          return@withContext Result.failure(IllegalStateException("Downloaded APK file is empty."))
        }

        if (finalFile.exists()) finalFile.delete()
        if (!partFile.renameTo(finalFile)) {
          partFile.copyTo(finalFile, overwrite = true)
          partFile.delete()
        }

        Result.success(finalFile)
      } catch (e: Exception) {
        partFile.delete()
        Result.failure(e)
      }
    }

  fun canInstallUnknownApps(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      context.packageManager.canRequestPackageInstalls()
    } else {
      true
    }
  }

  fun createUnknownSourcesSettingsIntent(): Intent {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${context.packageName}"),
      )
    } else {
      Intent(Settings.ACTION_SECURITY_SETTINGS)
    }
  }

  fun createInstallApkIntent(apkFile: File): Intent {
    val contentUri =
      FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        apkFile,
      )
    return Intent(Intent.ACTION_VIEW).apply {
      setDataAndType(contentUri, "application/vnd.android.package-archive")
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
  }
}
