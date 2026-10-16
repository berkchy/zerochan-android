package net.berkchy.zerochan

import android.app.Application
import android.os.Build
import androidx.room.Room
import coil.ImageLoader
import coil.ImageLoaderFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.berkchy.zerochan.core.data.ZerochanDatabase
import net.berkchy.zerochan.core.data.ZerochanRepository
import net.berkchy.zerochan.core.download.DownloadCenter
import net.berkchy.zerochan.core.download.DownloadService
import net.berkchy.zerochan.core.download.SettingsStore
import net.berkchy.zerochan.core.net.AppLog
import net.berkchy.zerochan.core.net.BotCheckInterceptor
import net.berkchy.zerochan.core.net.ZerochanClient
import net.berkchy.zerochan.core.net.withAvifFallback
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class ZerochanApp : Application(), ImageLoaderFactory {

    val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Created eagerly: Coil may build its singleton before `onCreate` finishes, and a lateinit
     * here would silently break every thumbnail.
     */
    private val imageClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(600, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", BotCheckInterceptor.USER_AGENT)
                    .header("Referer", "https://www.zerochan.net/")
                    .build()
            )
        }
        .build()

    lateinit var database: ZerochanDatabase
        private set

    lateinit var settings: SettingsStore
        private set

    lateinit var downloadCenter: DownloadCenter
        private set

    lateinit var repository: ZerochanRepository
        private set

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .withAvifFallback()
        .okHttpClient { imageClient }
        .crossfade(true)
        .respectCacheHeaders(false)
        .build()

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        AppLog.section("start")
        AppLog.d(
            TAG,
            "package=$packageName version=${BuildConfig.VERSION_NAME} sdk=${Build.VERSION.SDK_INT}",
        )
        AppLog.d(TAG, "user-agent=${BotCheckInterceptor.USER_AGENT}")
        AppLog.d(TAG, "log file: ${AppLog.path(this)}")

        database = Room.databaseBuilder(this, ZerochanDatabase::class.java, "zerochan.db")
            .fallbackToDestructiveMigration()
            .build()

        settings = SettingsStore(this)
        downloadCenter = DownloadCenter(this, database.libraryDao(), imageClient, settings)
        downloadCenter.startTicker(serviceScope)

        repository = ZerochanRepository(
            client = ZerochanClient(this),
            downloader = downloadCenter,
            dao = database.libraryDao(),
            settings = settings,
            appContext = this,
        )

        // resume anything that was still in flight when the process was killed
        serviceScope.launch {
            val pending = database.libraryDao().pending()
            if (pending.isNotEmpty()) {
                AppLog.d(TAG, "resuming ${pending.size} pending download(s)")
                DownloadService.launch(this@ZerochanApp)
            }
        }
    }

    private companion object {
        const val TAG = "app"
    }
}