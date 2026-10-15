package net.berkchy.zerochan.core.net

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Rolling file log written to `Android/data/<pkg>/files/logs/zerochan.log` so it can be read
 * straight off the device with `adb` or a file manager, no permissions required.
 */
object AppLog {

    private const val FILE_NAME = "zerochan.log"
    private const val MAX_BYTES = 512L * 1024L
    private const val KEEP_BYTES = 128L * 1024L

    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val lock = Any()

    @Volatile
    private var dir: File? = null

    @Volatile
    private var enabled = true

    fun init(context: Context) {
        val target = File(context.getExternalFilesDir(null) ?: context.filesDir, "logs")
        if (!target.exists()) target.mkdirs()
        dir = target
        // keep the previous log around instead of starting from scratch every launch
        rotateIfNeeded()
    }

    fun path(context: Context): String = logFile(context).absolutePath

    fun logFile(context: Context): File = File(dir ?: File(context.filesDir, "logs"), FILE_NAME)

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    fun isEnabled(): Boolean = enabled

    fun d(tag: String, message: String) = write("I", tag, message, null)

    fun w(tag: String, message: String, error: Throwable? = null) = write("W", tag, message, error)

    fun e(tag: String, message: String, error: Throwable? = null) = write("E", tag, message, error)

    fun section(title: String) = write("I", "----", "======== $title ========", null)

    fun clear(context: Context) {
        synchronized(lock) { logFile(context).delete() }
    }

    fun read(context: Context): String = synchronized(lock) {
        val file = logFile(context)
        if (!file.exists()) "" else runCatching { file.readText() }.getOrDefault("")
    }

    private fun write(level: String, tag: String, message: String, error: Throwable?) {
        if (!enabled) return
        val time = stamp.format(Date())
        val stack = error?.let {
            StringWriter().also { sw -> PrintWriter(sw).use { pw -> it.printStackTrace(pw) } }.toString()
        }.orEmpty()
        val line = buildString {
            append(time).append(' ').append(level).append('/').append(tag).append(": ").append(message)
            if (stack.isNotEmpty()) append('\n').append(stack.trimEnd())
            append('\n')
        }
        val target = dir ?: return
        synchronized(lock) {
            runCatching {
                val file = File(target, FILE_NAME)
                if (!file.exists()) target.mkdirs()
                if (file.length() > MAX_BYTES) rotateIfNeeded()
                file.appendText(line)
            }
        }
    }

    private fun rotateIfNeeded() {
        val target = dir ?: return
        runCatching {
            val file = File(target, FILE_NAME)
            if (!file.exists() || file.length() <= MAX_BYTES) return
            val previous = File(target, "zerochan.previous.log")
            previous.delete()
            file.copyTo(previous, overwrite = true)
            val bytes = file.readBytes()
            file.writeBytes(bytes.copyOfRange((bytes.size - KEEP_BYTES.toInt()).coerceAtLeast(0), bytes.size))
        }
    }
}