package com.libretv.tv

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Application entry point.
 *
 * Douban serves its poster images through a hotlink guard: a request without a
 * `Referer` header is answered with HTTP 418 and the image never arrives, which
 * would leave every poster on the home screen as an empty grey tile. Coil builds
 * its own OkHttp client by default, so the header has to be added here through a
 * custom [ImageLoaderFactory].
 */
class LibreTVApp : Application(), ImageLoaderFactory {

    override fun newImageLoader(): ImageLoader {
        val client = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request()
                val host = request.url.host
                val builder = request.newBuilder()
                if (host.endsWith("doubanio.com") || host.endsWith("douban.com")) {
                    builder.header("Referer", "https://movie.douban.com/")
                    builder.header("User-Agent", DOUBAN_UA)
                }
                chain.proceed(builder.build())
            }
            .build()

        return ImageLoader.Builder(this)
            .okHttpClient(client)
            .crossfade(true)
            .respectCacheHeaders(false)
            .build()
    }

    private companion object {
        const val DOUBAN_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    }
}
