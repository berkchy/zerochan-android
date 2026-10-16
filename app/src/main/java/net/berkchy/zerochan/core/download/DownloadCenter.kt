package net.berkchy.zerochan.core.download

import android.content.ContentValues
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import net.berkchy.zerochan.core.data.DownloadEntity
import net.berkchy.zerochan.core.data.DownloadStatus
import net.berkchy.zerochan.core.data.LibraryDao
import net.berkchy.zerochan.core.model.PostDetail
import net.berkchy.zerochan.core.net.AppLog
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

data class QueueSnapshot(
    val running: Int = 0,
    val queued: Int = 0,
    val maxConcurrent: Int = 2,
    val pausedForWifi: Boolean = false,
)

/**
 * The download queue. Lives in the application object so it survives the activity and keeps
 * running while the app sits in the background; the foreground service only exists to keep the
 * process alive and to host the notification.
 */
class DownloadCenter(
    context: Context,
    private val dao: LibraryDao,
    private val client: OkHttpClient,
    private val settings: SettingsStore,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(QueueSnapshot())
    val state: StateFlow<QueueSnapshot> = _state.asStateFlow()

    private val running = mutableSetOf<Long>()
    private var pump: Job? = null

    /** Monotonic tick so the service can refresh the notification once a second. */
    private val _tick = MutableStateFlow(0L)
    val tick: StateFlow<Long> = _tick.asStateFlow()

    fun startTicker(scope: CoroutineScope) {
        scope.launch {
            while (true) {
                delay(1000)
                _tick.value = System.currentTimeMillis()
            }
        }
    }

    suspend fun enqueue(detail: PostDetail, thumbnail: String, tag: String) {
        val url = detail.full.ifBlank { detail.large }
        val fileName = buildFileName(detail)
        dao.upsertDownload(
            DownloadEntity(
                id = detail.id,
                postUrl = detail.postUrl,
                downloadUrl = url,
                fileName = fileName,
                thumbnail = thumbnail.ifBlank { detail.medium },
                tag = tag.ifBlank { detail.displayName },
                width = detail.width,
                height = detail.height,
                totalBytes = detail.sizeBytes,
                status = DownloadStatus.QUEUED.name,
            )
        )
        AppLog.d(TAG, "queued ${detail.id} ($fileName)")
        pump()
        DownloadService.launch(appContext)
    }

    suspend fun cancel(id: Long) {
        dao.updateStatus(id, DownloadStatus.CANCELLED.name)
        running.remove(id)
        AppLog.d(TAG, "cancelled $id")
        pump()
    }

    suspend fun retry(id: Long) {
        dao.updateStatus(id, DownloadStatus.QUEUED.name)
        pump()
    }

    suspend fun remove(id: Long) {
        dao.removeDownload(id)
        pump()
    }

    fun pump() {
        if (pump?.isActive == true) return
        pump = scope.launch { drain() }
    }

    /** Drops finished rows from the notification shade. */
    suspend fun dismissFinished() = Unit

    /**
     * Fills every free slot with the oldest queued item, then returns. Each transfer re-triggers
     * the pump when it finishes, so the queue keeps draining while the process lives.
     */
    private suspend fun drain() {
        val prefs = settings.settings.first()
        val limit = prefs.maxConcurrentDownloads

        if (prefs.wifiOnly && !onWifi()) {
            dao.pending().filter { it.state == DownloadStatus.RUNNING }
                .forEach { dao.updateStatus(it.id, DownloadStatus.QUEUED.name) }
            running.clear()
            publish(limit)
            return
        }

        val queued = dao.pending().filter { it.state == DownloadStatus.QUEUED }
        val slots = limit - running.size
        if (slots <= 0 || queued.isEmpty()) {
            publish(limit)
            return
        }

        queued.take(slots).forEach { item ->
            running += item.id
            publish(limit)
            scope.launch {
                perform(item)
                running -= item.id
                publish(limit)
                pump()
            }
        }
        publish(limit)
    }

    private suspend fun perform(item: DownloadEntity) {
        if (item.downloadUrl.isBlank()) {
            dao.updateStatus(item.id, DownloadStatus.FAILED.name)
            return
        }
        dao.updateProgress(item.id, 0, item.totalBytes, 0, DownloadStatus.RUNNING.name)
        AppLog.d(TAG, "downloading ${item.id} from ${item.downloadUrl}")

        val dir = appContext.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            ?: appContext.filesDir
        if (!dir.exists()) dir.mkdirs()
        val target = File(dir, item.fileName)

        try {
            val request = Request.Builder().url(item.downloadUrl).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    dao.updateStatus(item.id, DownloadStatus.FAILED.name)
                    AppLog.w(TAG, "http ${response.code} for ${item.id}")
                    return
                }
                val body = response.body ?: run {
                    dao.updateStatus(item.id, DownloadStatus.FAILED.name)
                    return
                }
                val total = if (body.contentLength() > 0) body.contentLength() else item.totalBytes

                var lastNotify = 0L
                var lastBytes = 0L
                var lastTime = System.currentTimeMillis()

                target.outputStream().use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var read: Int
                        var written = 0L
                        while (input.read(buffer).also { read = it } != -1) {
                            out.write(buffer, 0, read)
                            written += read
                            val now = System.currentTimeMillis()
                            if (now - lastNotify >= 400) {
                                val span = ((now - lastTime).coerceAtLeast(1)).toDouble() / 1000.0
                                val speed = ((written - lastBytes) / span).toLong().coerceAtLeast(0)
                                dao.updateProgress(item.id, written, total, speed, DownloadStatus.RUNNING.name)
                                lastNotify = now
                                lastBytes = written
                                lastTime = now
                            }
                        }
                    }
                }

                val uri = publish(target)
                dao.upsertDownload(
                    item.copy(
                        filePath = target.absolutePath,
                        localUri = uri.toString(),
                        totalBytes = total,
                        downloadedBytes = target.length(),
                        bytesPerSecond = 0,
                        status = DownloadStatus.DONE.name,
                        finishedAt = System.currentTimeMillis(),
                    )
                )
                AppLog.d(TAG, "saved ${item.id} -> ${target.name} (${target.length()} bytes)")
            }
        } catch (e: Exception) {
            target.delete()
            dao.updateStatus(item.id, DownloadStatus.FAILED.name)
            AppLog.e(TAG, "download ${item.id} failed", e)
        }
    }

    private suspend fun publish(limit: Int) {
        val pending = dao.pending()
        _state.value = QueueSnapshot(
            running = pending.count { it.state == DownloadStatus.RUNNING },
            queued = pending.count { it.state == DownloadStatus.QUEUED },
            maxConcurrent = limit,
            pausedForWifi = false,
        )
    }

    private fun onWifi(): Boolean {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun buildFileName(detail: PostDetail): String {
        val extension = detail.full.substringAfterLast('.', "jpg").substringBefore('?')
            .let { if (it.isBlank() || it.length > 5) "jpg" else it }
        val base = (detail.displayName.ifBlank { "zerochan" })
            .replace(Regex("[^A-Za-z0-9 _-]"), "")
            .trim().replace(' ', '_').take(60).ifBlank { "image" }
        return "${base}_${detail.id}.$extension"
    }

    private fun publish(file: File): Uri {
        val resolver = appContext.contentResolver
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Images.Media.MIME_TYPE, mimeOf(file))
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/Zerochan",
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return FileProvider.getUriForFile(appContext, uriAuthority(), file)
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                null,
                null,
            )
            uri
        } else {
            @Suppress("DEPRECATION")
            FileProvider.getUriForFile(appContext, uriAuthority(), file)
        }
    }

    private fun uriAuthority() = "${appContext.packageName}.fileprovider"

    private fun mimeOf(file: File): String = when (file.extension.lowercase()) {
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "avif" -> "image/avif"
        "jpeg", "jpg" -> "image/jpeg"
        else -> "image/*"
    }

    private companion object {
        const val TAG = "downloads"
    }
}