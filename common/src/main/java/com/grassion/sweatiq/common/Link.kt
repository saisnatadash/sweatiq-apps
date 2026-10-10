package com.grassion.sweatiq.common

import android.os.Handler
import android.os.Looper
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import javax.net.ssl.SSLSocketFactory

/**
 * Minimal MQTT 3.1.1 client (QoS 0) over a plain TCP or TLS socket.
 * It needs no third-party library and no account: both apps talk to the same free public broker.
 */
class MqttLite(
    private val clientId: String,
    private val onMessage: (String, String) -> Unit,
    private val onStatus: (Boolean, String) -> Unit
) {
    class Endpoint(val host: String, val port: Int, val tls: Boolean) {
        val label: String get() = host + (if (tls) " (TLS)" else "")
    }

    companion object {
        // Both apps walk this list in the same order, so they meet on the same broker.
        val ENDPOINTS = listOf(
            Endpoint("broker.hivemq.com", 1883, false),
            Endpoint("broker.hivemq.com", 8883, true),
            Endpoint("broker.emqx.io", 1883, false),
            Endpoint("broker.emqx.io", 8883, true)
        )
    }

    @Volatile private var running = false
    @Volatile private var connected = false
    @Volatile private var socket: Socket? = null
    private var out: OutputStream? = null
    private val lock = Any()
    private val subs = CopyOnWriteArrayList<String>()
    private val writer = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var worker: Thread? = null
    private var pinger: Thread? = null

    fun start() {
        if (running) return
        running = true
        worker = Thread { loop() }.also { it.isDaemon = true; it.start() }
        pinger = Thread {
            while (running) {
                try { Thread.sleep(15000) } catch (e: InterruptedException) { return@Thread }
                if (connected) write(byteArrayOf(0xC0.toByte(), 0x00))
            }
        }.also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        running = false
        try { socket?.close() } catch (e: Exception) { }
        pinger?.interrupt()
    }

    fun isConnected(): Boolean = connected

    fun subscribe(topic: String) {
        if (!subs.contains(topic)) subs.add(topic)
        if (connected) writer.execute { sendSubscribe(topic) }
    }

    fun publish(topic: String, payload: String, retain: Boolean = false, done: ((Boolean) -> Unit)? = null) {
        writer.execute {
            var ok = false
            if (connected) {
                val t = topic.toByteArray(Charsets.UTF_8)
                val p = payload.toByteArray(Charsets.UTF_8)
                val body = ByteArrayOutputStream()
                body.write(t.size shr 8)
                body.write(t.size and 0xFF)
                body.write(t)
                body.write(p)
                val pkt = ByteArrayOutputStream()
                pkt.write(0x30 or (if (retain) 1 else 0))
                writeLen(pkt, body.size())
                pkt.write(body.toByteArray())
                ok = write(pkt.toByteArray())
            }
            if (done != null) main.post { done(ok) }
        }
    }

    // ---------- internals ----------

    private fun write(b: ByteArray): Boolean {
        return try {
            synchronized(lock) {
                val o = out ?: return false
                o.write(b)
                o.flush()
            }
            true
        } catch (e: Exception) {
            try { socket?.close() } catch (x: Exception) { }
            false
        }
    }

    private fun writeLen(o: ByteArrayOutputStream, len: Int) {
        var x = len
        do {
            var d = x % 128
            x /= 128
            if (x > 0) d = d or 0x80
            o.write(d)
        } while (x > 0)
    }

    private fun readLen(i: DataInputStream): Int {
        var mult = 1
        var value = 0
        while (true) {
            val d = i.readUnsignedByte()
            value += (d and 0x7F) * mult
            if (d and 0x80 == 0) break
            mult *= 128
            if (mult > 128 * 128 * 128 * 128) throw IOException("bad length")
        }
        return value
    }

    private fun sendSubscribe(topic: String) {
        val t = topic.toByteArray(Charsets.UTF_8)
        val body = ByteArrayOutputStream()
        body.write(0)
        body.write(1) // packet id
        body.write(t.size shr 8)
        body.write(t.size and 0xFF)
        body.write(t)
        body.write(0) // QoS 0
        val pkt = ByteArrayOutputStream()
        pkt.write(0x82)
        writeLen(pkt, body.size())
        pkt.write(body.toByteArray())
        write(pkt.toByteArray())
    }

    private fun sendConnect() {
        val id = clientId.toByteArray(Charsets.UTF_8)
        val body = ByteArrayOutputStream()
        body.write(byteArrayOf(0, 4, 'M'.code.toByte(), 'Q'.code.toByte(), 'T'.code.toByte(), 'T'.code.toByte(), 4))
        body.write(0x02) // clean session
        body.write(0)
        body.write(30) // keep-alive seconds
        body.write(id.size shr 8)
        body.write(id.size and 0xFF)
        body.write(id)
        val pkt = ByteArrayOutputStream()
        pkt.write(0x10)
        writeLen(pkt, body.size())
        pkt.write(body.toByteArray())
        write(pkt.toByteArray())
    }

    private fun open(ep: Endpoint): Socket {
        val raw = Socket()
        raw.connect(InetSocketAddress(ep.host, ep.port), 6000)
        raw.tcpNoDelay = true
        if (!ep.tls) return raw
        val s = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, ep.host, ep.port, true)
        (s as javax.net.ssl.SSLSocket).startHandshake()
        return s
    }

    private fun loop() {
        var idx = 0
        var backoff = 1000L
        while (running) {
            val ep = ENDPOINTS[idx % ENDPOINTS.size]
            var wasUp = false
            try {
                val s = open(ep)
                socket = s
                s.soTimeout = 45000
                val input = DataInputStream(BufferedInputStream(s.getInputStream()))
                synchronized(lock) { out = s.getOutputStream() }
                sendConnect()
                val first = input.readUnsignedByte()
                if ((first shr 4) != 2) throw IOException("no CONNACK")
                val ackLen = readLen(input)
                val ack = ByteArray(ackLen)
                input.readFully(ack)
                if (ack.size < 2 || ack[1].toInt() != 0) throw IOException("refused")
                connected = true
                wasUp = true
                backoff = 1000L
                main.post { onStatus(true, ep.label) }
                for (t in subs) sendSubscribe(t)
                while (running) {
                    val b = input.readUnsignedByte()
                    val type = b shr 4
                    val len = readLen(input)
                    val data = ByteArray(len)
                    input.readFully(data)
                    if (type == 3) {
                        val tl = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                        val topic = String(data, 2, tl, Charsets.UTF_8)
                        var off = 2 + tl
                        val qos = (b shr 1) and 3
                        if (qos > 0) off += 2
                        val payload = String(data, off, data.size - off, Charsets.UTF_8)
                        main.post { onMessage(topic, payload) }
                    }
                }
            } catch (e: Exception) {
                // fall through to reconnect
            } finally {
                try { socket?.close() } catch (e: Exception) { }
                synchronized(lock) { out = null }
                if (connected) {
                    connected = false
                    main.post { onStatus(false, ep.label) }
                }
            }
            if (!running) break
            if (!wasUp) idx++
            try { Thread.sleep(backoff) } catch (e: InterruptedException) { break }
            backoff = (backoff * 2).coerceAtMost(5000L)
        }
    }
}

/** One live session channel between the Device app and the Control app. */
class Link(
    private val code: String,
    role: String,
    kinds: List<String>,
    private val onStatus: (Boolean, String) -> Unit,
    private val onMsg: (String, String) -> Unit
) {
    companion object {
        const val ROOT = "sweatiq-grassion-demo/v1"
    }

    private val client = MqttLite(
        "sweatiq-" + role + "-" + System.currentTimeMillis() % 1000000 + "-" + (Math.random() * 9999).toInt(),
        { topic, payload ->
            val kind = topic.substringAfterLast('/')
            onMsg(kind, payload)
        },
        { up, label -> onStatus(up, label) }
    )

    init {
        for (k in kinds) client.subscribe("$ROOT/$code/$k")
    }

    fun start() = client.start()
    fun stop() = client.stop()
    fun isUp(): Boolean = client.isConnected()

    fun send(kind: String, payload: String, retain: Boolean = false, done: ((Boolean) -> Unit)? = null) {
        client.publish("$ROOT/$code/$kind", payload, retain, done)
    }
}
