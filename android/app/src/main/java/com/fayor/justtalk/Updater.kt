package com.fayor.justtalk

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Downloads an update once and keeps it until installation, even if the installer is dismissed.
 */
object Updater {
    /** null = idle, 0..1 = downloading */
    var progress by mutableStateOf<Float?>(null)
        private set

    private val http by lazy { OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build() }

    private fun updateFile(ctx: Context, url: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
        val name = digest.joinToString("") { "%02x".format(it) }
        return File(File(ctx.filesDir, "updates"), "$name.apk")
    }

    private fun validUpdate(ctx: Context, file: File): Boolean {
        if (!file.isFile || file.length() == 0L) return false
        val info = runCatching { ctx.packageManager.getPackageArchiveInfo(file.absolutePath, 0) }.getOrNull() ?: return false
        return info.packageName == ctx.packageName && info.longVersionCode > BuildConfig.VERSION_CODE
    }

    suspend fun download(ctx: Context, url: String): File? = withContext(Dispatchers.IO) {
        val file = updateFile(ctx, url)
        if (validUpdate(ctx, file)) return@withContext file
        file.delete()
        val dir = file.parentFile!!.apply { mkdirs() }
        val temporary = File(dir, "${file.name.removeSuffix(".apk")}.part.apk")
        temporary.delete()
        progress = 0f
        val ok = runCatching {
            http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) error("http ${r.code}")
                val body = r.body!!
                val total = body.contentLength()
                temporary.outputStream().use { out ->
                    val input = body.byteStream()
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) progress = done.toFloat() / total
                    }
                }
            }
            if (!validUpdate(ctx, temporary)) error("Invalid update APK")
            if (!temporary.renameTo(file)) error("Cannot save update APK")
            dir.listFiles()?.filter { it != file }?.forEach { it.delete() }
        }.isSuccess
        progress = null
        if (ok) file else { temporary.delete(); null }
    }

    fun install(ctx: Context, file: File): Boolean = runCatching {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".updates", file)
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
}
