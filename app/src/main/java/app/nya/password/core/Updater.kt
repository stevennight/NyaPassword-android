package app.nya.password.core

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import app.nya.password.BuildConfig
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Updates from GitHub Releases (design doc §3.4): the release workflow
 * publishes `NyaPassword-Android_<version>.apk` and its `.sha256`. The APK is
 * downloaded into the cache, its SHA-256 checked, and its signing
 * certificate compared with the installed app's before the system installer
 * opens (the system would refuse a differently signed update anyway; checking
 * first gives a clear message and never hands it a foreign APK).
 */
object Updater {
    data class Release(val version: String, val apkUrl: String, val sha256Url: String?, val notes: String, val page: String)

    fun assetName(version: String) = "NyaPassword-Android_$version.apk"

    /** Parses `GET /repos/<repo>/releases/latest`; null without the expected APK asset. */
    fun parseRelease(json: String): Release? = runCatching {
        val o = PlainJson.parseToJsonElement(json) as JsonObject
        fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
        val version = s("tag_name")?.removePrefix("v") ?: return null
        val assets = (o["assets"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return null
        fun url(name: String) = assets.firstOrNull { (it["name"] as? JsonPrimitive)?.contentOrNull == name }
            ?.let { (it["browser_download_url"] as? JsonPrimitive)?.contentOrNull }
        val apk = url(assetName(version)) ?: return null
        Release(version, apk, url(assetName(version) + ".sha256"), s("body") ?: "", s("html_url") ?: "")
    }.getOrNull()

    /** a > b for MAJOR.MINOR.PATCH[-pre] (a prerelease is older than its release). */
    fun newer(a: String, b: String): Boolean {
        fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 } + listOf(0, 0, 0)
        val pa = parts(a)
        val pb = parts(b)
        for (i in 0 until 3) if (pa[i] != pb[i]) return pa[i] > pb[i]
        val preA = a.substringAfter('-', "")
        val preB = b.substringAfter('-', "")
        return when {
            preA.isEmpty() && preB.isNotEmpty() -> true
            preA.isNotEmpty() && preB.isNotEmpty() -> comparePre(preA, preB) > 0
            else -> false
        }
    }

    /** beta.2 < beta.10; numeric parts compare as numbers. */
    private fun comparePre(a: String, b: String): Int {
        val xa = a.split('.')
        val xb = b.split('.')
        for (i in 0 until maxOf(xa.size, xb.size)) {
            val p = xa.getOrNull(i) ?: return -1
            val q = xb.getOrNull(i) ?: return 1
            val c = if (p.toIntOrNull() != null && q.toIntOrNull() != null) p.toInt().compareTo(q.toInt()) else p.compareTo(q)
            if (c != 0) return c
        }
        return 0
    }

    /** The hash from a `sha256sum` line (`<64 hex>  <name>`). */
    fun parseSha256(text: String): String? =
        text.trim().split(Regex("\\s+")).firstOrNull()?.lowercase()?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }

    /** Latest release of the configured repository, or null. Blocking. */
    fun latest(repo: String = BuildConfig.UPDATE_REPO): Release? {
        val c = URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        try {
            if (c.responseCode != 200) return null
            return parseRelease(c.inputStream.bufferedReader().readText())
        } finally {
            c.disconnect()
        }
    }

    /** Downloads and checks the APK (SHA-256 and signing certificate). Blocking. */
    fun download(context: Context, r: Release, progress: (Long, Long) -> Unit): File {
        val shaUrl = r.sha256Url ?: throw IllegalStateException("发布里没有校验文件（.sha256），不安装")
        val want = parseSha256(URL(shaUrl).readText()) ?: throw IllegalStateException("校验文件格式不对")
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val apk = File(dir, assetName(r.version))
        val c = URL(r.apkUrl).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        val total = c.contentLengthLong
        val digest = MessageDigest.getInstance("SHA-256")
        c.inputStream.use { input ->
            apk.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    digest.update(buf, 0, n)
                    done += n
                    progress(done, total)
                }
            }
        }
        if (Origins.hex(digest.digest()) != want) {
            apk.delete()
            throw IllegalStateException("下载的安装包校验失败")
        }
        signatureProblem(context, apk)?.let {
            apk.delete()
            throw IllegalStateException(it)
        }
        return apk
    }

    /** Why [apk] must not be installed over this app, or null if it may. */
    fun signatureProblem(context: Context, apk: File): String? {
        val pm = context.packageManager
        val archive = archiveInfo(pm, apk.path) ?: return "无法读取安装包"
        if (archive.packageName != context.packageName) return "安装包不是 NyaPassword"
        val mine = Origins.signingCerts(context, context.packageName).map(Origins::hex).toSet()
        val theirs = certsOf(archive).map(Origins::hex).toSet()
        if (mine.isEmpty() || theirs.isEmpty()) return "无法读取签名证书"
        // the new APK's current signer must be one this app trusts (same key, or a rotation of it)
        if (theirs.none { it in mine }) return "安装包的签名与当前应用不同，已拒绝（可能被篡改）"
        return null
    }

    private fun archiveInfo(pm: PackageManager, path: String): PackageInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNATURES)
        }

    private fun certsOf(p: PackageInfo): List<ByteArray> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val si = p.signingInfo ?: return emptyList()
            (if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory).map { Origins.sha256(it.toByteArray()) }
        } else {
            @Suppress("DEPRECATION")
            p.signatures.orEmpty().map { Origins.sha256(it.toByteArray()) }
        }

    /** Whether the user allowed this app to install packages (Android 8+ asks per app). */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Opens the system installer for [apk]. */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
