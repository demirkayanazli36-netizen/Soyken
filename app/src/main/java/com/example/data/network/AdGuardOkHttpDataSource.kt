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
import androidx.media3.datasource.HttpUtil
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException

/**
 * Media3 [HttpDataSource] backed by [OkHttpClient] configured with [AdGuardDnsResolver] (`dns.adguard.com`).
 * Ensures every HLS (.m3u8), MPEG-TS (.ts), DASH (.mpd), progressive VOD (.mp4/.mkv), and 302 redirect
 * stream connection resolves hostnames via `dns.adguard.com` rather than the ISP's system DNS.
 */
@OptIn(UnstableApi::class)
class AdGuardOkHttpDataSource private constructor(
    private val client: OkHttpClient,
    private val userAgentProvider: () -> String?,
    private val defaultRequestProperties: HttpDataSource.RequestProperties
) : BaseDataSource(/* isNetwork = */ true), HttpDataSource {

    class Factory(
        private val client: OkHttpClient = AdGuardNetworkClient.streamingHttpClient
    ) : HttpDataSource.Factory {

        private val defaultRequestProperties = HttpDataSource.RequestProperties()

        @Volatile
        private var userAgent: String? = null

        fun setUserAgent(userAgent: String?): Factory {
            this.userAgent = userAgent
            return this
        }

        override fun setDefaultRequestProperties(
            defaultRequestProperties: Map<String, String>
        ): Factory {
            this.defaultRequestProperties.clearAndSet(defaultRequestProperties)
            return this
        }

        override fun createDataSource(): AdGuardOkHttpDataSource {
            return AdGuardOkHttpDataSource(
                client = client,
                userAgentProvider = { userAgent },
                defaultRequestProperties = defaultRequestProperties
            )
        }
    }

    private val requestProperties = HttpDataSource.RequestProperties()

    private var dataSpec: DataSpec? = null
    private var call: Call? = null
    private var response: Response? = null
    private var responseByteStream: InputStream? = null
    private var opened: Boolean = false
    private var bytesToRead: Long = 0L
    private var bytesRead: Long = 0L

    override fun getUri(): Uri? {
        return response?.request?.url?.toString()?.let { Uri.parse(it) }
    }

    override fun getResponseCode(): Int {
        return response?.code ?: -1
    }

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

        // Ensure dns.adguard.com connection is active before opening any M3U stream endpoint
        AdGuardDnsResolver.ensureConnectedBlocking()

        val request = buildOkHttpRequest(dataSpec)
        val activeCall = client.newCall(request)
        this.call = activeCall

        val resp = try {
            activeCall.execute()
        } catch (e: IOException) {
            closeConnectionQuietly()
            throw HttpDataSource.HttpDataSourceException.createForIOException(
                e,
                dataSpec,
                HttpDataSource.HttpDataSourceException.TYPE_OPEN
            )
        }

        this.response = resp
        val body = resp.body
        this.responseByteStream = body?.byteStream()

        val responseCode = resp.code

        if (!resp.isSuccessful) {
            if (responseCode == 416) {
                val documentSize = HttpUtil.getDocumentSize(resp.headers["Content-Range"])
                if (dataSpec.position == documentSize) {
                    opened = true
                    transferStarted(dataSpec)
                    return if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else 0L
                }
            }

            val errorBody = runCatching { body?.bytes() }.getOrNull() ?: ByteArray(0)
            val headersMap = resp.headers.toMultimap()
            val responseMessage = resp.message
            closeConnectionQuietly()

            val cause = if (responseCode == 416) {
                DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
            } else {
                null
            }
            throw HttpDataSource.InvalidResponseCodeException(
                responseCode,
                responseMessage,
                cause,
                headersMap,
                dataSpec,
                errorBody
            )
        }

        val bytesToSkip = if (responseCode == 200 && dataSpec.position != 0L) dataSpec.position else 0L

        bytesToRead = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length
        } else {
            val contentLength = body?.contentLength() ?: -1L
            if (contentLength != -1L) (contentLength - bytesToSkip).coerceAtLeast(0L) else C.LENGTH_UNSET.toLong()
        }

        opened = true
        transferStarted(dataSpec)

        try {
            skipFully(bytesToSkip, dataSpec)
        } catch (e: HttpDataSource.HttpDataSourceException) {
            closeConnectionQuietly()
            throw e
        }

        return bytesToRead
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val spec = this.dataSpec ?: return C.RESULT_END_OF_INPUT

        return try {
            var readLength = length
            if (bytesToRead != C.LENGTH_UNSET.toLong()) {
                val bytesRemaining = bytesToRead - bytesRead
                if (bytesRemaining == 0L) {
                    return C.RESULT_END_OF_INPUT
                }
                readLength = minOf(length.toLong(), bytesRemaining).toInt()
            }

            val stream = responseByteStream ?: return C.RESULT_END_OF_INPUT
            val read = stream.read(buffer, offset, readLength)
            if (read == -1) {
                return C.RESULT_END_OF_INPUT
            }

            bytesRead += read
            bytesTransferred(read)
            read
        } catch (e: IOException) {
            throw HttpDataSource.HttpDataSourceException.createForIOException(
                e,
                spec,
                HttpDataSource.HttpDataSourceException.TYPE_READ
            )
        }
    }

    override fun close() {
        if (opened) {
            opened = false
            transferEnded()
        }
        closeConnectionQuietly()
    }

    private fun buildOkHttpRequest(dataSpec: DataSpec): Request {
        val urlString = dataSpec.uri.toString()
        val httpUrl = urlString.toHttpUrlOrNull()
            ?: throw HttpDataSource.HttpDataSourceException(
                "Geçersiz HTTP yayın adresi: $urlString",
                dataSpec,
                HttpDataSource.HttpDataSourceException.TYPE_OPEN
            )

        val builder = Request.Builder().url(httpUrl)

        val mergedHeaders = LinkedHashMap<String, String>()
        mergedHeaders.putAll(defaultRequestProperties.snapshot)
        mergedHeaders.putAll(requestProperties.snapshot)
        mergedHeaders.putAll(dataSpec.httpRequestHeaders)

        for ((k, v) in mergedHeaders) {
            builder.header(k, v)
        }

        val rangeHeader = HttpUtil.buildRangeRequestHeader(dataSpec.position, dataSpec.length)
        if (rangeHeader != null) {
            builder.header("Range", rangeHeader)
        }

        val ua = userAgentProvider()
        if (!ua.isNullOrBlank() && mergedHeaders.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            builder.header("User-Agent", ua)
        }

        if (mergedHeaders.keys.none { it.equals("Accept-Encoding", ignoreCase = true) }) {
            if (rangeHeader != null || !dataSpec.isFlagSet(DataSpec.FLAG_ALLOW_GZIP)) {
                builder.header("Accept-Encoding", "identity")
            }
        }

        val httpBody = dataSpec.httpBody
        val requestBody = when {
            httpBody != null -> httpBody.toRequestBody(null)
            dataSpec.httpMethod == DataSpec.HTTP_METHOD_POST -> ByteArray(0).toRequestBody(null)
            else -> null
        }
        builder.method(dataSpec.httpMethodString, requestBody)

        return builder.build()
    }

    private fun skipFully(bytesToSkip: Long, dataSpec: DataSpec) {
        if (bytesToSkip <= 0L) return
        val stream = responseByteStream ?: return
        val skipBuffer = ByteArray(4096)
        var remaining = bytesToSkip
        while (remaining > 0L) {
            if (Thread.currentThread().isInterrupted) {
                throw HttpDataSource.HttpDataSourceException.createForIOException(
                    InterruptedIOException(),
                    dataSpec,
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
                    dataSpec,
                    PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
                    HttpDataSource.HttpDataSourceException.TYPE_OPEN
                )
            }
            remaining -= read
            bytesTransferred(read)
        }
    }

    private fun closeConnectionQuietly() {
        runCatching { responseByteStream?.close() }
        responseByteStream = null
        runCatching { response?.close() }
        response = null
        call = null
    }
}
