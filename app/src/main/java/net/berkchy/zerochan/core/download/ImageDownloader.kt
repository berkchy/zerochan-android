package net.berkchy.zerochan.core.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.berkchy.zerochan.core.model.PostDetail
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

sealed interface DownloadState {
    data object Queued : DownloadState
    data class Running(val percent: Int) : DownloadState
    data class Done(val uri: Uri, val file: File, val bytes: Long) : DownloadState
    data class Failed(val reason: String) : DownloadState
}

class ImageDownloader(
    private val context: Context,
    private val client: OkHttpClient,
) {
    suspend fun download(detail: PostDetail, onProgress: (DownloadState) -> Unit): DownloadState =
        withContext(Dispatchers.IO) {
            onProgress(DownloadState.Queued)
            val url = detail.full.ifBlank { detail.large }
            if (url.isBlank()) return@withContext DownloadState.Failed("No download url")

            val dir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
                ?: context.filesDir
            if (!dir.exists()) dir.mkdirs()

            val extension = url.substringAfterLast('.', "jpg").substringBefore('?').ifBlank { "jpg" }
            val fileName = sanitize(detail.displayName.ifBlank { "zerochan" }) + "_${detail.id}.$extension"
            val target = File(dir, fileName)

            try {
                val request = Request.Builder().url(url).get().build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext DownloadState.Failed("HTTP ${response.code}")
                    val body = response.body ?: return@withContext DownloadState.Failed("Empty response")
                    val total = body.contentLength()
                    target.outputStream().use { out ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(16 * 1024)
                            var read: Int
                            var written = 0L
                            var lastPercent = -1
                            while (input.read(buffer).also { read = it } != -1) {
                                out.write(buffer, 0, read)
                                written += read
                                if (total > 0) {
                                    val percent = ((written * 100) / total).toInt()
                                    if (percent != lastPercent) {
                                        lastPercent = percent
                                        onProgress(DownloadState.Running(percent))
                                    }
                                }
                            }
                        }
                    }
                }

                val uri = publish(target)
                DownloadState.Done(uri, target, target.length())
            } catch (e: Exception) {
                target.delete()
                DownloadState.Failed(e.message ?: "Download failed")
            }
        }

    private fun publish(file: File): Uri {
        val resolver = context.contentResolver
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Images.Media.MIME_TYPE, mimeOf(file))
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Zerochan")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            uri
        } else {
            @Suppress("DEPRECATION")
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }
    }

    private fun mimeOf(file: File): String = when (file.extension.lowercase()) {
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "avif" -> "image/avif"
        "jpeg", "jpg" -> "image/jpeg"
        else -> "image/*"
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().replace(' ', '_').take(60).ifBlank { "image" }
}