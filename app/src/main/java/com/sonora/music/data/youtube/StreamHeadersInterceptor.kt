package com.sonora.music.data.youtube

import okhttp3.Interceptor
import okhttp3.Response
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper

/**
 * googlevideo.com stream URLs are bound to the client that requested them.
 * A mismatched User-Agent gets a 403, so we send the one that matches.
 * NewPipeExtractor's own helpers are used so this stays in sync when the library updates.
 */
class StreamHeadersInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.url.host.endsWith("googlevideo.com")) return chain.proceed(request)

        val url = request.url.toString()
        val client = request.url.queryParameter("c")?.uppercase().orEmpty()
        val builder = request.newBuilder()
        when {
            YoutubeParsingHelper.isVisionOsStreamingUrl(url) ->
                builder.header("User-Agent", YoutubeParsingHelper.getVisionOsUserAgent(null))
            YoutubeParsingHelper.isWebStreamingUrl(url) || client.startsWith("WEB") -> builder
                .header("User-Agent", NewPipeDownloader.USER_AGENT)
                .header("Origin", "https://www.youtube.com")
                .header("Referer", "https://www.youtube.com/")
            client.startsWith("ANDROID") -> builder.header("User-Agent", UA_ANDROID)
            client.startsWith("IOS") -> builder.header("User-Agent", UA_IOS)
            else -> builder.header("User-Agent", NewPipeDownloader.USER_AGENT)
        }
        return chain.proceed(builder.build())
    }

    private companion object {
        const val UA_ANDROID = "com.google.android.youtube/19.28.35 (Linux; U; Android 15; GB) gzip"
        const val UA_IOS = "com.google.ios.youtube/19.45.4 (iPhone16,2; U; CPU iOS 18_1_0 like Mac OS X;)"
    }
}
