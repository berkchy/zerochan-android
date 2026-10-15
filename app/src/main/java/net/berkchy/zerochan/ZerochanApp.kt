package net.berkchy.zerochan

import android.app.Application
import androidx.room.Room
import coil.ImageLoader
import coil.ImageLoaderFactory
import net.berkchy.zerochan.core.data.ZerochanDatabase
import net.berkchy.zerochan.core.data.ZerochanRepository
import net.berkchy.zerochan.core.download.ImageDownloader
import net.berkchy.zerochan.core.net.BotCheckInterceptor
import net.berkchy.zerochan.core.net.ZerochanClient
import net.berkchy.zerochan.core.net.withAvifFallback
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class ZerochanApp : Application(), ImageLoaderFactory {

    lateinit var repository: ZerochanRepository
        private set

    lateinit var imageClient: OkHttpClient
        private set

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .withAvifFallback()
        .okHttpClient { imageClient }
        .crossfade(true)
        .respectCacheHeaders(false)
        .build()

    override fun onCreate() {
        super.onCreate()

        imageClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", BotCheckInterceptor.USER_AGENT)
                    .header("Referer", "https://www.zerochan.net/")
                    .build()
                chain.proceed(request)
            }
            .build()

        val db = Room.databaseBuilder(this, ZerochanDatabase::class.java, "zerochan.db")
            .fallbackToDestructiveMigration()
            .build()

        repository = ZerochanRepository(
            client = ZerochanClient(this),
            downloader = ImageDownloader(this, imageClient),
            dao = db.libraryDao(),
        )
    }
}