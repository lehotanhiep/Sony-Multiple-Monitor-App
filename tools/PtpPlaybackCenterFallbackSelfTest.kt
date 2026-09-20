import com.example.sonymultilive.SonyPtpIpClient
import com.example.sonymultilive.SonyPtpLiveViewController
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import kotlin.concurrent.thread

private fun s16(v: Int)=ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array()
private fun s32(v: Int)=ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
private fun s64(v: Long)=ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array()
private fun g16(b:ByteArray,o:Int)=ByteBuffer.wrap(b,o,2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff
private fun g32(b:ByteArray,o:Int)=ByteBuffer.wrap(b,o,4).order(ByteOrder.LITTLE_ENDIAN).int
private data class Packet(val type:Int,val payload:ByteArray)
private fun readFully(i:BufferedInputStream,b:ByteArray){var o=0;while(o<b.size){val n=i.read(b,o,b.size-o);check(n>0);o+=n}}
private fun readPacket(i:BufferedInputStream):Packet{val h=ByteArray(8);readFully(i,h);val p=ByteArray(g32(h,0)-8);readFully(i,p);return Packet(g32(h,4),p)}
private fun sendPacket(o:BufferedOutputStream,t:Int,p:ByteArray){o.write(s32(8+p.size));o.write(s32(t));o.write(p);o.flush()}
private fun response(o:BufferedOutputStream,tx:Int,c:Int=0x2001)=sendPacket(o,7,s16(c)+s32(tx))
private fun dataIn(o:BufferedOutputStream,tx:Int,d:ByteArray){sendPacket(o,9,s32(tx)+s64(d.size.toLong()));sendPacket(o,12,s32(tx)+d)}
private fun op(p:Packet)=g16(p.payload,4)
private fun tx(p:Packet)=g32(p.payload,6)
private fun param(p:Packet,i:Int)=g32(p.payload,10+i*4)
private fun expectU32(i:BufferedInputStream,v:Int){check(readPacket(i).type==9);val d=readPacket(i);check(d.type==12);check(g32(d.payload,4)==v)}

private fun disabledPlayback9209():ByteArray{
    val header=s32(2)+s32(0)
    fun prop(code:Int)=s16(code)+s16(0x0004)+byteArrayOf(0x01,0x00)+s16(1)+s16(1)+byteArrayOf(0)
    return header+prop(0xE021)+prop(0xE022)
}

fun main(){
    val server=ServerSocket(0)
    val worker=thread(name="fake-sony-playback-center"){
        val cmd=server.accept();val ci=BufferedInputStream(cmd.getInputStream());val co=BufferedOutputStream(cmd.getOutputStream())
        check(readPacket(ci).type==1)
        sendPacket(co,2,s32(1)+ByteArray(16)+"Sony Camera\u0000".toByteArray(Charset.forName("UTF-16LE"))+s32(0x10000))
        val evt=server.accept();val ei=BufferedInputStream(evt.getInputStream());val eo=BufferedOutputStream(evt.getOutputStream());check(readPacket(ei).type==3);sendPacket(eo,4,byteArrayOf())
        var p=readPacket(ci);check(op(p)==0x1002);response(co,0)
        p=readPacket(ci);check(op(p)==0x1001);response(co,1)
        p=readPacket(ci);check(op(p)==0x1004);response(co,2)
        p=readPacket(ci);check(op(p)==0x9201&&param(p,0)==1);response(co,3)
        p=readPacket(ci);check(op(p)==0x9201&&param(p,0)==2);response(co,4)
        val ext=s16(0x012C)+s32(0)+s32(0)
        p=readPacket(ci);check(op(p)==0x9202);dataIn(co,5,ext);response(co,5)
        p=readPacket(ci);check(op(p)==0x9201&&param(p,0)==3);response(co,6)
        p=readPacket(ci);check(op(p)==0x9202);dataIn(co,7,ext);response(co,7)
        for(n in 8..17){p=readPacket(ci);check(op(p)==0x9209&&tx(p)==n);response(co,n)}
        // Enter Playback.
        p=readPacket(ci);check(op(p)==0x9207&&param(p,0)==0xD309&&tx(p)==18);expectU32(ci,0x00110002);response(co,18)
        p=readPacket(ci);check(op(p)==0x9207&&param(p,0)==0xD309&&tx(p)==19);expectU32(ci,0x00110001);response(co,19)
        // E021 is present but disabled -> ZV-style control-wheel ENTER fallback ID 0x0005.
        p=readPacket(ci);check(op(p)==0x9209&&tx(p)==20);dataIn(co,20,disabledPlayback9209());response(co,20)
        p=readPacket(ci);check(op(p)==0x9207&&param(p,0)==0xD309&&tx(p)==21);expectU32(ci,0x00050002);response(co,21)
        p=readPacket(ci);check(op(p)==0x9207&&param(p,0)==0xD309&&tx(p)==22);expectU32(ci,0x00050001);response(co,22)
        // E022 disabled -> same physical ENTER key toggles pause/play on ZV bodies.
        p=readPacket(ci);check(op(p)==0x9209&&tx(p)==23);dataIn(co,23,disabledPlayback9209());response(co,23)
        p=readPacket(ci);check(op(p)==0x9207&&param(p,0)==0xD309&&tx(p)==24);expectU32(ci,0x00050002);response(co,24)
        p=readPacket(ci);check(op(p)==0x9207&&param(p,0)==0xD309&&tx(p)==25);expectU32(ci,0x00050001);response(co,25)
        evt.close();cmd.close();server.close()
    }
    val c=SonyPtpIpClient("127.0.0.1",server.localPort,ByteArray(16),"Android");c.connect()
    val ctrl=SonyPtpLiveViewController(c){}
    check(ctrl.initialize()==SonyPtpLiveViewController.Protocol.PTP3_SDIO)
    check(ctrl.enterPlaybackMode())
    check(ctrl.moviePlaybackPlay())
    check(ctrl.moviePlaybackPause())
    ctrl.close();worker.join()
    println("PTP PLAYBACK CENTER FALLBACK PASS: disabled E021/E022 -> D309 ENTER 0x0005")
}
