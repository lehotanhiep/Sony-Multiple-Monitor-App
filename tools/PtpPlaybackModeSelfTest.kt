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
private fun expectDataOut(ci: BufferedInputStream, expectedValue: Int) {
    check(readPacket(ci).type==9)
    val d=readPacket(ci); check(d.type==12); check(g32(d.payload,4)==expectedValue) { "value=0x${g32(d.payload,4).toString(16)} expected=0x${expectedValue.toString(16)}" }
}
private fun expectU16DataOut(ci: BufferedInputStream, expectedValue: Int) {
    check(readPacket(ci).type==9)
    val d=readPacket(ci); check(d.type==12); check(g16(d.payload,4)==expectedValue) { "value=${g16(d.payload,4)} expected=$expectedValue" }
}

// 0x9209 blob that deliberately starts with an unknown descriptor so the normal
// conservative parser stops before E021/E022. The raw playback scanner must still
// find the two movie-button properties. Camera Control PTP3 specifies getSet=0x01
// and UINT16 Up/Down values, so the write route is 0x9205.
private fun playback9209(): ByteArray {
    val header = s32(3) + s32(0)
    val unknown = s16(0xD777) + s16(0x1234) + byteArrayOf(0, 1) + byteArrayOf(0, 0, 0, 0, 0)
    fun button(code: Int) =
        s16(code) + s16(0x0004) + byteArrayOf(0x01, 1) + s16(1) + s16(1) + byteArrayOf(0)
    return header + unknown + button(0xE021) + button(0xE022)
}

fun main(){
    val server=ServerSocket(0)
    val worker=thread(name="fake-sony-playback"){
        val cmd=server.accept(); val ci=BufferedInputStream(cmd.getInputStream()); val co=BufferedOutputStream(cmd.getOutputStream())
        check(readPacket(ci).type==1)
        val ack=s32(1)+ByteArray(16)+"Sony Camera\u0000".toByteArray(Charset.forName("UTF-16LE"))+s32(0x10000)
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
        for(expected in 8..17){ p=readPacket(ci); check(op(p)==0x9209 && tx(p)==expected); response(co,expected) }

        // Enter Playback: D309 button ID 0x0011 ON -> OFF.
        p=readPacket(ci); check(op(p)==0x9207 && param(p,0)==0xD309 && tx(p)==18); expectDataOut(ci,0x00110002); response(co,18)
        p=readPacket(ci); check(op(p)==0x9207 && param(p,0)==0xD309 && tx(p)==19); expectDataOut(ci,0x00110001); response(co,19)

        // PLAY refreshes the post-mode-switch descriptor table first.
        p=readPacket(ci); check(op(p)==0x9209 && tx(p)==20); dataIn(co,20,playback9209()); response(co,20)
        // E021 is getSet=0x01 => SetExtDevicePropValue 0x9205.
        p=readPacket(ci); check(op(p)==0x9205 && param(p,0)==0xE021 && tx(p)==21); expectU16DataOut(ci,2); response(co,21)
        p=readPacket(ci); check(op(p)==0x9205 && param(p,0)==0xE021 && tx(p)==22); expectU16DataOut(ci,1); response(co,22)

        // PAUSE repeats the fresh 0x9209 read because availability can change with playback state.
        p=readPacket(ci); check(op(p)==0x9209 && tx(p)==23); dataIn(co,23,playback9209()); response(co,23)
        p=readPacket(ci); check(op(p)==0x9205 && param(p,0)==0xE022 && tx(p)==24); expectU16DataOut(ci,2); response(co,24)
        p=readPacket(ci); check(op(p)==0x9205 && param(p,0)==0xE022 && tx(p)==25); expectU16DataOut(ci,1); response(co,25)

        // Exit Playback: same physical Playback button again.
        p=readPacket(ci); check(op(p)==0x9207 && param(p,0)==0xD309 && tx(p)==26); expectDataOut(ci,0x00110002); response(co,26)
        p=readPacket(ci); check(op(p)==0x9207 && param(p,0)==0xD309 && tx(p)==27); expectDataOut(ci,0x00110001); response(co,27)
        evt.close(); cmd.close(); server.close()
    }
    val client=SonyPtpIpClient("127.0.0.1",server.localPort,ByteArray(16),"Android")
    client.connect()
    val ctrl=SonyPtpLiveViewController(client){}
    check(ctrl.initialize()==SonyPtpLiveViewController.Protocol.PTP3_SDIO)
    check(ctrl.enterPlaybackMode())
    check(ctrl.moviePlaybackPlay())
    check(ctrl.moviePlaybackPause())
    check(ctrl.pressPlaybackModeButton())
    ctrl.close(); worker.join()
    println("PTP PLAYBACK SELF-TEST PASS: post-Playback 0x9209 + E021/E022 getSet=1 routed through 0x9205")
}
