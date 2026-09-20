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
private fun expectU32(i:BufferedInputStream,v:Int){check(readPacket(i).type==9);val d=readPacket(i);check(d.type==12);check(g32(d.payload,4)==v){"value=0x${g32(d.payload,4).toString(16)} expected=0x${v.toString(16)}"}}

private fun ptpString(s:String):ByteArray {
    if(s.isEmpty()) return byteArrayOf(0)
    val body=(s+"\u0000").toByteArray(Charset.forName("UTF-16LE"))
    return byteArrayOf((s.length+1).toByte())+body
}
private fun emptyU16Array()=s32(0)
private fun deviceInfo():ByteArray =
    s16(100)+s32(0x11)+s16(100)+ptpString("Sony")+s16(0)+
    emptyU16Array()+emptyU16Array()+emptyU16Array()+emptyU16Array()+emptyU16Array()+
    ptpString("Sony")+ptpString("ZV-E10M2")+ptpString("1.00")+ptpString("TEST")

private fun extInfo():ByteArray =
    s16(0x012C) +
    s32(2) + s16(0xE021) + s16(0xE022) +
    s32(1) + s16(0xD309)

private fun playback9209():ByteArray {
    // D208 AUINT32: default=[] current=[LEFT, RIGHT, ENTER, PLAYBACK]
    val d208 = s16(0xD208)+s16(0x4006)+byteArrayOf(0x00,0x01)+
        s32(0)+s32(4)+s32(0x00030000)+s32(0x00040000)+s32(0x00050000)+s32(0x00110000)+byteArrayOf(0)
    fun button(code:Int)=s16(code)+s16(0x0004)+byteArrayOf(0x01,0x01)+s16(1)+s16(1)+byteArrayOf(0)
    return s32(3)+s32(0)+d208+button(0xE021)+button(0xE022)
}

fun main(){
    val server=ServerSocket(0)
    val worker=thread(name="fake-zve10m2-playback-center"){
        val cmd=server.accept();val ci=BufferedInputStream(cmd.getInputStream());val co=BufferedOutputStream(cmd.getOutputStream())
        check(readPacket(ci).type==1)
        sendPacket(co,2,s32(1)+ByteArray(16)+"Sony Camera\u0000".toByteArray(Charset.forName("UTF-16LE"))+s32(0x10000))
        val evt=server.accept();val ei=BufferedInputStream(evt.getInputStream());val eo=BufferedOutputStream(evt.getOutputStream());check(readPacket(ei).type==3);sendPacket(eo,4,byteArrayOf())
        var p=readPacket(ci);check(op(p)==0x1002);response(co,0)
        p=readPacket(ci);check(op(p)==0x1001);dataIn(co,1,deviceInfo());response(co,1)
        p=readPacket(ci);check(op(p)==0x1004);response(co,2)
        p=readPacket(ci);check(op(p)==0x9201&&param(p,0)==1);response(co,3)
        p=readPacket(ci);check(op(p)==0x9201&&param(p,0)==2);response(co,4)
        p=readPacket(ci);check(op(p)==0x9202);dataIn(co,5,extInfo());response(co,5)
        p=readPacket(ci);check(op(p)==0x9201&&param(p,0)==3);response(co,6)
        p=readPacket(ci);check(op(p)==0x9202);dataIn(co,7,extInfo());response(co,7)
        for(n in 8..17){p=readPacket(ci);check(op(p)==0x9209&&tx(p)==n);response(co,n)}

        // Physical Playback key still enters camera Playback mode.
        p=readPacket(ci);check(op(p)==0x9207&&param(p,0)==0xD309&&tx(p)==18);expectU32(ci,0x00110002);response(co,18)
        p=readPacket(ci);check(op(p)==0x9207&&param(p,0)==0xD309&&tx(p)==19);expectU32(ci,0x00110001);response(co,19)

        // PLAY: even though E021/E022 are explicitly advertised and enabled, a
        // ZV-E10M2 must bypass them and press the physical control-wheel ENTER.
        p=readPacket(ci);check(op(p)==0x9209&&tx(p)==20);dataIn(co,20,playback9209());response(co,20)
        p=readPacket(ci);check(op(p)==0x9207&&param(p,0)==0xD309&&tx(p)==21);expectU32(ci,0x00050002);response(co,21)
        p=readPacket(ci);check(op(p)==0x9207&&param(p,0)==0xD309&&tx(p)==22);expectU32(ci,0x00050001);response(co,22)

        // PAUSE uses the exact same physical ENTER toggle.
        p=readPacket(ci);check(op(p)==0x9209&&tx(p)==23);dataIn(co,23,playback9209());response(co,23)
        p=readPacket(ci);check(op(p)==0x9207&&param(p,0)==0xD309&&tx(p)==24);expectU32(ci,0x00050002);response(co,24)
        p=readPacket(ci);check(op(p)==0x9207&&param(p,0)==0xD309&&tx(p)==25);expectU32(ci,0x00050001);response(co,25)

        // PREVIOUS file = physical LEFT 0x0003.
        p=readPacket(ci);check(op(p)==0x9209&&tx(p)==26);dataIn(co,26,playback9209());response(co,26)
        p=readPacket(ci);check(op(p)==0x9207&&param(p,0)==0xD309&&tx(p)==27);expectU32(ci,0x00030002);response(co,27)
        p=readPacket(ci);check(op(p)==0x9207&&param(p,0)==0xD309&&tx(p)==28);expectU32(ci,0x00030001);response(co,28)

        // NEXT file = physical RIGHT 0x0004.
        p=readPacket(ci);check(op(p)==0x9209&&tx(p)==29);dataIn(co,29,playback9209());response(co,29)
        p=readPacket(ci);check(op(p)==0x9207&&param(p,0)==0xD309&&tx(p)==30);expectU32(ci,0x00040002);response(co,30)
        p=readPacket(ci);check(op(p)==0x9207&&param(p,0)==0xD309&&tx(p)==31);expectU32(ci,0x00040001);response(co,31)
        evt.close();cmd.close();server.close()
    }
    val c=SonyPtpIpClient("127.0.0.1",server.localPort,ByteArray(16),"Android");c.connect()
    val logs=mutableListOf<String>()
    val ctrl=SonyPtpLiveViewController(c){logs+=it}
    check(ctrl.initialize()==SonyPtpLiveViewController.Protocol.PTP3_SDIO)
    check(ctrl.deviceInfo?.model=="ZV-E10M2")
    check(ctrl.enterPlaybackMode())
    check(ctrl.moviePlaybackPlay())
    check(ctrl.moviePlaybackPause())
    check(ctrl.moviePlaybackPrevious())
    check(ctrl.moviePlaybackNext())
    ctrl.close();worker.join()
    check(logs.any{it.contains("Playback PREVIOUS") && it.contains("0x3")}) { logs.joinToString("\n") }
    check(logs.any{it.contains("Playback NEXT") && it.contains("0x4")}) { logs.joinToString("\n") }
    println("PTP PLAYBACK TILE TRANSPORT PASS: PLAY/PAUSE center + PREV left + NEXT right")
}
