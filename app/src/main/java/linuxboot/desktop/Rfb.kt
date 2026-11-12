package linuxboot.desktop

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class RfbException(message: String) : IOException(message)

/**
 * Minimal RFB 3.8 client for the local desktop: VNC authentication, Raw, CopyRect and
 * DesktopSize encodings, pointer and key input. Written for loopback use only.
 */
class RfbClient(private val host: String, private val port: Int, private val password: String) {

    interface Listener {
        fun onSize(width: Int, height: Int)
        fun onUpdate(x: Int, y: Int, w: Int, h: Int)
        fun onClosed(reason: String?)

        /** The server changed the pointer shape; [isText] is true for an I-beam (text) cursor. */
        fun onCursor(isText: Boolean) {}

        /** The pointer shape itself (mask only), called just before [onCursor]. */
        fun onCursorShape(w: Int, h: Int, mask: BooleanArray) {}
    }

    @Volatile
    var width = 0
        private set

    @Volatile
    var height = 0
        private set

    @Volatile
    var pixels = IntArray(0)
        private set

    @Volatile
    var serverName = ""
        private set

    private var socket: Socket? = null
    private lateinit var input: DataInputStream
    private lateinit var output: DataOutputStream
    private val writeLock = Any()

    @Volatile
    private var closed = false
    private val sender = Executors.newSingleThreadExecutor { r -> Thread(r, "rfb-send").apply { isDaemon = true } }

    /** Blocking handshake. Call off the main thread. */
    fun connect(timeoutMs: Int = 10_000) {
        val s = Socket()
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(host, port), timeoutMs)
        s.soTimeout = timeoutMs
        socket = s
        input = DataInputStream(BufferedInputStream(s.getInputStream(), 1 shl 16))
        output = DataOutputStream(s.getOutputStream())
        try {
            handshake()
        } catch (e: Exception) {
            close()
            throw e
        }
        s.soTimeout = 0
    }

    private fun readReason(): String {
        val n = input.readInt()
        if (n < 0 || n > 4096) return ""
        val b = ByteArray(n)
        input.readFully(b)
        return String(b, Charsets.UTF_8)
    }

    private fun handshake() {
        val ver = ByteArray(12)
        input.readFully(ver)
        val v = String(ver, Charsets.US_ASCII)
        if (!v.startsWith("RFB 003.")) throw RfbException("Not an RFB server: ${v.trim()}")
        val minor = v.substring(8, 11).toIntOrNull() ?: 0
        if (minor < 7) throw RfbException("RFB version too old: ${v.trim()}")
        val ours = if (minor >= 8) "RFB 003.008" else "RFB 003.007"
        output.write((ours + System.lineSeparator()).toByteArray(Charsets.US_ASCII))
        output.flush()

        val n = input.readUnsignedByte()
        if (n == 0) throw RfbException("Server refused the connection: ${readReason()}")
        val types = ByteArray(n)
        input.readFully(types)
        if (types.none { it.toInt() == 2 }) throw RfbException("Server does not offer VNC authentication")
        output.writeByte(2)
        output.flush()

        val challenge = ByteArray(16)
        input.readFully(challenge)
        output.write(encryptChallenge(challenge))
        output.flush()
        if (input.readInt() != 0) {
            val reason = try { readReason() } catch (e: IOException) { "" }
            throw RfbException("Authentication failed" + if (reason.isNotEmpty()) ": $reason" else "")
        }

        output.writeByte(1) // shared
        output.flush()
        width = input.readUnsignedShort()
        height = input.readUnsignedShort()
        input.readFully(ByteArray(16)) // server pixel format; we override it below
        val nameLen = input.readInt()
        if (nameLen < 0 || nameLen > 4096) throw RfbException("Bad server name length")
        val name = ByteArray(nameLen)
        input.readFully(name)
        serverName = String(name, Charsets.UTF_8)
        pixels = IntArray(width * height)

        synchronized(writeLock) {
            // SetPixelFormat: 32 bpp, depth 24, little endian, true colour, R<<16 G<<8 B
            output.writeByte(0)
            output.write(ByteArray(3))
            output.writeByte(32); output.writeByte(24); output.writeByte(0); output.writeByte(1)
            output.writeShort(255); output.writeShort(255); output.writeShort(255)
            output.writeByte(16); output.writeByte(8); output.writeByte(0)
            output.write(ByteArray(3))
            // SetEncodings: CopyRect, Raw, DesktopSize
            output.writeByte(2); output.writeByte(0); output.writeShort(4)
            output.writeInt(1); output.writeInt(0); output.writeInt(-223); output.writeInt(-239)
            output.flush()
        }
        requestUpdate(false)
    }

    private fun requestUpdate(incremental: Boolean) {
        synchronized(writeLock) {
            output.writeByte(3)
            output.writeByte(if (incremental) 1 else 0)
            output.writeShort(0); output.writeShort(0)
            output.writeShort(width); output.writeShort(height)
            output.flush()
        }
    }

    fun start(listener: Listener) {
        val t = Thread({
            var reason: String? = null
            try {
                listener.onSize(width, height)
                readLoop(listener)
            } catch (e: Exception) {
                if (!closed) reason = e.message ?: e.javaClass.simpleName
            }
            close()
            listener.onClosed(reason)
        }, "rfb-reader")
        t.isDaemon = true
        t.start()
    }

    private fun skipFully(n: Long) {
        var left = n
        val buf = ByteArray(4096)
        while (left > 0) {
            val r = input.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
            if (r < 0) throw IOException("Connection closed")
            left -= r
        }
    }

    private fun readLoop(listener: Listener) {
        while (!closed) {
            when (val type = input.readUnsignedByte()) {
                0 -> readUpdate(listener)
                1 -> throw RfbException("Colour maps are not supported")
                2 -> Unit // bell
                3 -> {
                    input.readFully(ByteArray(3))
                    skipFully(input.readInt().toLong() and 0xFFFFFFFFL)
                }
                else -> throw RfbException("Unknown server message $type")
            }
        }
    }

    private fun readUpdate(listener: Listener) {
        input.readUnsignedByte()
        val count = input.readUnsignedShort()
        var needFull = false
        repeat(count) {
            val x = input.readUnsignedShort()
            val y = input.readUnsignedShort()
            val w = input.readUnsignedShort()
            val h = input.readUnsignedShort()
            when (val enc = input.readInt()) {
                0 -> {
                    checkBounds(x, y, w, h)
                    readRaw(x, y, w, h)
                    listener.onUpdate(x, y, w, h)
                }
                1 -> {
                    val sx = input.readUnsignedShort()
                    val sy = input.readUnsignedShort()
                    checkBounds(x, y, w, h)
                    checkBounds(sx, sy, w, h)
                    copyRect(sx, sy, x, y, w, h)
                    listener.onUpdate(x, y, w, h)
                }
                -223 -> {
                    if (w <= 0 || h <= 0 || w.toLong() * h > 64L * 1024 * 1024) throw RfbException("Bad desktop size ${w}x$h")
                    pixels = IntArray(w * h)
                    width = w
                    height = h
                    listener.onSize(w, h)
                    needFull = true
                }
                -239 -> readCursor(w, h, listener)
                else -> throw RfbException("Unsupported encoding $enc")
            }
        }
        if (needFull) requestUpdate(false) else requestUpdate(true)
    }

    /** Cursor pseudo-encoding: w*h pixels in our pixel format, then a 1-bit mask (rows padded to bytes). */
    private fun readCursor(w: Int, h: Int, listener: Listener) {
        val pixelBytes = w.toLong() * h * 4
        val maskStride = (w + 7) / 8
        val maskBytes = maskStride.toLong() * h
        if (w > 256 || h > 256) {
            skipFully(pixelBytes + maskBytes)
            listener.onCursor(false)
            return
        }
        skipFully(pixelBytes) // colours are not needed to recognise the shape
        val mask = ByteArray(maskBytes.toInt())
        input.readFully(mask)
        val bits = BooleanArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                bits[y * w + x] = (mask[y * maskStride + (x shr 3)].toInt() and (0x80 shr (x and 7))) != 0
            }
        }
        listener.onCursorShape(w, h, bits)
        listener.onCursor(CursorShape.isTextCursor(bits, w, h))
    }

    private fun checkBounds(x: Int, y: Int, w: Int, h: Int) {
        if (x + w > width || y + h > height) throw RfbException("Rectangle outside framebuffer")
    }

    private fun readRaw(x: Int, y: Int, w: Int, h: Int) {
        val row = ByteArray(w * 4)
        val px = pixels
        for (r in 0 until h) {
            input.readFully(row)
            var o = (y + r) * width + x
            var i = 0
            for (c in 0 until w) {
                val b = row[i].toInt() and 0xFF
                val g = row[i + 1].toInt() and 0xFF
                val rr = row[i + 2].toInt() and 0xFF
                px[o++] = (0xFF shl 24) or (rr shl 16) or (g shl 8) or b
                i += 4
            }
        }
    }

    private fun copyRect(sx: Int, sy: Int, dx: Int, dy: Int, w: Int, h: Int) {
        val px = pixels
        val tmp = IntArray(w * h)
        for (r in 0 until h) System.arraycopy(px, (sy + r) * width + sx, tmp, r * w, w)
        for (r in 0 until h) System.arraycopy(tmp, r * w, px, (dy + r) * width + dx, w)
    }

    private fun send(block: () -> Unit) {
        if (closed) return
        sender.execute {
            try {
                synchronized(writeLock) {
                    block()
                    output.flush()
                }
            } catch (e: IOException) {
                close()
            }
        }
    }

    /** Button mask: 1 left, 2 middle, 4 right, 8 wheel up, 16 wheel down. */
    fun pointer(mask: Int, x: Int, y: Int) = send {
        output.writeByte(5)
        output.writeByte(mask)
        output.writeShort(x.coerceIn(0, maxOf(0, width - 1)))
        output.writeShort(y.coerceIn(0, maxOf(0, height - 1)))
    }

    fun key(down: Boolean, keysym: Int) = send {
        output.writeByte(4)
        output.writeByte(if (down) 1 else 0)
        output.writeShort(0)
        output.writeInt(keysym)
    }

    fun close() {
        if (closed) return
        closed = true
        sender.shutdownNow()
        try { socket?.close() } catch (e: IOException) { }
    }

    private fun encryptChallenge(challenge: ByteArray): ByteArray {
        val key = ByteArray(8)
        val pw = password.toByteArray(Charsets.ISO_8859_1)
        for (i in 0 until 8) {
            val b = if (i < pw.size) pw[i].toInt() and 0xFF else 0
            key[i] = (Integer.reverse(b) ushr 24).toByte() // VNC uses bit-reversed DES key bytes
        }
        val c = Cipher.getInstance("DES/ECB/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "DES"))
        return c.doFinal(challenge)
    }
}
