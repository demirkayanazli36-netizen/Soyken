package com.example.data.network

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException

/**
 * Media3 [HttpDataSource] backed by [OkHttpClient] + [CloudflareDns] (`1dot1dot1dot1.cloudflare-dns.com`)
 * so all ExoPlayer HLS (.m3u8), MPEG-TS (.ts), DASH (.mpd), progressive VOD (.mp4/.mkv), and
 * 301/302 CDN redirect connections resolve through `1dot1dot1dot1.cloudflare-dns.com` instead
 * of Android's default `HttpURLConnection` system DNS.
 */
@OptIn(UnstableApi::class)
class CloudflareOkHttpDataSource(
    private val client: OkHttpClient,
    private val userAgent: String?,
    private val defaultRequestProperties: HttpDataSource.RequestProperties
) : BaseDataSource(/* isNetwork = */ true), HttpDataSource {

    class Factory(
        connectTimeoutMs: Long = 4_500L,
        readTimeoutMs: Long = 10_000L
    ) : HttpDataSource.Factory {

        private val sharedClient: OkHttpClient = CloudflareDns.createHttpClient(
            connectTimeoutMs = connectTimeoutMs,
            readTimeoutMs = readTimeoutMs
        )
        private val defaultRequestProperties = HttpDataSource.RequestProperties()

        @Volatile
        private var userAgent: String? = null

        fun setUserAgent(userAgent: String?): Factory {
            this.userAgent = userAgent
            return this
        }

        override fun setDefaultRequestProperties(
            defaultRequestProperties: Map<String, String>
        ): HttpDataSource.Factory {
            this.defaultRequestProperties.clearAndSet(defaultRequestProperties)
            return this
        }

        override fun createDataSource(): HttpDataSource {
            return CloudflareOkHttpDataSource(
                client = sharedClient,
                userAgent = userAgent,
                defaultRequestProperties = defaultRequestProperties
            )
        }
    }

    private val requestProperties = HttpDataSource.RequestProperties()

    private var dataSpec: DataSpec? = null
    private var response: Response? = null
    private var responseByteStream: InputStream? = null
    private var resolvedUri: Uri? = null
    private var opened = false
    private var bytesToRead: Long = 0L
    private var bytesRead: Long = 0L

    override fun getUri(): Uri? = resolvedUri

    override fun getResponseCode(): Int = response?.code ?: -1

    override fun getResponseHeaders(): Map<String, List<String>> {
        return response?.headers?.toMultimap() ?: emptyMap()
    }

    override fun setRequestProperty(name: String, value: String) {
        requestProperties.set(name, value)
    }

    override fun clearRequestProperty(name: String) {
        requestProperties.remove(name)
    }

    override fun clearAllRequestProperties() {
        requestProperties.clear()
    }

    override fun open(dataSpec: DataSpec): Long {
        this.dataSpec = dataSpec
        this.bytesRead = 0L
        this.bytesToRead = 0L
        transferInitializing(dataSpec)

        val urlString = dataSpec.uri.toString()

        // OkHttp's configured CloudflareDns resolves domain names automatically (with 0ms cache
        // and 0ms IP-literal fast-path) without blocking segment opens on extra handshakes.
        CloudflareDns.warmUpAsync()

        val httpUrl = urlString.toHttpUrlOrNull()
            ?: throw HttpDataSource.HttpDataSourceException(
                "Malformed stream URL: $urlString",
                dataSpec,
                PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK,
                HttpDataSource.HttpDataSourceException.TYPE_OPEN
            )

        val requestBuilder = Request.Builder().url(httpUrl)

        val mergedHeaders = LinkedHashMap<String, String>()
        mergedHeaders.putAll(defaultRequestProperties.snapshot)
        mergedHeaders.putAll(dataSpec.httpRequestHeaders)
        mergedHeaders.putAll(requestProperties.snapshot)

        for ((key, value) in mergedHeaders) {
            requestBuilder.header(key, value)
        }

        if (!userAgent.isNullOrBlank() &&
            mergedHeaders.keys.none { it.equals("User-Agent", ignoreCase = true) }
        ) {
            requestBuilder.header("User-Agent", userAgent)
        }

        val position = dataSpec.position
        val length = dataSpec.length
        val hasByteRange = !(position == 0L && length == C.LENGTH_UNSET.toLong())
        if (hasByteRange) {
            val rangeValue = if (length != C.LENGTH_UNSET.toLong()) {
                "bytes=$position-${position + length - 1}"
            } else {
                "bytes=$position-"
            }
            requestBuilder.header("Range", rangeValue)
            // Prevent transparent gzip decompression from altering byte offsets during VOD seeking
            requestBuilder.header("Accept-Encoding", "identity")
        } else if (!dataSpec.isFlagSet(DataSpec.FLAG_ALLOW_GZIP)) {
            requestBuilder.header("Accept-Encoding", "identity")
        }

        val method = DataSpec.getStringForHttpMethod(dataSpec.httpMethod)
        val postBody = dataSpec.httpBody
        val requestBody = when {
            postBody != null -> postBody.toRequestBody(null)
            dataSpec.httpMethod == DataSpec.HTTP_METHOD_POST -> ByteArray(0).toRequestBody(null)
            else -> null
        }
        requestBuilder.method(method, requestBody)

        val executedResponse = try {
            client.newCall(requestBuilder.build()).execute()
        } catch (e: IOException) {
            throw HttpDataSource.HttpDataSourceException.createForIOException(
                e,
                dataSpec,
                HttpDataSource.HttpDataSourceException.TYPE_OPEN
            )
        }

        this.response = executedResponse
        this.resolvedUri = Uri.parse(executedResponse.request.url.toString())
        val responseBody = executedResponse.body
        val responseCode = executedResponse.code

        if (!executedResponse.isSuccessful) {
            if (responseCode == 416) {
                val documentSize = parseDocumentSizeFromContentRange(
                    executedResponse.headers["Content-Range"]
                )
                if (dataSpec.position == documentSize) {
                    opened = true
                    transferStarted(dataSpec)
                    return if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else 0L
                }
            }

            val errorSnippet = runCatching {
                responseBody?.byteStream()?.use { stream ->
                    val buf = ByteArray(2048)
                    val read = stream.read(buf)
                    if (read > 0) buf.copyOf(read) else ByteArray(0)
                } ?: ByteArray(0)
            }.getOrDefault(ByteArray(0))

            closeConnectionQuietly()

            val cause = if (responseCode == 416) {
                DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
            } else {
                null
            }
            throw HttpDataSource.InvalidResponseCodeException(
                responseCode,
                executedResponse.message,
                cause,
                executedResponse.headers.toMultimap(),
                dataSpec,
                errorSnippet
            )
        }

        val rawStream = responseBody?.byteStream() ?: run {
            closeConnectionQuietly()
            throw HttpDataSource.HttpDataSourceException(
                IOException("Empty HTTP response body"),
                dataSpec,
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                HttpDataSource.HttpDataSourceException.TYPE_OPEN
            )
        }
        this.responseByteStream = BufferedInputStream(rawStream, 128 * 1024)

        // If server ignored our Range request and returned 200 OK instead of 206, skip to requested offset
        val bytesToSkip = if (responseCode == 200 && dataSpec.position != 0L) dataSpec.position else 0L

        bytesToRead = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length
        } else {
            val contentLength = responseBody.contentLength()
            if (contentLength != -1L) (contentLength - bytesToSkip).coerceAtLeast(0L) else C.LENGTH_UNSET.toLong()
        }

        try {
            skipFully(bytesToSkip, dataSpec)
        } catch (e: HttpDataSource.HttpDataSourceException) {
            closeConnectionQuietly()
            throw e
        }

        opened = true
        transferStarted(dataSpec)
        return bytesToRead
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesToRead != C.LENGTH_UNSET.toLong()) {
            val bytesRemaining = bytesToRead - bytesRead
            if (bytesRemaining == 0L) {
                return C.RESULT_END_OF_INPUT
            }
        }

        val maxToRead = if (bytesToRead == C.LENGTH_UNSET.toLong()) {
            length
        } else {
            minOf(length.toLong(), bytesToRead - bytesRead).toInt()
        }

        val stream = responseByteStream ?: return C.RESULT_END_OF_INPUT
        val readCount = try {
            stream.read(buffer, offset, maxToRead)
        } catch (e: IOException) {
            val spec = dataSpec ?: DataSpec(Uri.EMPTY)
            throw HttpDataSource.HttpDataSourceException.createForIOException(
                e,
                spec,
                HttpDataSource.HttpDataSourceException.TYPE_READ
            )
        }

        if (readCount == -1) {
            return C.RESULT_END_OF_INPUT
        }

        bytesRead += readCount
        bytesTransferred(readCount)
        return readCount
    }

    override fun close() {
        if (opened) {
            opened = false
            transferEnded()
        }
        closeConnectionQuietly()
    }

    private fun skipFully(bytesToSkip: Long, dataSpec: DataSpec) {
        if (bytesToSkip <= 0L) return
        val stream = responseByteStream ?: return
        val skipBuffer = ByteArray(64 * 1024)
        var remaining = bytesToSkip
        while (remaining > 0L) {
            if (Thread.currentThread().isInterrupted) {
                throw HttpDataSource.HttpDataSourceException(
                    InterruptedIOException(),
                    dataSpec,
                    PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                    HttpDataSource.HttpDataSourceException.TYPE_OPEN
                )
            }
            val toRead = minOf(remaining, skipBuffer.size.toLong()).toInt()
            val read = try {
                stream.read(skipBuffer, 0, toRead)
            } catch (e: IOException) {
                throw HttpDataSource.HttpDataSourceException.createForIOException(
                    e,
                    dataSpec,
                    HttpDataSource.HttpDataSourceException.TYPE_OPEN
                )
            }
            if (read == -1) {
                throw HttpDataSource.HttpDataSourceException(
                    EOFException(),
                    dataSpec,
                    PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
                    HttpDataSource.HttpDataSourceException.TYPE_OPEN
                )
            }
            remaining -= read
            bytesTransferred(read)
        }
    }

    private fun parseDocumentSizeFromContentRange(contentRange: String?): Long {
        if (contentRange.isNullOrBlank()) return C.LENGTH_UNSET.toLong()
        val slashIdx = contentRange.lastIndexOf('/')
        if (slashIdx == -1 || slashIdx + 1 >= contentRange.length) return C.LENGTH_UNSET.toLong()
        return contentRange.substring(slashIdx + 1).trim().toLongOrNull() ?: C.LENGTH_UNSET.toLong()
    }

    private fun closeConnectionQuietly() {
        runCatching { responseByteStream?.close() }
        responseByteStream = null
        runCatching { response?.close() }
        response = null
    }
}
