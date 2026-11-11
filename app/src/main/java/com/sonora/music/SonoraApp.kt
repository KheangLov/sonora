package com.sonora.music

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache

class SonoraApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
    }

    /** One shared, aggressively-cached image loader so artwork also works offline. */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient { AppGraph.okHttp }
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.25).build() }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("artwork"))
                .maxSizeBytes(300L * 1024 * 1024)
                .build()
        }
        .respectCacheHeaders(false)
        .crossfade(true)
        .build()
}
