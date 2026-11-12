package com.netshaper

import java.util.concurrent.atomic.AtomicInteger

/** Raw IPv4/IPv6 packet helpers: field access, checksums and packet builders. */
object Pkt {
    const val M = 0xFFFFFFFFL
    private val idCounter = AtomicInteger(1)

    fun u8(b: ByteArray, i: Int): Int = b[i].toInt() and 0xFF
    fun u16(b: ByteArray, i: Int): Int = (u8(b, i) shl 8) or u8(b, i + 1)
    fun u32(b: ByteArray, i: Int): Long = (u16(b, i).toLong() shl 16) or u16(b, i + 2).toLong()

    fun put16(b: ByteArray, i: Int, v: Int) {
        b[i] = (v shr 8).toByte()
        b[i + 1] = v.toByte()
    }

    fun put32(b: ByteArray, i: Int, v: Long) {
        put16(b, i, ((v shr 16) and 0xFFFFL).toInt())
        put16(b, i + 2, (v and 0xFFFFL).toInt())
    }

    fun ip(a: ByteArray): String = "${u8(a, 0)}.${u8(a, 1)}.${u8(a, 2)}.${u8(a, 3)}"

    /** Sum of 16-bit big-endian words (not folded, not inverted). */
    fun sum(b: ByteArray, off: Int, len: Int): Long {
        var s = 0L
        var i = off
        val end = off + len
        while (i + 1 < end) {
            s += ((u8(b, i) shl 8) or u8(b, i + 1)).toLong()
            i += 2
        }
        if (i < end) s += (u8(b, i) shl 8).toLong()
        return s
    }

    fun fold(s0: Long): Int {
        var s = s0
        while ((s shr 16) != 0L) s = (s and 0xFFFFL) + (s shr 16)
        return (s.inv() and 0xFFFFL).toInt()
    }

    private fun pseudo4(src: ByteArray, dst: ByteArray, proto: Int, len: Int): Long =
        sum(src, 0, 4) + sum(dst, 0, 4) + proto.toLong() + len.toLong()

    private fun pseudo6(src: ByteArray, dst: ByteArray, proto: Int, len: Int): Long =
        sum(src, 0, 16) + sum(dst, 0, 16) + proto.toLong() + len.toLong()

    private fun newIpv4(l4len: Int, proto: Int, src: ByteArray, dst: ByteArray): ByteArray {
        val p = ByteArray(20 + l4len)
        p[0] = 0x45
        put16(p, 2, p.size)
        put16(p, 4, idCounter.incrementAndGet() and 0xFFFF)
        put16(p, 6, 0x4000) // DF
        p[8] = 64
        p[9] = proto.toByte()
        System.arraycopy(src, 0, p, 12, 4)
        System.arraycopy(dst, 0, p, 16, 4)
        put16(p, 10, fold(sum(p, 0, 20)))
        return p
    }

    /** Complete IPv4+TCP packet. [mss] > 0 adds an MSS option (SYN-ACK only). */
    fun buildTcp(
        src: ByteArray, dst: ByteArray, sp: Int, dp: Int,
        seq: Long, ack: Long, flags: Int, win: Int,
        data: ByteArray?, mss: Int = 0
    ): ByteArray {
        val dlen = data?.size ?: 0
        val thl = if (mss > 0) 24 else 20
        val tcpLen = thl + dlen
        val p = newIpv4(tcpLen, 6, src, dst)
        val o = 20
        put16(p, o, sp)
        put16(p, o + 2, dp)
        put32(p, o + 4, seq and M)
        put32(p, o + 8, ack and M)
        p[o + 12] = ((thl / 4) shl 4).toByte()
        p[o + 13] = flags.toByte()
        put16(p, o + 14, win)
        if (mss > 0) {
            p[o + 20] = 2
            p[o + 21] = 4
            put16(p, o + 22, mss)
        }
        if (data != null && dlen > 0) System.arraycopy(data, 0, p, o + thl, dlen)
        put16(p, o + 16, fold(pseudo4(src, dst, 6, tcpLen) + sum(p, o, tcpLen)))
        return p
    }

    /** Complete IPv4+UDP packet. */
    fun buildUdp(src: ByteArray, dst: ByteArray, sp: Int, dp: Int, data: ByteArray): ByteArray {
        val udpLen = 8 + data.size
        val p = newIpv4(udpLen, 17, src, dst)
        val o = 20
        put16(p, o, sp)
        put16(p, o + 2, dp)
        put16(p, o + 4, udpLen)
        System.arraycopy(data, 0, p, o + 8, data.size)
        var c = fold(pseudo4(src, dst, 17, udpLen) + sum(p, o, udpLen))
        if (c == 0) c = 0xFFFF
        put16(p, o + 6, c)
        return p
    }

    /** IPv6 TCP RST|ACK answering a SYN, so IPv6 connection attempts fail fast. */
    fun buildTcp6Rst(src: ByteArray, dst: ByteArray, sp: Int, dp: Int, ack: Long): ByteArray {
        val p = ByteArray(60)
        p[0] = 0x60
        put16(p, 4, 20)
        p[6] = 6
        p[7] = 64
        System.arraycopy(src, 0, p, 8, 16)
        System.arraycopy(dst, 0, p, 24, 16)
        put16(p, 40, sp)
        put16(p, 42, dp)
        put32(p, 44, 0L)
        put32(p, 48, ack and M)
        p[52] = (5 shl 4).toByte()
        p[53] = 0x14 // RST|ACK
        put16(p, 56, fold(pseudo6(src, dst, 6, 20) + sum(p, 40, 20)))
        return p
    }
}
