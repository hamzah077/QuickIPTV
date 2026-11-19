package com.quickiptv.app

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

/** الحالة المشتركة بين الشاشات: الحساب الحالي وقائمة التشغيل للمشغّل. */
object Session {
    var api: Xtream? = null
    var userInfo: org.json.JSONObject? = null

    // قائمة القنوات اللي انفتح منها المشغّل (للتنقل فوق/تحت بالريموت)
    var playlist: List<Channel> = emptyList()
    var playIndex: Int = 0
    var playCategory: String = ""

    fun open(ctx: Context, acc: Account) {
        api = Xtream(acc, ctx.cacheDir)
        Store.currentId = acc.id
    }
}

/**
 * مشغّل خفيف للأجهزة الضعيفة:
 * - بداية سريعة (يبدأ بعد ثانية بفر)، وذاكرة بفر محدودة.
 * - فك الفيديو بقطع الجهاز، ولو فشل يرجع لبديل تلقائياً.
 */
@OptIn(UnstableApi::class)
fun buildPlayer(ctx: Context, live: Boolean): ExoPlayer {
    val http = DefaultHttpDataSource.Factory()
        .setUserAgent(Xtream.UA)
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(12000)
        .setReadTimeoutMs(20000)
    val load = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            if (live) 4000 else 15000,      // أقل بفر
            if (live) 20000 else 50000,     // أقصى بفر (ذاكرة محدودة)
            1000,                            // يبدأ التشغيل بعد ثانية
            if (live) 2000 else 3000,        // بعد التقطيع
        )
        .setPrioritizeTimeOverSizeThresholds(true)
        .build()
    val renderers = DefaultRenderersFactory(ctx)
        .setEnableDecoderFallback(true)
        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
    return ExoPlayer.Builder(ctx, renderers)
        .setMediaSourceFactory(DefaultMediaSourceFactory(http))
        .setLoadControl(load)
        .build()
}
