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
private fun readFully(input: BufferedInputStream, b: ByteArray) { var o=0; while(o<b.size){ val n=input.read(b,o,b.size-o); check(n>0); o+=n } }
private fun readPacket(input: BufferedInputStream): Packet { val h=ByteArray(8); readFully(input,h); val len=g32(h,0); val p=ByteArray(len-8); readFully(input,p); return Packet(g32(h,4),p) }
private fun sendPacket(out: BufferedOutputStream, type: Int, payload: ByteArray){ out.write(s32(8+payload.size)); out.write(s32(type)); out.write(payload); out.flush() }
private fun response(out: BufferedOutputStream, tx: Int, code: Int=0x2001)=sendPacket(out,7,s16(code)+s32(tx))
private fun dataIn(out: BufferedOutputStream, tx: Int, data: ByteArray){ sendPacket(out,9,s32(tx)+s64(data.size.toLong())); sendPacket(out,12,s32(tx)+data) }
private fun op(p: Packet)=g16(p.payload,4)
private fun tx(p: Packet)=g32(p.payload,6)
private fun param(p: Packet,i:Int)=g32(p.payload,10+i*4)

private fun isoDataset(raw: Int): ByteArray {
    // one Sony descriptor: D21E UINT32, writable/enabled, default/current, FORM_NONE
    return s32(1)+s32(0)+s16(0xD21E)+s16(0x0006)+byteArrayOf(1,1)+s32(raw)+s32(raw)+byteArrayOf(0)
}

fun main(){
    val server=ServerSocket(0)
    val worker=thread(name="fake-zv1-event-prop"){
        val cmd=server.accept(); val ci=BufferedInputStream(cmd.getInputStream()); val co=BufferedOutputStream(cmd.getOutputStream())
        check(readPacket(ci).type==1)
        val ack=s32(1)+ByteArray(16)+"Sony ZV-1\u0000".toByteArray(Charset.forName("UTF-16LE"))+s32(0x10000)
        sendPacket(co,2,ack)
        val evt=server.accept(); val ei=BufferedInputStream(evt.getInputStream()); val eo=BufferedOutputStream(evt.getOutputStream())
        check(readPacket(ei).type==3); sendPacket(eo,4,byteArrayOf())
        var p=readPacket(ci); check(op(p)==0x1002); response(co,0)
        p=readPacket(ci); check(op(p)==0x1001); response(co,1)
        p=readPacket(ci); check(op(p)==0x1004); response(co,2)
        p=readPacket(ci); check(op(p)==0x9201 && param(p,0)==1); response(co,3)
        p=readPacket(ci); check(op(p)==0x9201 && param(p,0)==2); response(co,4)
        val ext=s16(0x012C)+s32(0)+s32(0)
        p=readPacket(ci); check(op(p)==0x9202); dataIn(co,5,ext); response(co,5)
        p=readPacket(ci); check(op(p)==0x9201 && param(p,0)==3); response(co,6)
        p=readPacket(ci); check(op(p)==0x9202); dataIn(co,7,ext); response(co,7)
        for(expected in 8..17){
            p=readPacket(ci); check(op(p)==0x9209 && tx(p)==expected)
            if(expected==8) dataIn(co,expected,isoDataset(800))
            response(co,expected)
        }
        p=readPacket(ci); check(op(p)==0x1008 && tx(p)==18); response(co,18)
        p=readPacket(ci); check(op(p)==0x1009 && tx(p)==19)
        val jpeg=byteArrayOf(0xff.toByte(),0xd8.toByte(),0x11,0xff.toByte(),0xd9.toByte())
        dataIn(co,19,jpeg); response(co,19)
        // Event-driven single-property refresh: vendor property => exactly one 0x9204.
        p=readPacket(ci); check(op(p)==0x9204 && tx(p)==20 && param(p,0)==0xD21E)
        dataIn(co,20,s32(1250)); response(co,20)
        // Live View continues directly; no 0x9209 or re-prime is inserted.
        p=readPacket(ci); check(op(p)==0x1009 && tx(p)==21)
        dataIn(co,21,jpeg); response(co,21)
        evt.close(); cmd.close(); server.close()
    }
    val client=SonyPtpIpClient("127.0.0.1",server.localPort,ByteArray(16),"Android")
    client.connect()
    val ctrl=SonyPtpLiveViewController(client){}
    check(ctrl.initialize()==SonyPtpLiveViewController.Protocol.PTP3_SDIO)
    ctrl.primeLiveViewObject()
    check(ctrl.getLiveViewJpegFast().isNotEmpty())
    check(ctrl.readChangedNumericProperty(0xD21E)==1250L)
    check(ctrl.getLiveViewJpegFast().isNotEmpty())
    ctrl.close(); worker.join()
    println("PTP EVENT PROPERTY SELF-TEST PASS: GetObject -> 0x9204(D21E) -> GetObject, no 0x9209")
}
