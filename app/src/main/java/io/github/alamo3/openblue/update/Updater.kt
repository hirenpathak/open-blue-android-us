package io.github.alamo3.openblue.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import io.github.alamo3.openblue.BuildConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** What CI published as the newest build (version.json next to the APK in the "latest" release). */
@Serializable
data class Release(
    val versionCode: Int,
    val versionName: String,
    val commit: String = "",
    /** The commit's subject line: what changed. */
    val message: String = "",
    val sha256: String = "",
    val size: Long = 0,
) {
    val isNewer: Boolean get() = versionCode > BuildConfig.VERSION_CODE
}

/**
 * Updates the app from the rolling "latest" GitHub release: check, download, verify, install.
 * Android only installs it if it's signed with the same key as the installed app, so a
 * tampered download can't replace it. The first update asks you to confirm (and to allow
 * installs from Open Blue); after that Android lets the app update itself without asking.
 */
object Updater {
    // US fork: updates come from the fork's own releases. There is no rolling
    // "latest" release there yet, so the check quietly finds nothing instead
    // of offering the upstream Canada build.
    private const val BASE = "https://github.com/hirenpathak/open-blue-android-us/releases/download/latest/"
    const val ACTION_STATUS = "io.github.alamo3.openblue.UPDATE_STATUS"

    private val http = OkHttpClient.Builder().followRedirects(true).readTimeout(60, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }

    fun check(): Release {
        http.newCall(Request.Builder().url(BASE + "version.json").header("Cache-Control", "no-cache").build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("GitHub answered ${r.code}")
            return json.decodeFromString(Release.serializer(), r.body?.string() ?: throw IOException("Empty answer"))
        }
    }

    /** Downloads the APK (or reuses an already-downloaded, verified one). [progress] gets 0..1. */
    fun download(context: Context, release: Release, progress: (Float) -> Unit = {}): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val apk = File(dir, "OpenBlue-${release.versionCode}.apk")
        if (apk.exists() && verified(apk, release)) return apk
        dir.listFiles()?.forEach { it.delete() }
        val tmp = File(dir, "download.part")
        http.newCall(Request.Builder().url(BASE + "OpenBlue.apk").build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Download failed (${r.code})")
            val body = r.body ?: throw IOException("Empty download")
            val total = body.contentLength().takeIf { it > 0 } ?: release.size
            tmp.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(1 shl 16)
                    var got = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        got += n
                        if (total > 0) progress((got.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        }
        if (!verified(tmp, release)) { tmp.delete(); throw IOException("The download didn't match the published checksum") }
        if (!tmp.renameTo(apk)) throw IOException("Couldn't save the download")
        return apk
    }

    private fun verified(f: File, release: Release): Boolean {
        if (release.sha256.isBlank()) return f.length() > 0
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }.equals(release.sha256, ignoreCase = true)
    }

    /** Whether Android lets Open Blue install apps (asked once, in Settings). */
    fun allowedToInstall(context: Context) = context.packageManager.canRequestPackageInstalls()

    /** Hands the APK to Android's installer. The app closes when it's replaced. */
    fun install(context: Context, apk: File, release: Release) {
        UpdateState.noteInstalling(context, release)
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("OpenBlue.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val status = PendingIntent.getBroadcast(
                context, id,
                Intent(context, InstallReceiver::class.java).setAction(ACTION_STATUS),
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0),
            )
            session.commit(status.intentSender)
        }
    }
}
