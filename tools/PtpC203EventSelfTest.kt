import com.example.sonymultilive.SonyPtpIpClient
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private fun s16(v: Int) = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array()
private fun s32(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
private fun g32(b: ByteArray, o: Int) = ByteBuffer.wrap(b, o, 4).order(ByteOrder.LITTLE_ENDIAN).int
private data class Packet(val type: Int, val payload: ByteArray)
private fun readFully(input: BufferedInputStream, b: ByteArray) { var o=0; while (o<b.size) { val n=input.read(b,o,b.size-o); check(n>0); o+=n } }
private fun readPacket(input: BufferedInputStream): Packet { val h=ByteArray(8); readFully(input,h); val len=g32(h,0); val p=ByteArray(len-8); readFully(input,p); return Packet(g32(h,4),p) }
private fun sendPacket(out: BufferedOutputStream, type: Int, payload: ByteArray) { out.write(s32(8+payload.size)); out.write(s32(type)); out.write(payload); out.flush() }

fun main() {
    val server = ServerSocket(0)
    val worker = thread(name="fake-zv1-c203") {
        val cmd = server.accept()
        val ci = BufferedInputStream(cmd.getInputStream())
        val co = BufferedOutputStream(cmd.getOutputStream())
        check(readPacket(ci).type == 1)
        val ack = s32(7) + ByteArray(16) + "Sony ZV-1\u0000".toByteArray(Charset.forName("UTF-16LE")) + s32(0x10000)
        sendPacket(co, 2, ack)

        val evt = server.accept()
        val ei = BufferedInputStream(evt.getInputStream())
        val eo = BufferedOutputStream(evt.getOutputStream())
        check(readPacket(ei).type == 3)
        sendPacket(eo, 4, byteArrayOf())

        // PTP/IP Event payload: EventCode, TransactionId, Param1.
        // Sony 0xC203 Param1 identifies the changed property; no new value follows.
        sendPacket(eo, 8, s16(0xC203) + s32(-1) + s32(0xD21E))
        Thread.sleep(100)
        evt.close(); cmd.close(); server.close()
    }

    val latch = CountDownLatch(1)
    var received: SonyPtpIpClient.PtpEvent? = null
    val client = SonyPtpIpClient(
        host="127.0.0.1",
        port=server.localPort,
        initiatorGuid=ByteArray(16),
        initiatorName="Android",
        onEvent={ event -> received = event; latch.countDown() }
    )
    client.connect()
    check(latch.await(2, TimeUnit.SECONDS)) { "C203 event was not delivered" }
    val event = checkNotNull(received)
    check(event.code == 0xC203)
    check(event.transactionId == 0xffffffffL)
    check(event.params.size == 1)
    check(event.params[0] == 0xD21E)
    client.close(); worker.join()
    println("PTP C203 EVENT SELF-TEST PASS: Param1=0xD21E, no value payload")
}
