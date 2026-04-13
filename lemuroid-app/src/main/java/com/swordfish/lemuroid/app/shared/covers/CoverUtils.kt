package com.swordfish.lemuroid.app.shared.covers

import android.content.Context
import android.widget.ImageView
import coil.ImageLoader
import java.util.Locale
import coil.disk.DiskCache
import coil.imageLoader
import coil.load
import coil.memory.MemoryCache
import coil.request.CachePolicy
import com.swordfish.lemuroid.common.drawable.TextDrawable
import com.swordfish.lemuroid.common.graphics.ColorUtils
import com.swordfish.lemuroid.lib.library.db.entity.Game
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient

object CoverUtils {
    /** Returns true when the device is flagged as a low-RAM device by the OS. */
    private fun isLowRamDevice(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        return am.isLowRamDevice
    }

    fun loadCover(
        game: Game,
        imageView: ImageView?,
    ) {
        if (imageView == null) return

        imageView.load(game.coverFrontUrl, imageView.context.imageLoader) {
            val fallbackDrawable = getFallbackDrawable(game)
            fallback(fallbackDrawable)
            error(fallbackDrawable)
        }
    }

    fun buildImageLoader(applicationContext: Context): ImageLoader {
        val lowRam = isLowRamDevice(applicationContext)
        val memoryCacheFraction = if (lowRam) 0.08 else 0.20
        val diskCacheFraction = if (lowRam) 0.10 else 0.20
        return ImageLoader.Builder(applicationContext)
            .diskCache(
                DiskCache.Builder()
                    .directory(applicationContext.cacheDir.resolve("image_cache"))
                    .maxSizePercent(diskCacheFraction)
                    .build(),
            )
            .memoryCache {
                MemoryCache.Builder(applicationContext)
                    .maxSizePercent(memoryCacheFraction)
                    .build()
            }
            .okHttpClient {
                OkHttpClient.Builder()
                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .addNetworkInterceptor(ThrottleFailedThumbnailsInterceptor)
                    .build()
            }
            .crossfade(true)
            .interceptorDispatcher(Dispatchers.IO)
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .respectCacheHeaders(false)
            .build()
    }

    fun getFallbackDrawable(game: Game) = TextDrawable(computeTitle(game), computeColor(game))

    fun getFallbackRemoteUrl(game: Game): String {
        val color = Integer.toHexString(computeColor(game)).substring(2)
        val title = computeTitle(game)
        return "https://fakeimg.pl/512x512/$color/fff/?font=bebas&text=$title"
    }

    private fun computeTitle(game: Game): String {
        val sanitizedName =
            game.title
                .replace(Regex("\\(.*\\)"), "")

        return sanitizedName.asSequence()
            .filter { it.isDigit() or it.isUpperCase() or (it == '&') }
            .take(3)
            .joinToString("")
            .ifBlank { game.title.first().toString() }
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
    }

    private fun computeColor(game: Game): Int {
        return ColorUtils.randomColor(game.title)
    }
}
