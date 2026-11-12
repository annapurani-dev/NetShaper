package com.netshaper

import android.net.VpnService
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Reads raw IP packets from the TUN device and relays them to the real network through
 * "protected" sockets (which bypass the VPN), passing every packet through the [Shaper]s.
 *
 *  - UDP: one protected DatagramSocket per flow (4-tuple). Shaped per packet (rate/latency/jitter/loss).
 *  - TCP: connections are terminated locally ([TcpConn]) and re-originated with a real Socket.
 *  - IPv6: refused (TCP RST / dropped) so apps fall back to IPv4, if blockIpv6 is on.
 *  - Everything else (ICMP, ...) is dropped.
 */
class PacketForwarder(
    val vpn: VpnService,
    private val tunIn: FileInputStream,
    private val tunOut: FileOutputStream,
    private val blockIpv6: Boolean
) {
    val up = Shaper("up", true)
    val down = Shaper("down", false)
    val tcp = ConcurrentHashMap<String, TcpConn>()
    val udp = ConcurrentHashMap<String, UdpFlow>()
    val timer = ScheduledThreadPoolExecutor(1, ThreadFactory { r -> Thread(r, "fwd-timer").apply { isDaemon = true } })
    val pool: ExecutorService = Executors.newCachedThreadPool { r -> Thread(r, "fwd-io").apply { isDaemon = true } }

    @Volatile var running = true
    private val outLock = Any()

    fun writeTun(p: ByteArray) {
        try {
            synchronized(outLock) { tunOut.write(p) }
        } catch (e: IOException) {
            if (running) AppLog.log("TUN write failed: $e")
        }
    }

    /** Blocking packet loop; returns when the TUN is closed. */
    fun run() {
        val buf = ByteArray(65535)
        try {
            while (running) {
                val n = tunIn.read(buf)
                if (n < 0) break
                if (n == 0) continue
                try {
                    handle(buf, n)
                } catch (e: Exception) {
                    AppLog.log("packet error: $e")
                }
            }
        } catch (e: IOException) {
            if (running) AppLog.log("TUN read ended: $e")
        }
    }

    fun stop() {
        running = false
        for (c in tcp.values) c.close()
        for (u in udp.values) u.close()
        up.stop()
        down.stop()
        timer.shutdownNow()
        pool.shutdownNow()
    }

    private fun handle(b: ByteArray, n: Int) {
        val ver = (b[0].toInt() and 0xF0) shr 4
        if (ver == 6) {
            if (blockIpv6) handleV6(b, n)
            return
        }
        if (ver != 4 || n < 20) return
        val ihl = (b[0].toInt() and 0x0F) * 4
        val total = minOf(Pkt.u16(b, 2), n)
        if (ihl < 20 || total < ihl) return
        if ((Pkt.u16(b, 6) and 0x3FFF) != 0) return // fragments are not supported
        when (Pkt.u8(b, 9)) {
            6 -> handleTcp(b, ihl, total)
            17 -> handleUdp(b, ihl, total)
        }
    }

    private fun handleV6(b: ByteArray, n: Int) {
        if (n < 60 || Pkt.u8(b, 6) != 6) return // only plain TCP; everything else is dropped
        val flags = Pkt.u8(b, 53)
        if ((flags and 0x02) == 0 || (flags and 0x10) != 0) return // answer SYNs only
        val srcAddr = b.copyOfRange(8, 24)
        val dstAddr = b.copyOfRange(24, 40)
        val sp = Pkt.u16(b, 40)
        val dp = Pkt.u16(b, 42)
        val seq = Pkt.u32(b, 44)
        writeTun(Pkt.buildTcp6Rst(dstAddr, srcAddr, dp, sp, (seq + 1) and Pkt.M))
    }

    private fun handleUdp(b: ByteArray, ihl: Int, total: Int) {
        if (total < ihl + 8) return
        val src = b.copyOfRange(12, 16)
        val dst = b.copyOfRange(16, 20)
        val sp = Pkt.u16(b, ihl)
        val dp = Pkt.u16(b, ihl + 2)
        val dlen = Pkt.u16(b, ihl + 4) - 8
        if (dlen < 0 || ihl + 8 + dlen > total) return
        val data = b.copyOfRange(ihl + 8, ihl + 8 + dlen)
        val key = "${Pkt.ip(src)}:$sp>${Pkt.ip(dst)}:$dp"
        var flow = udp[key]
        if (flow == null) {
            flow = try {
                UdpFlow(this, key, src, sp, dst, dp)
            } catch (e: Exception) {
                AppLog.log("UDP flow $key failed: $e")
                return
            }
            udp[key] = flow
        }
        flow.send(data)
    }

    private fun handleTcp(b: ByteArray, ihl: Int, total: Int) {
        if (total < ihl + 20) return
        val src = b.copyOfRange(12, 16)
        val dst = b.copyOfRange(16, 20)
        val sp = Pkt.u16(b, ihl)
        val dp = Pkt.u16(b, ihl + 2)
        val seq = Pkt.u32(b, ihl + 4)
        val ack = Pkt.u32(b, ihl + 8)
        val off = (Pkt.u8(b, ihl + 12) shr 4) * 4
        val flags = Pkt.u8(b, ihl + 13)
        val win = Pkt.u16(b, ihl + 14)
        val ds = ihl + off
        if (off < 20 || ds > total) return
        val data = if (total > ds) b.copyOfRange(ds, total) else ByteArray(0)
        val key = "${Pkt.ip(src)}:$sp>${Pkt.ip(dst)}:$dp"
        val c = tcp[key]

        if ((flags and TcpConn.SYN) != 0 && (flags and TcpConn.ACK) == 0) {
            if (c == null) {
                val nc = TcpConn(this, key, src, sp, dst, dp, seq)
                tcp[key] = nc
                nc.start()
            }
            return // retransmitted SYN while connecting: ignore
        }
        if (c == null) {
            if ((flags and TcpConn.RST) == 0) {
                if ((flags and TcpConn.ACK) != 0) {
                    writeTun(Pkt.buildTcp(dst, src, dp, sp, ack, 0, TcpConn.RST, 0, null))
                } else {
                    val extra = data.size + (if ((flags and TcpConn.FIN) != 0) 1 else 0)
                    writeTun(Pkt.buildTcp(dst, src, dp, sp, 0, (seq + extra) and Pkt.M, TcpConn.RST or TcpConn.ACK, 0, null))
                }
            }
            return
        }
        c.onSegment(seq, ack, flags, win, data)
    }
}

/** One UDP "connection": app 4-tuple <-> protected DatagramSocket to the real server. */
class UdpFlow(
    private val fwd: PacketForwarder,
    private val key: String,
    private val appIp: ByteArray,
    private val appPort: Int,
    private val dstIp: ByteArray,
    private val dstPort: Int
) {
    private val socket = DatagramSocket()
    @Volatile private var last = System.currentTimeMillis()
    @Volatile private var closed = false
    private val idleMs = if (dstPort == 53) 10_000L else 90_000L

    init {
        if (!fwd.vpn.protect(socket)) {
            socket.close()
            throw IOException("protect() failed")
        }
        socket.connect(InetAddress.getByAddress(dstIp), dstPort)
        socket.soTimeout = 5000
        fwd.pool.execute { recvLoop() }
    }

    fun send(data: ByteArray) {
        last = System.currentTimeMillis()
        fwd.up.enqueue(data.size + 28, null, true) {
            try {
                socket.send(DatagramPacket(data, data.size))
            } catch (_: Exception) {
            }
        }
    }

    private fun recvLoop() {
        val buf = ByteArray(65535)
        try {
            while (!closed) {
                try {
                    val pk = DatagramPacket(buf, buf.size)
                    socket.receive(pk)
                    last = System.currentTimeMillis()
                    val n = pk.length
                    if (n + 28 > 1500) continue // would not fit the TUN MTU
                    val d = buf.copyOf(n)
                    fwd.down.enqueue(n + 28, null, true) {
                        fwd.writeTun(Pkt.buildUdp(dstIp, appIp, dstPort, appPort, d))
                    }
                } catch (_: SocketTimeoutException) {
                    if (System.currentTimeMillis() - last > idleMs) break
                }
            }
        } catch (_: Exception) {
        } finally {
            close()
        }
    }

    fun close() {
        closed = true
        try {
            socket.close()
        } catch (_: Exception) {
        }
        fwd.udp.remove(key, this)
    }
}

/**
 * Minimal userspace TCP endpoint facing the app, plus a real Socket facing the server.
 *
 * Simplifications (fine for a lossless local TUN):
 *  - no window scaling / SACK / timestamps; fixed 64 KB receive window; MSS 1400.
 *  - the app's data is ACKed only after it has passed the uplink shaper, so upload back-pressure is real.
 *  - no retransmission towards the app (the TUN is lossless); downlink inflight is limited by the app's window.
 *  - "loss" on TCP is emulated as extra delay, never as a real drop.
 */
class TcpConn(
    private val fwd: PacketForwarder,
    private val key: String,
    private val appIp: ByteArray,
    private val appPort: Int,
    private val dstIp: ByteArray,
    private val dstPort: Int,
    clientIsn: Long
) {
    companion object {
        const val MSS = 1400
        const val FIN = 0x01
        const val SYN = 0x02
        const val RST = 0x04
        const val PSH = 0x08
        const val ACK = 0x10
        private const val M = 0xFFFFFFFFL
        private val EMPTY = ByteArray(0)
    }

    // 0 = connecting, 1 = SYN-ACK sent, 2 = established, 3 = closed
    @Volatile private var state = 0
    private val isn: Long = ThreadLocalRandom.current().nextLong() and M
    @Volatile private var sndNxt: Long = isn
    @Volatile private var sndUna: Long = isn
    @Volatile private var rcvNxt: Long = (clientIsn + 1) and M
    @Volatile private var peerWnd = 65535
    @Volatile private var appFinned = false
    @Volatile private var srvFinned = false
    @Volatile private var socket: Socket? = null

    private val lock = ReentrantLock()
    private val ackCond = lock.newCondition()
    private val outQ = LinkedBlockingQueue<ByteArray>()
    private val downOrder = LongArray(1)
    private val upOrder = LongArray(1)

    fun start() {
        fwd.pool.execute {
            try {
                val s = Socket()
                if (!fwd.vpn.protect(s)) throw IOException("protect() failed")
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(InetAddress.getByAddress(dstIp), dstPort), 8000)
                socket = s
                sndNxt = (isn + 1) and M
                sndUna = sndNxt
                state = 1
                val seq = isn
                fwd.down.enqueue(44, downOrder, false) {
                    fwd.writeTun(Pkt.buildTcp(dstIp, appIp, dstPort, appPort, seq, rcvNxt, SYN or ACK, 65535, null, MSS))
                }
            } catch (_: Exception) {
                // connect refused / timed out: tell the app
                fwd.writeTun(Pkt.buildTcp(dstIp, appIp, dstPort, appPort, 0, rcvNxt, RST or ACK, 0, null))
                close()
            }
        }
    }

    /** Called from the TUN thread for every segment the app sends on this connection. */
    fun onSegment(seq: Long, ackNum: Long, flags: Int, win: Int, data: ByteArray) {
        if ((flags and RST) != 0) {
            close()
            return
        }
        if (state == 0 || state == 3) return
        if ((flags and ACK) != 0) {
            peerWnd = win
            lock.withLock {
                val d = ((ackNum - sndUna) and M).toInt()
                if (d > 0) sndUna = ackNum
                if (state == 1 && ackNum == sndNxt) {
                    state = 2
                    fwd.pool.execute { writerLoop() }
                    fwd.pool.execute { readerLoop() }
                }
                ackCond.signalAll()
            }
        }
        if (state < 2) return
        if (data.isNotEmpty()) acceptData(seq, data)
        if ((flags and FIN) != 0) acceptFin((seq + data.size) and M)
    }

    private fun acceptData(seq: Long, data0: ByteArray) {
        var data = data0
        val diff = ((seq - rcvNxt) and M).toInt()
        if (diff < 0) {
            val skip = -diff
            if (skip >= data.size) return // pure retransmission: our (delayed) ACK is still on its way
            data = data.copyOfRange(skip, data.size)
        } else if (diff > 0) {
            return // gap; cannot happen on a lossless TUN
        }
        rcvNxt = (rcvNxt + data.size) and M
        val end = rcvNxt
        val chunk = data
        fwd.up.enqueue(chunk.size + 40, upOrder, false) {
            outQ.add(chunk)
            sendAck(end)
        }
    }

    private fun acceptFin(finSeq: Long) {
        if (appFinned || finSeq != rcvNxt) return
        appFinned = true
        rcvNxt = (rcvNxt + 1) and M
        val end = rcvNxt
        fwd.up.enqueue(40, upOrder, false) {
            outQ.add(EMPTY)
            sendAck(end)
            checkDone()
        }
    }

    private fun sendAck(ackSeq: Long) {
        fwd.writeTun(Pkt.buildTcp(dstIp, appIp, dstPort, appPort, sndNxt, ackSeq, ACK, 65535, null))
    }

    private fun writerLoop() {
        try {
            val os = socket!!.getOutputStream()
            while (true) {
                val d = outQ.take()
                if (state == 3) return
                if (d.isEmpty()) {
                    try {
                        socket?.shutdownOutput()
                    } catch (_: Exception) {
                    }
                    return
                }
                os.write(d)
            }
        } catch (_: Exception) {
            if (state != 3) resetApp()
        }
    }

    private fun readerLoop() {
        val buf = ByteArray(MSS)
        try {
            val ins = socket!!.getInputStream()
            while (state != 3) {
                waitWindow()
                if (state == 3) return
                val n = ins.read(buf)
                if (n < 0) {
                    sendFin()
                    return
                }
                if (n == 0) continue
                fwd.down.awaitSpace()
                val seg = buf.copyOf(n)
                val seq = sndNxt
                sndNxt = (sndNxt + n) and M
                fwd.down.enqueue(n + 40, downOrder, false) {
                    fwd.writeTun(Pkt.buildTcp(dstIp, appIp, dstPort, appPort, seq, rcvNxt, ACK or PSH, 65535, seg))
                }
            }
        } catch (_: Exception) {
            if (state != 3) resetApp()
        }
    }

    /** Honour the app's advertised receive window so its kernel never has to drop our segments. */
    private fun waitWindow() {
        while (state != 3) {
            val inflight = ((sndNxt - sndUna) and M).toInt()
            val wnd = minOf(maxOf(peerWnd, 0), 65535)
            if (inflight <= 0 || inflight + MSS <= wnd) return
            lock.withLock { ackCond.await(100, TimeUnit.MILLISECONDS) }
        }
    }

    private fun sendFin() {
        srvFinned = true
        val seq = sndNxt
        sndNxt = (sndNxt + 1) and M
        fwd.down.enqueue(40, downOrder, false) {
            fwd.writeTun(Pkt.buildTcp(dstIp, appIp, dstPort, appPort, seq, rcvNxt, FIN or ACK, 65535, null))
            checkDone()
        }
    }

    private fun checkDone() {
        if (srvFinned && appFinned) {
            try {
                fwd.timer.schedule({ close() }, 3, TimeUnit.SECONDS)
            } catch (_: Exception) {
            }
        }
    }

    private fun resetApp() {
        fwd.writeTun(Pkt.buildTcp(dstIp, appIp, dstPort, appPort, sndNxt, rcvNxt, RST or ACK, 0, null))
        close()
    }

    fun close() {
        synchronized(this) {
            if (state == 3) return
            state = 3
        }
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        outQ.offer(EMPTY)
        lock.withLock { ackCond.signalAll() }
        fwd.tcp.remove(key, this)
    }
}
