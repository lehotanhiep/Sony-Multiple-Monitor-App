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

private fun prop(code:Int, dt:Int, raw:ByteArray):ByteArray =
    s16(code)+s16(dt)+byteArrayOf(1,1)+raw+raw+byteArrayOf(0)

private fun dataset(rec:Int, evMilli:Int, meterMilli:Int): ByteArray =
    s32(4)+s32(0) +
        prop(0xD21D, 0x0002, byteArrayOf(rec.toByte())) +
        prop(0x5010, 0x0003, s16(evMilli)) +
        prop(0xD1B5, 0x0003, s16(meterMilli)) +
        prop(0xD2C8, 0x0004, s16(1))

fun main(){
    val server=ServerSocket(0)
    val worker=thread(name="fake-sony-c203-bulk"){
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
            if(expected==8) dataIn(co,expected,dataset(0,0,0))
            response(co,expected)
        }
        p=readPacket(ci); check(op(p)==0x9209 && tx(p)==18); dataIn(co,18,dataset(1,500,-1000)); response(co,18)
        p=readPacket(ci); check(op(p)==0x9209 && tx(p)==19); dataIn(co,19,dataset(0,-500,750)); response(co,19)
        evt.close(); cmd.close(); server.close()
    }
    val client=SonyPtpIpClient("127.0.0.1",server.localPort,ByteArray(16),"Android")
    client.connect()
    val ctrl=SonyPtpLiveViewController(client){}
    check(ctrl.initialize()==SonyPtpLiveViewController.Protocol.PTP3_SDIO)
    check(ctrl.cachedExtDevicePropInfo()[0xD21D]?.currentValue==0L)
    val a=checkNotNull(ctrl.refreshExtDevicePropInfo())
    check(a[0xD21D]?.currentValue==1L)
    check(a[0x5010]?.currentValue==500L)
    check(a[0xD1B5]?.currentValue==-1000L)
    val b=checkNotNull(ctrl.refreshExtDevicePropInfo())
    check(b[0xD21D]?.currentValue==0L)
    check(b[0x5010]?.currentValue==-500L)
    check(b[0xD1B5]?.currentValue==750L)
    ctrl.close(); worker.join()
    println("PTP C203 BULK SNAPSHOT SELF-TEST PASS: 0x9209 updates D21D/5010/D1B5 across refreshes")
}
