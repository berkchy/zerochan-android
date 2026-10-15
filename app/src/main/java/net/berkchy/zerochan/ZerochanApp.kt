package net.berkchy.zerochan

import android.app.Application
import androidx.room.Room
import coil.ImageLoader
import coil.ImageLoaderFactory
import net.berkchy.zerochan.core.data.ZerochanDatabase
import net.berkchy.zerochan.core.data.ZerochanRepository
import net.berkchy.zerochan.core.download.ImageDownloader
import net.berkchy.zerochan.core.net.AppLog
import net.berkchy.zerochan.core.net.BotCheckInterceptor
import net.berkchy.zerochan.core.net.ZerochanClient
import net.berkchy.zerochan.core.net.withAvifFallback
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class ZerochanApp : Application(), ImageLoaderFactory {

    /**
     * Created eagerly: Coil may build its singleton before `onCreate` finishes, and a lateinit
     * here would silently break every thumbnail.
     */
    private val imageClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", BotCheckInterceptor.USER_AGENT)
                    .header("Referer", "https://www.zerochan.net/")
                    .build()
            )
        }
        .build()

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
            "package=$packageName version=${BuildConfig.VERSION_NAME} sdk=${android.os.Build.VERSION.SDK_INT}",
        )
        AppLog.d(TAG, "user-agent=${BotCheckInterceptor.USER_AGENT}")

        val db = Room.databaseBuilder(this, ZerochanDatabase::class.java, "zerochan.db")
            .fallbackToDestructiveMigration()
            .build()

        repository = ZerochanRepository(
            client = ZerochanClient(this),
            downloader = ImageDownloader(this, imageClient),
            dao = db.libraryDao(),
        )
        AppLog.d(TAG, "log file: ${AppLog.path(this)}")
    }

    private companion object {
        const val TAG = "app"
    }
}