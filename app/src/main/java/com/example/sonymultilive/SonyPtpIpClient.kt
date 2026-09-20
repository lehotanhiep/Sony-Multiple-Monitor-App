package com.example.sonymultilive

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class SonyPtpIpClient(
    private val host: String,
    private val port: Int = 15740,
    private val initiatorGuid: ByteArray,
    private val initiatorName: String,
    private val socketBinder: ((Socket) -> Unit)? = null,
    private val onEvent: (PtpEvent) -> Unit = {}
) : AutoCloseable {
    data class PtpResult(val responseCode: Int, val params: IntArray, val data: ByteArray) {
        val ok: Boolean get() = responseCode == RC_OK
    }
    data class PtpEvent(val code: Int, val transactionId: Long, val params: IntArray)
    private data class Packet(val type: Int, val payload: ByteArray)

    companion object {
        const val RC_OK = 0x2001
        private const val TYPE_INIT_COMMAND_REQUEST = 1
        private const val TYPE_INIT_COMMAND_ACK = 2
        private const val TYPE_INIT_EVENT_REQUEST = 3
        private const val TYPE_INIT_EVENT_ACK = 4
        private const val TYPE_INIT_FAIL = 5
        private const val TYPE_OPERATION_REQUEST = 6
        private const val TYPE_OPERATION_RESPONSE = 7
        private const val TYPE_EVENT = 8
        private const val TYPE_START_DATA = 9
        private const val TYPE_DATA = 10
        private const val TYPE_CANCEL = 11
        private const val TYPE_END_DATA = 12
        private const val TYPE_PING = 13
        private const val TYPE_PONG = 14
        private const val PHASE_NO_DATA_OR_DATA_IN = 1
        private const val PHASE_DATA_OUT = 2
        private const val PROTOCOL_VERSION = 0x00010000
        private const val MAX_PACKET_BYTES = 128 * 1024 * 1024
    }

    private val commandLock = ReentrantLock(true)
    private val running = AtomicBoolean(false)
    private val nextTransaction = AtomicInteger(1)
    @Volatile private var commandSocket: Socket? = null
    @Volatile private var eventSocket: Socket? = null
    @Volatile private var commandIn: BufferedInputStream? = null
    @Volatile private var commandOut: BufferedOutputStream? = null
    @Volatile private var eventThread: Thread? = null
    var connectionNumber: Long = 0; private set
    var responderName: String = ""; private set

    init { require(initiatorGuid.size == 16) }

    fun connect(timeoutMs: Int = 5000) {
        close()
        val cmd = Socket()
        socketBinder?.invoke(cmd)
        cmd.tcpNoDelay = true
        cmd.keepAlive = true
        cmd.receiveBufferSize = 16 * 1024 * 1024
        cmd.sendBufferSize = 4 * 1024 * 1024
        cmd.soTimeout = timeoutMs
        cmd.connect(InetSocketAddress(host, port), timeoutMs)
        val cin = BufferedInputStream(cmd.getInputStream(), 8 * 1024 * 1024)
        val cout = BufferedOutputStream(cmd.getOutputStream(), 2 * 1024 * 1024)

        sendPacket(cout, TYPE_INIT_COMMAND_REQUEST, initiatorGuid + encodeUtf16LeZ(initiatorName) + le32(PROTOCOL_VERSION))
        val ack = readPacket(cin)
        if (ack.type == TYPE_INIT_FAIL) error("InitCommand rejected")
        require(ack.type == TYPE_INIT_COMMAND_ACK) { "Expected InitCommandAck, got ${ack.type}" }
        require(ack.payload.size >= 24) { "InitCommandAck too short: ${ack.payload.size}" }
        connectionNumber = u32(ack.payload, 0)
        responderName = decodeUtf16LeZ(ack.payload, 20, (ack.payload.size - 24).coerceAtLeast(0))

        val evt = Socket()
        socketBinder?.invoke(evt)
        evt.tcpNoDelay = true
        evt.keepAlive = true
        evt.receiveBufferSize = 4 * 1024 * 1024
        evt.sendBufferSize = 1024 * 1024
        evt.soTimeout = timeoutMs
        evt.connect(InetSocketAddress(host, port), timeoutMs)
        val ein = BufferedInputStream(evt.getInputStream(), 1024 * 1024)
        val eout = BufferedOutputStream(evt.getOutputStream(), 256 * 1024)
        sendPacket(eout, TYPE_INIT_EVENT_REQUEST, le32(connectionNumber.toInt()))
        val eventAck = readPacket(ein)
        require(eventAck.type == TYPE_INIT_EVENT_ACK) { "Expected InitEventAck, got ${eventAck.type}" }

        evt.soTimeout = 0
        commandSocket = cmd
        commandIn = cin
        commandOut = cout
        eventSocket = evt
        running.set(true)
        startEventReader(ein, eout)
    }

    fun openSession(sessionId: Int = 1): PtpResult {
        val r = transactionWithId(0, 0x1002, intArrayOf(sessionId), null)
        if (r.ok) nextTransaction.set(1)
        return r
    }

    fun transaction(opCode: Int, params: IntArray = intArrayOf(), dataOut: ByteArray? = null): PtpResult {
        return transactionWithId(nextTransaction.getAndIncrement(), opCode, params, dataOut)
    }

    private fun transactionWithId(transactionId: Int, opCode: Int, params: IntArray, dataOut: ByteArray?): PtpResult = commandLock.withLock {
        val input = commandIn ?: error("PTP/IP not connected")
        val output = commandOut ?: error("PTP/IP not connected")
        val phase = if (dataOut == null) PHASE_NO_DATA_OR_DATA_IN else PHASE_DATA_OUT
        val request = ByteBuffer.allocate(10 + params.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(phase).putShort(opCode.toShort()).putInt(transactionId)
            .apply { params.forEach { putInt(it) } }.array()
        sendPacket(output, TYPE_OPERATION_REQUEST, request)
        if (dataOut != null) {
            val start = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(transactionId).putLong(dataOut.size.toLong()).array()
            sendPacket(output, TYPE_START_DATA, start)
            sendPacket(output, TYPE_END_DATA, le32(transactionId) + dataOut)
        }

        var data = ByteArrayOutputStream(64 * 1024)
        while (true) {
            val p = readPacket(input)
            when (p.type) {
                TYPE_OPERATION_RESPONSE -> {
                    require(p.payload.size >= 6)
                    val code = u16(p.payload, 0)
                    val tx = u32(p.payload, 2).toInt()
                    require(tx == transactionId) { "Response transaction mismatch $tx != $transactionId" }
                    val count = (p.payload.size - 6) / 4
                    val responseParams = IntArray(count) { i -> u32(p.payload, 6 + i * 4).toInt() }
                    return@withLock PtpResult(code, responseParams, data.toByteArray())
                }
                TYPE_START_DATA -> {
                    require(p.payload.size >= 12)
                    require(u32(p.payload, 0).toInt() == transactionId)
                    // Sony supplies a declared DATA-IN size here when it is known.
                    // Pre-size the accumulator for normal JPEG/object transfers to avoid
                    // repeated buffer growth/copies in the steady-state live-view loop.
                    // Sentinel/oversized lengths are intentionally ignored.
                    val declared = u64(p.payload, 4)
                    if (data.size() == 0 && declared in 65_537L..8_388_608L) {
                        data = ByteArrayOutputStream(declared.toInt())
                    }
                    if (p.payload.size > 12) data.write(p.payload, 12, p.payload.size - 12)
                }
                TYPE_DATA, TYPE_END_DATA -> {
                    require(p.payload.size >= 4)
                    require(u32(p.payload, 0).toInt() == transactionId)
                    if (p.payload.size > 4) data.write(p.payload, 4, p.payload.size - 4)
                }
                TYPE_PING -> sendPacket(output, TYPE_PONG, p.payload)
                TYPE_CANCEL -> error("Camera cancelled transaction $transactionId")
                else -> error("Unexpected PTP/IP packet type=${p.type}")
            }
        }
        @Suppress("UNREACHABLE_CODE") PtpResult(0, intArrayOf(), byteArrayOf())
    }

    override fun close() {
        running.set(false)
        runCatching { eventSocket?.close() }
        runCatching { commandSocket?.close() }
        eventSocket = null; commandSocket = null; commandIn = null; commandOut = null
        eventThread?.interrupt(); eventThread = null
    }

    private fun startEventReader(input: BufferedInputStream, output: BufferedOutputStream) {
        eventThread = Thread({
            while (running.get()) {
                try {
                    val p = readPacket(input)
                    when (p.type) {
                        TYPE_EVENT -> parseEvent(p.payload)?.let(onEvent)
                        TYPE_PING -> sendPacket(output, TYPE_PONG, p.payload)
                    }
                } catch (_: Throwable) {
                    break
                }
            }
        }, "ZV1-PTP-Events").apply { isDaemon = true; start() }
    }

    private fun parseEvent(payload: ByteArray): PtpEvent? {
        if (payload.size < 6) return null
        val code = u16(payload, 0)
        val tx = u32(payload, 2)
        val count = (payload.size - 6) / 4
        return PtpEvent(code, tx, IntArray(count) { i -> u32(payload, 6 + i * 4).toInt() })
    }

    private fun sendPacket(output: BufferedOutputStream, type: Int, payload: ByteArray) {
        val total = 8L + payload.size
        require(total <= MAX_PACKET_BYTES)
        output.write(le32(total.toInt())); output.write(le32(type)); output.write(payload); output.flush()
    }

    private fun readPacket(input: BufferedInputStream): Packet {
        val header = ByteArray(8); readFully(input, header)
        val len = u32(header, 0)
        val type = u32(header, 4).toInt()
        require(len in 8..MAX_PACKET_BYTES.toLong()) { "Bad PTP/IP length=$len" }
        val payload = ByteArray((len - 8).toInt()); readFully(input, payload)
        return Packet(type, payload)
    }

    private fun readFully(input: BufferedInputStream, dst: ByteArray) {
        var off = 0
        while (off < dst.size) {
            val n = input.read(dst, off, dst.size - off)
            if (n < 0) throw EOFException("EOF PTP/IP")
            off += n
        }
    }

    private fun encodeUtf16LeZ(text: String) = text.toByteArray(Charset.forName("UTF-16LE")) + byteArrayOf(0, 0)
    private fun decodeUtf16LeZ(bytes: ByteArray, offset: Int, maxLength: Int): String {
        var end = offset; val limit = (offset + maxLength).coerceAtMost(bytes.size)
        while (end + 1 < limit && !(bytes[end] == 0.toByte() && bytes[end + 1] == 0.toByte())) end += 2
        return if (end <= offset) "" else String(bytes, offset, end - offset, Charset.forName("UTF-16LE"))
    }
    private fun le32(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
    private fun u16(b: ByteArray, o: Int) = ByteBuffer.wrap(b, o, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff
    private fun u32(b: ByteArray, o: Int) = ByteBuffer.wrap(b, o, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
    private fun u64(b: ByteArray, o: Int): Long = ByteBuffer.wrap(b, o, 8).order(ByteOrder.LITTLE_ENDIAN).long
}
