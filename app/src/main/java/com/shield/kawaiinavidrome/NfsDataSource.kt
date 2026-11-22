package com.shield.kawaiinavidrome

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import kotlin.math.min

class NfsDataSource(private val nfsClient: NfsClient) : BaseDataSource(/* isNetwork= */ true) {
    private var uri: Uri? = null
    private var fileHandle: ByteArray = ByteArray(0)
    private var fileSize: Long = 0L
    private var position: Long = 0L
    private var opened = false

    private var cacheBuffer: ByteArray = ByteArray(0)
    private var cacheStartOffset: Long = -1L

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)

        val fhHex = dataSpec.uri.getQueryParameter("fh")
            ?: throw IllegalArgumentException("Missing NFS file handle in URI")
        fileHandle = NfsAudioFile.hexToBytes(fhHex)
        fileSize = dataSpec.uri.getQueryParameter("size")?.toLongOrNull() ?: C.LENGTH_UNSET.toLong()
        position = dataSpec.position
        cacheStartOffset = -1L
        cacheBuffer = ByteArray(0)

        opened = true
        transferStarted(dataSpec)

        return if (fileSize > 0L) {
            (fileSize - position).coerceAtLeast(0L)
        } else {
            C.LENGTH_UNSET.toLong()
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (fileSize > 0L && position >= fileSize) return C.RESULT_END_OF_INPUT

        val cacheEnd = cacheStartOffset + cacheBuffer.size
        if (cacheStartOffset < 0L || position < cacheStartOffset || position >= cacheEnd) {
            val fetchSize = 131072 // 128 KB read-ahead chunk
            val fetched = nfsClient.readBytes(fileHandle, position, fetchSize)
            if (fetched.isEmpty()) return C.RESULT_END_OF_INPUT
            cacheStartOffset = position
            cacheBuffer = fetched
        }

        val offsetInCache = (position - cacheStartOffset).toInt()
        val available = cacheBuffer.size - offsetInCache
        val toCopy = min(length, available)
        System.arraycopy(cacheBuffer, offsetInCache, buffer, offset, toCopy)

        position += toCopy
        bytesTransferred(toCopy)
        return toCopy
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        cacheBuffer = ByteArray(0)
        cacheStartOffset = -1L
        if (opened) {
            opened = false
            transferEnded()
        }
    }
}

class RoutingDataSource(
    nfsClient: NfsClient,
    private val httpDataSource: DefaultHttpDataSource
) : DataSource {
    private val nfsDataSource = NfsDataSource(nfsClient)
    private var activeDelegate: DataSource = httpDataSource

    override fun addTransferListener(transferListener: TransferListener) {
        nfsDataSource.addTransferListener(transferListener)
        httpDataSource.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        activeDelegate = if (dataSpec.uri.scheme == "nfs") nfsDataSource else httpDataSource
        return activeDelegate.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        activeDelegate.read(buffer, offset, length)

    override fun getUri(): Uri? = activeDelegate.uri

    override fun getResponseHeaders(): Map<String, List<String>> = activeDelegate.responseHeaders

    override fun close() = activeDelegate.close()
}
