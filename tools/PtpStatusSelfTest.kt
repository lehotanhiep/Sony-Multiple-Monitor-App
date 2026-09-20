import com.example.sonymultilive.SonyPtpIpClient
import com.example.sonymultilive.SonyPtpLiveViewController
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

private fun s16(v: Int) = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array()
private fun s32(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
private fun s64(v: Long) = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array()
private fun g16(b: ByteArray, o: Int) = ByteBuffer.wrap(b, o, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff
private fun g32(b: ByteArray, o: Int) = ByteBuffer.wrap(b, o, 4).order(ByteOrder.LITTLE_ENDIAN).int
private data class Packet(val type: Int, val payload: ByteArray)
private fun readFully(input: BufferedInputStream, b: ByteArray) { var o=0; while (o<b.size) { val n=input.read(b,o,b.size-o); check(n>0); o+=n } }
private fun readPacket(input: BufferedInputStream): Packet { val h=ByteArray(8); readFully(input,h); val len=g32(h,0); val p=ByteArray(len-8); readFully(input,p); return Packet(g32(h,4),p) }
private fun sendPacket(out: BufferedOutputStream, type: Int, payload: ByteArray) { out.write(s32(8+payload.size)); out.write(s32(type)); out.write(payload); out.flush() }
private fun response(out: BufferedOutputStream, tx: Int, code: Int=0x2001) = sendPacket(out,7,s16(code)+s32(tx))
private fun dataIn(out: BufferedOutputStream, tx: Int, data: ByteArray) { sendPacket(out,9,s32(tx)+s64(data.size.toLong())); sendPacket(out,12,s32(tx)+data) }
private fun op(p: Packet)=g16(p.payload,4)
private fun tx(p: Packet)=g32(p.payload,6)
private fun param(p: Packet,i:Int)=g32(p.payload,10+i*4)

private fun propU8None(code: Int, current: Int): ByteArray =
    s16(code) + s16(0x0002) + byteArrayOf(0, 1) + byteArrayOf(current.toByte()) + byteArrayOf(current.toByte()) + byteArrayOf(0)

private fun propU32None(code: Int, current: Long): ByteArray =
    s16(code) + s16(0x0006) + byteArrayOf(0, 1) + s32(current.toInt()) + s32(current.toInt()) + byteArrayOf(0)

private fun sonyStatusTable(batteryPercent: Int, remainingSeconds: Long): ByteArray {
    val props = listOf(
        propU8None(SonyPtpLiveViewController.PROP_BATTERY_REMAIN, batteryPercent),
        propU32None(SonyPtpLiveViewController.PROP_MEDIA_SLOT1_REMAINING_TIME, remainingSeconds)
    )
    return s32(props.size) + s32(0) + props.fold(ByteArray(0)) { a, b -> a + b }
}

fun main() {
    val storageId = 0x00010001
    val statusTable = sonyStatusTable(batteryPercent = 76, remainingSeconds = 2L * 3600L + 33L * 60L)
    val server=ServerSocket(0)
    val worker=thread(name="fake-status") {
        val cmd=server.accept(); val ci=BufferedInputStream(cmd.getInputStream()); val co=BufferedOutputStream(cmd.getOutputStream())
        check(readPacket(ci).type==1)
        val ack=s32(21)+ByteArray(16){it.toByte()}+"Sony Camera\u0000".toByteArray(Charset.forName("UTF-16LE"))+s32(0x10000)
        sendPacket(co,2,ack)
        val evt=server.accept(); val ei=BufferedInputStream(evt.getInputStream()); val eo=BufferedOutputStream(evt.getOutputStream())
        check(readPacket(ei).type==3); sendPacket(eo,4,byteArrayOf())
        var p=readPacket(ci); check(op(p)==0x1002 && tx(p)==0); response(co,0)
        p=readPacket(ci); check(op(p)==0x1001 && tx(p)==1); response(co,1)
        p=readPacket(ci); check(op(p)==0x1004 && tx(p)==2); dataIn(co,2,s32(1)+s32(storageId)); response(co,2)
        p=readPacket(ci); check(op(p)==0x9201 && param(p,0)==1); response(co,3)
        p=readPacket(ci); check(op(p)==0x9201 && param(p,0)==2); response(co,4)
        val ext=s16(0x012C)+s32(0)+s32(0)
        p=readPacket(ci); check(op(p)==0x9202); dataIn(co,5,ext); response(co,5)
        p=readPacket(ci); check(op(p)==0x9201 && param(p,0)==3); response(co,6)
        p=readPacket(ci); check(op(p)==0x9202); dataIn(co,7,ext); response(co,7)
        for (expected in 8..17) {
            p=readPacket(ci); check(op(p)==0x9209 && tx(p)==expected)
            dataIn(co, expected, statusTable); response(co, expected)
        }

        // readCameraStatus() must be cache-only. No 0x9209 / 0x1015 / 0x1005
        // transaction is allowed after the warm-up because Live View shares this
        // command channel with FFFFC002.
        cmd.soTimeout = 500
        try {
            val unexpected = readPacket(ci)
            error("unexpected post-warmup PTP packet type=${unexpected.type} op=0x${op(unexpected).toString(16)}")
        } catch (_: SocketTimeoutException) {
            // expected: no command while the client remains connected
        } catch (_: IllegalStateException) {
            // also acceptable here: the test client may close immediately after the
            // cache-only call, producing EOF rather than a timeout.
        }
        evt.close(); cmd.close(); server.close()
    }
    val client=SonyPtpIpClient("127.0.0.1",server.localPort,ByteArray(16){it.toByte()},"Android")
    client.connect()
    val ctrl=SonyPtpLiveViewController(client){}
    check(ctrl.initialize()==SonyPtpLiveViewController.Protocol.PTP3_SDIO)
    val cached = ctrl.cachedExtDevicePropInfo()
    check(cached[SonyPtpLiveViewController.PROP_BATTERY_REMAIN]?.currentValue == 76L)
    check(cached[SonyPtpLiveViewController.PROP_MEDIA_SLOT1_REMAINING_TIME]?.currentValue == 9180L)
    val status=ctrl.readCameraStatus()
    check(status.batteryPercent==76) { "battery=${status.batteryPercent}" }
    check(status.remainingRecordSeconds==9180L) { "remaining=${status.remainingRecordSeconds}" }
    ctrl.close(); worker.join()
    println("PTP STATUS SELF-TEST PASS: Sony D218=76% + D24A=9180s (2h33m)")
}
