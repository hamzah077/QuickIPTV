package com.quickiptv.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy

/** التطبيق: إعداد تحميل الصور بأقل استهلاك للذاكرة (مهم للأجهزة الضعيفة). */
class App : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        instance = this
        Store.init(this)
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.15).build() }
            .diskCache { DiskCache.Builder().directory(cacheDir.resolve("img")).maxSizeBytes(80L * 1024 * 1024).build() }
            .allowRgb565(true)              // نصف الذاكرة للصور بدون شفافية
            .crossfade(false)               // بدون حركات = أسرع على الأجهزة الضعيفة
            .respectCacheHeaders(false)     // الصور تبقى محفوظة حتى لو السيرفر قال لا
            .diskCachePolicy(CachePolicy.ENABLED)
            .build()

    companion object {
        lateinit var instance: App
            private set
    }
}
