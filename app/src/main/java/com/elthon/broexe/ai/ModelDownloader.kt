package com.elthon.broexe.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Streams a model file to a local path. Split out from [ModelStore] so the store's
 * verify/atomic-finalize logic can be exercised in unit tests without any network.
 *
 * Implementations must write only to [dest] and must honour coroutine cancellation.
 */
interface ModelDownloader {
    suspend fun download(url: String, dest: File, onProgress: (written: Long, total: Long) -> Unit)
}

/**
 * Production downloader. Streams the response body straight to disk (never into the
 * Java heap), reports progress, and aborts promptly when its coroutine is cancelled.
 */
class OkHttpModelDownloader(
    private val client: OkHttpClient = defaultClient(),
) : ModelDownloader {

    override suspend fun download(url: String, dest: File, onProgress: (Long, Long) -> Unit) =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw ModelDownloadException("HTTP ${response.code} for $url")
                }
                val body = response.body ?: throw ModelDownloadException("empty response body")
                val total = body.contentLength()
                body.byteStream().use { input ->
                    dest.outputStream().use { output ->
                        val buf = ByteArray(1 shl 16)
                        var written = 0L
                        var n: Int
                        while (input.read(buf).also { n = it } != -1) {
                            coroutineContext.ensureActive()
                            output.write(buf, 0, n)
                            written += n
                            onProgress(written, total)
                        }
                        output.flush()
                    }
                }
            }
        }

    companion object {
        private const val USER_AGENT = "bro-exe-android/model-downloader"

        /** Long timeouts: a several-hundred-MB file on mobile data is a slow, single request. */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }
}

/** Any failure while fetching or validating a model file. */
open class ModelDownloadException(message: String, cause: Throwable? = null) :
    java.io.IOException(message, cause)
