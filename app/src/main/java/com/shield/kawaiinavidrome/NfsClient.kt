package com.shield.kawaiinavidrome

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

data class NfsAudioFile(
    val name: String,
    val relativePath: String,
    val fileHandle: ByteArray,
    val size: Long
) {
    fun fileHandleHex(): String = fileHandle.joinToString("") { "%02x".format(it) }

    companion object {
        fun hexToBytes(hex: String): ByteArray {
            val len = hex.length
            val out = ByteArray(len / 2)
            var i = 0
            while (i < len) {
                out[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
                i += 2
            }
            return out
        }
    }
}

class NfsClient {
    private val xidCounter = AtomicInteger((System.currentTimeMillis() and 0x7FFFFFFF).toInt())
    private val audioExtensions = setOf("mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "wma")

    @Volatile var host: String = "192.168.1.53"
    @Volatile var exportPath: String = "/Alex/Christmas Music"
    @Volatile var nfsPort: Int = 2049

    private var readSocket: Socket? = null
    private var readOut: DataOutputStream? = null
    private var readIn: DataInputStream? = null
    private val readLock = Any()

    fun parseShareSpec(spec: String): Pair<String, String> {
        val trimmed = spec.trim()
        val colonIdx = trimmed.indexOf(':')
        return if (colonIdx > 0) {
            val h = trimmed.substring(0, colonIdx).trim()
            val p = trimmed.substring(colonIdx + 1).trim().ifEmpty { "/" }
            h to p
        } else {
            trimmed to "/Alex/Christmas Music"
        }
    }

    suspend fun scanAudioFiles(shareSpec: String): List<NfsAudioFile> = withContext(Dispatchers.IO) {
        val (parsedHost, parsedPath) = parseShareSpec(shareSpec)
        host = parsedHost
        exportPath = parsedPath
        closeReadSocket()

        val mountPort = getPort(host, 100005, 3) ?: 20048
        nfsPort = getPort(host, 100003, 3) ?: 2049

        val rootHandle = mountExport(host, mountPort, exportPath)
            ?: throw IllegalStateException("Cannot mount NFS export $host:$exportPath")

        val results = mutableListOf<NfsAudioFile>()
        Socket(host, nfsPort).use { sock ->
            sock.soTimeout = 10000
            val out = DataOutputStream(sock.getOutputStream())
            val inp = DataInputStream(sock.getInputStream())
            traverseDirectory(out, inp, rootHandle, "", results, depth = 0)
        }
        results.sortBy { it.relativePath.lowercase() }
        results
    }

    fun readBytes(fileHandle: ByteArray, offset: Long, count: Int): ByteArray {
        synchronized(readLock) {
            for (attempt in 0..1) {
                try {
                    val sock = ensureReadSocket()
                    val out = readOut!!
                    val inp = readIn!!

                    val args = XdrWriter().apply {
                        writeOpaque(fileHandle)
                        writeLong(offset)
                        writeInt(count)
                    }.toByteArray()

                    val reply = callRpc(out, inp, 100003, 3, 6, args) // NFSPROC3_READ = 6
                    val status = reply.readInt()
                    if (status != 0) return ByteArray(0)

                    skipPostOpAttr(reply)
                    val bytesRead = reply.readInt()
                    reply.readBoolean() // eof
                    val data = reply.readOpaque()
                    return if (data.size == bytesRead) data else data.copyOf(bytesRead.coerceAtMost(data.size))
                } catch (e: Exception) {
                    closeReadSocket()
                    if (attempt == 1) throw e
                }
            }
            return ByteArray(0)
        }
    }

    private fun ensureReadSocket(): Socket {
        val current = readSocket
        if (current != null && current.isConnected && !current.isClosed) {
            return current
        }
        val sock = Socket(host, nfsPort).apply {
            soTimeout = 12000
            tcpNoDelay = true
            receiveBufferSize = 256 * 1024
        }
        readSocket = sock
        readOut = DataOutputStream(sock.getOutputStream())
        readIn = DataInputStream(sock.getInputStream())
        return sock
    }

    fun closeReadSocket() {
        synchronized(readLock) {
            try { readSocket?.close() } catch (_: Exception) {}
            readSocket = null
            readOut = null
            readIn = null
        }
    }

    private fun getPort(targetHost: String, program: Int, version: Int): Int? {
        return try {
            Socket(targetHost, 111).use { sock ->
                sock.soTimeout = 5000
                val out = DataOutputStream(sock.getOutputStream())
                val inp = DataInputStream(sock.getInputStream())
                val args = XdrWriter().apply {
                    writeInt(program)
                    writeInt(version)
                    writeInt(6) // IPPROTO_TCP
                    writeInt(0)
                }.toByteArray()
                val reply = callRpc(out, inp, 100000, 2, 3, args) // PMAPPROC_GETPORT = 3
                val port = reply.readInt()
                if (port > 0) port else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun mountExport(targetHost: String, mountPort: Int, path: String): ByteArray? {
        Socket(targetHost, mountPort).use { sock ->
            sock.soTimeout = 8000
            val out = DataOutputStream(sock.getOutputStream())
            val inp = DataInputStream(sock.getInputStream())
            val args = XdrWriter().apply {
                writeString(path)
            }.toByteArray()
            val reply = callRpc(out, inp, 100005, 3, 1, args) // MOUNTPROC3_MNT = 1
            val status = reply.readInt()
            if (status != 0) return null
            return reply.readOpaque()
        }
    }

    private fun traverseDirectory(
        out: DataOutputStream,
        inp: DataInputStream,
        dirHandle: ByteArray,
        prefix: String,
        results: MutableList<NfsAudioFile>,
        depth: Int
    ) {
        if (depth > 5) return
        var cookie = 0L
        var cookieVerf = ByteArray(8)
        var eof = false

        val names = mutableListOf<String>()
        while (!eof) {
            val args = XdrWriter().apply {
                writeOpaque(dirHandle)
                writeLong(cookie)
                writeFixedOpaque(cookieVerf)
                writeInt(32768)
            }.toByteArray()

            val reply = callRpc(out, inp, 100003, 3, 16, args) // NFSPROC3_READDIR = 16
            val status = reply.readInt()
            if (status != 0) break

            skipPostOpAttr(reply)
            cookieVerf = reply.readFixedOpaque(8)

            while (reply.readBoolean()) {
                reply.readLong() // fileid
                val entryName = reply.readString()
                cookie = reply.readLong()
                if (entryName != "." && entryName != "..") {
                    names.add(entryName)
                }
            }
            eof = reply.readBoolean()
        }

        for (name in names) {
            val lookupArgs = XdrWriter().apply {
                writeOpaque(dirHandle)
                writeString(name)
            }.toByteArray()

            val reply = callRpc(out, inp, 100003, 3, 3, lookupArgs) // NFSPROC3_LOOKUP = 3
            val status = reply.readInt()
            if (status != 0) continue

            val childHandle = reply.readOpaque()
            val attr = readPostOpAttr(reply) ?: continue
            val relPath = if (prefix.isEmpty()) name else "$prefix/$name"

            if (attr.first == 2) { // NF3DIR
                traverseDirectory(out, inp, childHandle, relPath, results, depth + 1)
            } else if (attr.first == 1) { // NF3REG
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext in audioExtensions && attr.second > 0L) {
                    results.add(NfsAudioFile(name, relPath, childHandle, attr.second))
                }
            }
        }
    }

    private fun skipPostOpAttr(r: XdrReader) {
        if (r.readBoolean()) {
            r.skip(84) // fattr3 is always 84 bytes in RFC 1813
        }
    }

    // Returns Pair(ftype, size) if attributes follow
    private fun readPostOpAttr(r: XdrReader): Pair<Int, Long>? {
        if (!r.readBoolean()) return null
        val ftype = r.readInt()
        r.skip(16) // mode, nlink, uid, gid
        val size = r.readLong()
        r.skip(56) // used, rdev(8), fsid(8), fileid(8), atime(8), mtime(8), ctime(8)
        return ftype to size
    }

    private fun callRpc(
        out: DataOutputStream,
        inp: DataInputStream,
        program: Int,
        version: Int,
        procedure: Int,
        args: ByteArray
    ): XdrReader {
        val xid = xidCounter.incrementAndGet()
        val authUnix = XdrWriter().apply {
            writeInt((System.currentTimeMillis() / 1000L).toInt())
            writeString("shield")
            writeInt(0) // uid
            writeInt(0) // gid
            writeInt(0) // auxiliary gids count
        }.toByteArray()

        val callHeader = XdrWriter().apply {
            writeInt(xid)
            writeInt(0) // CALL
            writeInt(2) // RPC version 2
            writeInt(program)
            writeInt(version)
            writeInt(procedure)
            writeInt(1) // AUTH_UNIX
            writeOpaque(authUnix)
            writeInt(0) // AUTH_NONE verifier
            writeInt(0)
        }.toByteArray()

        val payload = ByteArray(callHeader.size + args.size)
        System.arraycopy(callHeader, 0, payload, 0, callHeader.size)
        System.arraycopy(args, 0, payload, callHeader.size, args.size)

        // ONC-RPC Record Marking: bit 31 = last fragment (1)
        out.writeInt(payload.size or -0x80000000)
        out.write(payload)
        out.flush()

        val respStream = ByteArrayOutputStream()
        while (true) {
            val mark = inp.readInt()
            val last = (mark and -0x80000000) != 0
            val fragLen = mark and 0x7FFFFFFF
            val frag = ByteArray(fragLen)
            inp.readFully(frag)
            respStream.write(frag)
            if (last) break
        }

        val reader = XdrReader(respStream.toByteArray())
        reader.readInt() // xid
        val msgType = reader.readInt()
        if (msgType != 1) throw IllegalStateException("Unexpected RPC msgType=$msgType")
        val replyStat = reader.readInt()
        if (replyStat != 0) throw IllegalStateException("RPC call denied stat=$replyStat")
        reader.readInt() // verf flavor
        reader.readOpaque() // verf body
        val acceptStat = reader.readInt()
        if (acceptStat != 0) throw IllegalStateException("RPC procedure failed acceptStat=$acceptStat")
        return reader
    }

    private class XdrWriter {
        private val buf = ByteArrayOutputStream()
        private val out = DataOutputStream(buf)

        fun writeInt(v: Int) = out.writeInt(v)
        fun writeLong(v: Long) = out.writeLong(v)

        fun writeFixedOpaque(bytes: ByteArray) {
            out.write(bytes)
            val pad = (4 - (bytes.size % 4)) % 4
            repeat(pad) { out.writeByte(0) }
        }

        fun writeOpaque(bytes: ByteArray) {
            out.writeInt(bytes.size)
            writeFixedOpaque(bytes)
        }

        fun writeString(s: String) = writeOpaque(s.toByteArray(Charsets.UTF_8))
        fun toByteArray(): ByteArray = buf.toByteArray()
    }

    private class XdrReader(data: ByteArray) {
        private val bb = ByteBuffer.wrap(data)

        fun readInt(): Int = bb.int
        fun readLong(): Long = bb.long
        fun readBoolean(): Boolean = bb.int != 0

        fun skip(n: Int) {
            bb.position(bb.position() + n)
        }

        fun readFixedOpaque(len: Int): ByteArray {
            val out = ByteArray(len)
            bb.get(out)
            val pad = (4 - (len % 4)) % 4
            if (pad > 0) skip(pad)
            return out
        }

        fun readOpaque(): ByteArray {
            val len = bb.int
            return readFixedOpaque(len)
        }

        fun readString(): String = String(readOpaque(), Charsets.UTF_8)
    }
}
