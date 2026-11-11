package linuxboot.desktop

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread
import org.junit.Test

/** Runs the real client against an in-process fake RFB server on loopback. */
class RfbClientTest {

    /** Independent bit-reversal (bit by bit) so a bug in the client's key handling is caught. */
    private fun expectedResponse(pw: String, challenge: ByteArray): ByteArray {
        val key = ByteArray(8)
        for (i in 0 until 8) {
            val b = if (i < pw.length) pw[i].code else 0
            var r = 0
            for (bit in 0 until 8) if (b and (1 shl bit) != 0) r = r or (1 shl (7 - bit))
            key[i] = r.toByte()
        }
        val c = Cipher.getInstance("DES/ECB/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "DES"))
        return c.doFinal(challenge)
    }

    /** 9x16 I-beam: serifs on rows 0 and 15 (x=1..7), stem at x=4. Two mask bytes per row. */
    private fun ibeamMask(): ByteArray {
        val m = ByteArray(2 * 16)
        for (y in 0 until 16) m[y * 2] = (if (y == 0 || y == 15) 0x7F else 0x08).toByte()
        return m
    }

    @Test
    fun handshakeFrameAndInput() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val seen = LinkedBlockingQueue<String>()
        thread(isDaemon = true) {
            server.accept().use { s ->
                val i = DataInputStream(s.getInputStream())
                val o = DataOutputStream(s.getOutputStream())
                o.write("RFB 003.008\n".toByteArray()); o.flush()
                i.readFully(ByteArray(12))
                o.write(byteArrayOf(1, 2)); o.flush()
                i.readUnsignedByte()
                val challenge = ByteArray(16) { it.toByte() }
                o.write(challenge); o.flush()
                val resp = ByteArray(16)
                i.readFully(resp)
                seen.add(if (resp.contentEquals(expectedResponse("secret", challenge))) "auth:ok" else "auth:bad")
                o.writeInt(0); o.flush()
                i.readUnsignedByte() // ClientInit
                o.writeShort(2); o.writeShort(1)
                o.write(ByteArray(16))
                val name = "testsrv".toByteArray()
                o.writeInt(name.size); o.write(name); o.flush()
                i.readFully(ByteArray(20))      // SetPixelFormat
                i.readFully(ByteArray(4 + 16))  // SetEncodings (4 encodings)
                i.readFully(ByteArray(10))      // first update request
                // One Raw rect 2x1: pixel0 = red, pixel1 = blue (bytes are B,G,R,pad)
                o.writeByte(0); o.writeByte(0); o.writeShort(1)
                o.writeShort(0); o.writeShort(0); o.writeShort(2); o.writeShort(1); o.writeInt(0)
                o.write(byteArrayOf(0, 0, 0xFF.toByte(), 0, 0xFF.toByte(), 0, 0, 0)); o.flush()
                // Cursor pseudo-encoding: a 9x16 I-beam (x,y = hotspot; colours ignored, then the mask)
                o.writeByte(0); o.writeByte(0); o.writeShort(1)
                o.writeShort(4); o.writeShort(8); o.writeShort(9); o.writeShort(16); o.writeInt(-239)
                o.write(ByteArray(9 * 16 * 4))
                o.write(ibeamMask()); o.flush()
                var got = 0
                while (got < 2) {
                    when (i.readUnsignedByte()) {
                        3 -> i.readFully(ByteArray(9))
                        5 -> { val b = ByteArray(5); i.readFully(b); seen.add("ptr:" + b.joinToString(",") { (it.toInt() and 0xFF).toString() }); got++ }
                        4 -> { val b = ByteArray(7); i.readFully(b); seen.add("key:" + b.joinToString(",") { (it.toInt() and 0xFF).toString() }); got++ }
                        else -> { seen.add("unexpected"); return@use }
                    }
                }
            }
        }

        val client = RfbClient("127.0.0.1", server.localPort, "secret")
        client.connect(5000)
        assertEquals(2, client.width)
        assertEquals(1, client.height)
        assertEquals("testsrv", client.serverName)

        val updated = CountDownLatch(1)
        val textCursor = CountDownLatch(1)
        client.start(object : RfbClient.Listener {
            override fun onSize(width: Int, height: Int) {}
            override fun onUpdate(x: Int, y: Int, w: Int, h: Int) { updated.countDown() }
            override fun onClosed(reason: String?) {}
            override fun onCursor(isText: Boolean) { if (isText) textCursor.countDown() }
        })
        assertTrue("no framebuffer update", updated.await(5, TimeUnit.SECONDS))
        assertEquals(0xFFFF0000.toInt(), client.pixels[0])
        assertEquals(0xFF0000FF.toInt(), client.pixels[1])
        assertTrue("I-beam cursor not recognised", textCursor.await(5, TimeUnit.SECONDS))

        client.pointer(1, 1, 0)
        client.key(true, 0xFF0D)

        val events = ArrayList<String>()
        for (n in 0 until 3) events.add(seen.poll(5, TimeUnit.SECONDS) ?: fail("timed out, saw $events").let { "" })
        client.close()
        server.close()
        assertEquals("auth:ok", events[0])
        assertTrue(events.toString(), events.contains("ptr:1,0,1,0,0"))
        assertTrue(events.toString(), events.contains("key:1,0,0,0,0,255,13"))
    }

    @Test
    fun wrongPasswordIsRejected() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            server.accept().use { s ->
                val i = DataInputStream(s.getInputStream())
                val o = DataOutputStream(s.getOutputStream())
                o.write("RFB 003.008\n".toByteArray()); o.flush()
                i.readFully(ByteArray(12))
                o.write(byteArrayOf(1, 2)); o.flush()
                i.readUnsignedByte()
                o.write(ByteArray(16)); o.flush()
                i.readFully(ByteArray(16))
                val reason = "bad password".toByteArray()
                o.writeInt(1); o.writeInt(reason.size); o.write(reason); o.flush()
            }
        }
        val client = RfbClient("127.0.0.1", server.localPort, "nope")
        try {
            client.connect(5000)
            fail("expected authentication failure")
        } catch (e: RfbException) {
            assertNotNull(e.message)
            assertTrue(e.message!!, e.message!!.contains("Authentication failed"))
        } finally {
            server.close()
        }
    }
}
