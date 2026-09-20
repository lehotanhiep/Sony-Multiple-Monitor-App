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
private fun readFully(input: BufferedInputStream, b: ByteArray) { var o=0; while (o<b.size) { val n=input.read(b,o,b.size-o); check(n>0); o+=n } }
private fun readPacket(input: BufferedInputStream): Packet { val h=ByteArray(8); readFully(input,h); val len=g32(h,0); val p=ByteArray(len-8); readFully(input,p); return Packet(g32(h,4),p) }
private fun sendPacket(out: BufferedOutputStream, type: Int, payload: ByteArray) { out.write(s32(8+payload.size)); out.write(s32(type)); out.write(payload); out.flush() }
private fun response(out: BufferedOutputStream, tx: Int, code: Int = 0x2001) = sendPacket(out, 7, s16(code)+s32(tx))
private fun dataIn(out: BufferedOutputStream, tx: Int, data: ByteArray, declared: Long = data.size.toLong()) { sendPacket(out,9,s32(tx)+s64(declared)); sendPacket(out,12,s32(tx)+data) }
private fun op(p: Packet)=g16(p.payload,4)
private fun tx(p: Packet)=g32(p.payload,6)
private fun param(p: Packet,i:Int)=g32(p.payload,10+i*4)

private fun d26aDataset(current: Int): ByteArray {
    // 0x9209 Sony enum: u64 count, code, type(U16), getSet, enabled,
    // default, current, form=ENUM, first list, second/settable list.
    val entry = s16(0xD26A) + s16(0x0004) + byteArrayOf(0, 1) +
        s16(current) + s16(current) + byteArrayOf(2) +
        s16(3) + s16(1) + s16(2) + s16(3) +
        s16(3) + s16(1) + s16(2) + s16(3)
    return s64(1) + entry
}

private fun runCase(high: Boolean) {
    val initial = if (high) 1 else 2
    val target = if (high) 2 else 1
    val server = ServerSocket(0)
    val worker = thread(name="fake-source-res-$target") {
        val cmd=server.accept(); val ci=BufferedInputStream(cmd.getInputStream()); val co=BufferedOutputStream(cmd.getOutputStream())
        check(readPacket(ci).type==1)
        val ack=s32(21)+ByteArray(16){(it+3).toByte()}+"Sony ZV-1\u0000".toByteArray(Charset.forName("UTF-16LE"))+s32(0x10000)
        sendPacket(co,2,ack)
        val evt=server.accept(); val ei=BufferedInputStream(evt.getInputStream()); val eo=BufferedOutputStream(evt.getOutputStream())
        check(readPacket(ei).type==3); sendPacket(eo,4,byteArrayOf())

        var p=readPacket(ci); check(op(p)==0x1002 && tx(p)==0); response(co,0)
        p=readPacket(ci); check(op(p)==0x1001 && tx(p)==1); response(co,1)
        p=readPacket(ci); check(op(p)==0x1004 && tx(p)==2); response(co,2)
        p=readPacket(ci); check(op(p)==0x9201 && param(p,0)==1); response(co,3)
        p=readPacket(ci); check(op(p)==0x9201 && param(p,0)==2); response(co,4)
        val ext=s16(0x012C)+s32(0)+s32(0)
        p=readPacket(ci); check(op(p)==0x9202 && tx(p)==5); dataIn(co,5,ext); response(co,5)
        p=readPacket(ci); check(op(p)==0x9201 && param(p,0)==3); response(co,6)
        p=readPacket(ci); check(op(p)==0x9202 && tx(p)==7); dataIn(co,7,ext); response(co,7)
        val props=d26aDataset(initial)
        for (expectedTx in 8..17) {
            p=readPacket(ci); check(op(p)==0x9209 && tx(p)==expectedTx); dataIn(co,expectedTx,props); response(co,expectedTx)
        }

        p=readPacket(ci)
        check(op(p)==0x9205 && tx(p)==18 && param(p,0)==0xD26A)
        val start=readPacket(ci); check(start.type==9 && g32(start.payload,0)==18)
        val end=readPacket(ci); check(end.type==12 && g32(end.payload,0)==18)
        check(g16(end.payload,4)==target) { "D26A target=${g16(end.payload,4)} expected=$target" }
        response(co,18)

        p=readPacket(ci); check(op(p)==0x1008 && tx(p)==19 && param(p,0)==-16382); response(co,19)
        p=readPacket(ci); check(op(p)==0x1009 && tx(p)==20 && param(p,0)==-16382)
        val jpeg=byteArrayOf(0xff.toByte(),0xd8.toByte(),0x11,0x22,0xff.toByte(),0xd9.toByte())
        dataIn(co,20,byteArrayOf(1,2,3)+jpeg,declared=0xffffffffL); response(co,20)
        evt.close(); cmd.close(); server.close()
    }

    val client=SonyPtpIpClient("127.0.0.1",server.localPort,ByteArray(16){it.toByte()},"Android")
    client.connect()
    val ctrl=SonyPtpLiveViewController(client){}
    check(ctrl.initialize()==SonyPtpLiveViewController.Protocol.PTP3_SDIO)
    check(ctrl.applyLiveViewSourceQuality(high))
    ctrl.primeLiveViewObject()
    check(ctrl.getLiveViewJpegFast().size==6)
    ctrl.close(); worker.join()
}

fun main() {
    runCase(high=true)
    runCase(high=false)
    println("PTP SOURCE RES SELF-TEST PASS: D26A=2 HIGH and D26A=1 LOW before FFFFC002 prime")
}
