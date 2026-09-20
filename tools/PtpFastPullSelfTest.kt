import com.example.sonymultilive.SonyPtpIpClient
import com.example.sonymultilive.SonyPtpLiveViewController
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import kotlin.concurrent.thread

private fun s16(v: Int) = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array()
private fun s32(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
private fun s64(v: Long) = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array()
private fun g16(b: ByteArray, o: Int) = ByteBuffer.wrap(b, o, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff
private fun g32(b: ByteArray, o: Int) = ByteBuffer.wrap(b, o, 4).order(ByteOrder.LITTLE_ENDIAN).int
private data class Packet(val type: Int, val payload: ByteArray)
private fun readFully(input: BufferedInputStream, b: ByteArray) { var o = 0; while (o < b.size) { val n = input.read(b, o, b.size-o); check(n > 0); o += n } }
private fun readPacket(input: BufferedInputStream): Packet { val h=ByteArray(8); readFully(input,h); val len=g32(h,0); val p=ByteArray(len-8); readFully(input,p); return Packet(g32(h,4),p) }
private fun sendPacket(out: BufferedOutputStream, type: Int, payload: ByteArray) { out.write(s32(8+payload.size)); out.write(s32(type)); out.write(payload); out.flush() }
private fun response(out: BufferedOutputStream, tx: Int, code: Int = 0x2001) = sendPacket(out, 7, s16(code) + s32(tx))
private fun dataIn(out: BufferedOutputStream, tx: Int, data: ByteArray, declared: Long = data.size.toLong()) { sendPacket(out, 9, s32(tx)+s64(declared)); sendPacket(out,12,s32(tx)+data) }
private fun op(packet: Packet) = g16(packet.payload, 4)
private fun tx(packet: Packet) = g32(packet.payload, 6)
private fun param(packet: Packet, index: Int) = g32(packet.payload, 10 + index * 4)

fun main() {
    val server = ServerSocket(0)
    val worker = thread(name = "fake-zv1-fastpull") {
        val cmd = server.accept()
        val ci = BufferedInputStream(cmd.getInputStream())
        val co = BufferedOutputStream(cmd.getOutputStream())
        check(readPacket(ci).type == 1)
        val ack = s32(21) + ByteArray(16) { (it + 3).toByte() } +
            "Sony ZV-1\u0000".toByteArray(Charset.forName("UTF-16LE")) + s32(0x10000)
        sendPacket(co, 2, ack)

        val evt = server.accept()
        val ei = BufferedInputStream(evt.getInputStream())
        val eo = BufferedOutputStream(evt.getOutputStream())
        check(readPacket(ei).type == 3)
        sendPacket(eo, 4, byteArrayOf())

        var p = readPacket(ci); check(op(p) == 0x1002 && tx(p) == 0); response(co, 0)
        p = readPacket(ci); check(op(p) == 0x1001 && tx(p) == 1); response(co, 1)
        p = readPacket(ci); check(op(p) == 0x1004 && tx(p) == 2); response(co, 2)
        p = readPacket(ci); check(op(p) == 0x9201 && param(p,0) == 1); response(co, 3)
        p = readPacket(ci); check(op(p) == 0x9201 && param(p,0) == 2); response(co, 4)

        val ext = s16(0x012C) + s32(0) + s32(0)
        p = readPacket(ci)
        check(op(p) == 0x9202 && tx(p) == 5 && param(p,0) == 0x012C && param(p,1) == 1)
        dataIn(co, 5, ext); response(co, 5)

        p = readPacket(ci); check(op(p) == 0x9201 && param(p,0) == 3); response(co, 6)
        p = readPacket(ci)
        check(op(p) == 0x9202 && tx(p) == 7 && param(p,0) == 0x012C && param(p,1) == 1)
        dataIn(co, 7, ext); response(co, 7)

        for (expectedTx in 8..17) {
            p = readPacket(ci)
            check(op(p) == 0x9209 && tx(p) == expectedTx)
            response(co, expectedTx)
        }

        p = readPacket(ci)
        check(op(p) == 0x1008 && tx(p) == 18 && param(p,0) == -16382)
        response(co, 18)

        // Fast pull must retry only GetObject(FFFFC002). No D26A/0x9205 or
        // any other camera property transaction may be inserted.
        p = readPacket(ci); check(op(p) == 0x1009 && tx(p) == 19 && param(p,0) == -16382); response(co, 19, 0x2019)
        p = readPacket(ci); check(op(p) == 0x1009 && tx(p) == 20 && param(p,0) == -16382); response(co, 20, 0x200F)
        p = readPacket(ci); check(op(p) == 0x1009 && tx(p) == 21 && param(p,0) == -16382)
        val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0x11, 0x22, 0x33, 0xff.toByte(), 0xd9.toByte())
        val dataset = byteArrayOf(0x24, 0x35, 0x68, 0x79, 0x01, 0x02, 0x03) + jpeg + byteArrayOf(0x55, 0x66)
        dataIn(co, 21, dataset, declared = 0xffffffffL); response(co, 21)

        evt.close(); cmd.close(); server.close()
    }

    val client = SonyPtpIpClient(
        host = "127.0.0.1",
        port = server.localPort,
        initiatorGuid = ByteArray(16) { it.toByte() },
        initiatorName = "Android"
    )
    client.connect()
    val controller = SonyPtpLiveViewController(client) { }
    check(controller.initialize() == SonyPtpLiveViewController.Protocol.PTP3_SDIO)
    controller.primeLiveViewObject()
    val jpeg = controller.getLiveViewJpegFast()
    check(jpeg.contentEquals(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0x11, 0x22, 0x33, 0xff.toByte(), 0xd9.toByte())))
    controller.close(); worker.join()
    println("Sony Multiple Monitor V1.0.0 fast GetObject pull self-test OK (busy retries, no D26A)")
}
