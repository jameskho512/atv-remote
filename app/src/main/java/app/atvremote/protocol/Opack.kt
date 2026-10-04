package app.atvremote.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Apple's OPACK serialization, as used by the Companion protocol.
 *
 * Kotlin types: null, Boolean, Int/Long (non-negative), Float/Double, String, ByteArray,
 * UUID, List<*>, Map<*, *>. Decoding returns Long for integers and LinkedHashMap for dicts.
 */
object Opack {

    fun pack(value: Any?): ByteArray = ByteArrayOutputStream().also { write(it, value) }.toByteArray()

    private fun write(out: ByteArrayOutputStream, value: Any?) {
        when (value) {
            null -> out.write(0x04)
            is Boolean -> out.write(if (value) 0x01 else 0x02)
            is UUID -> {
                out.write(0x05)
                out.write(ByteBuffer.allocate(16).putLong(value.mostSignificantBits).putLong(value.leastSignificantBits).array())
            }
            is Int -> writeInt(out, value.toLong())
            is Long -> writeInt(out, value)
            is Float, is Double -> {
                out.write(0x36)
                out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble((value as Number).toDouble()).array())
            }
            is String -> {
                val b = value.toByteArray(Charsets.UTF_8)
                if (b.size <= 0x20) out.write(0x40 + b.size) else writeLength(out, 0x60, b.size)
                out.write(b)
            }
            is ByteArray -> {
                if (value.size <= 0x20) out.write(0x70 + value.size) else writeLength(out, 0x90, value.size)
                out.write(value)
            }
            is List<*> -> {
                out.write(0xD0 + minOf(value.size, 0xF))
                value.forEach { write(out, it) }
                if (value.size >= 0xF) out.write(0x03)
            }
            is Map<*, *> -> {
                out.write(0xE0 + minOf(value.size, 0xF))
                value.forEach { (k, v) -> write(out, k); write(out, v) }
                if (value.size >= 0xF) out.write(0x03)
            }
            else -> throw IllegalArgumentException("OPACK cannot encode ${value::class}")
        }
    }

    private fun writeInt(out: ByteArrayOutputStream, v: Long) {
        require(v >= 0) { "negative integers are not supported" }
        when {
            v < 0x28 -> out.write((v + 8).toInt())
            v <= 0xFF -> { out.write(0x30); writeLe(out, v, 1) }
            v <= 0xFFFF -> { out.write(0x31); writeLe(out, v, 2) }
            v <= 0xFFFFFFFFL -> { out.write(0x32); writeLe(out, v, 4) }
            else -> { out.write(0x33); writeLe(out, v, 8) }
        }
    }

    /** Sized length prefix: base+1 → 1 byte, base+2 → 2 bytes, then 4 bytes (0x64 for strings, 0x93 for data). */
    private fun writeLength(out: ByteArrayOutputStream, base: Int, len: Int) {
        when {
            len <= 0xFF -> { out.write(base + 1); writeLe(out, len.toLong(), 1) }
            len <= 0xFFFF -> { out.write(base + 2); writeLe(out, len.toLong(), 2) }
            else -> { out.write(if (base == 0x60) 0x64 else 0x93); writeLe(out, len.toLong(), 4) }
        }
    }

    private fun writeLe(out: ByteArrayOutputStream, v: Long, n: Int) {
        for (i in 0 until n) out.write(((v ushr (8 * i)) and 0xFF).toInt())
    }

    fun unpack(data: ByteArray): Any? = Reader(data).read()

    private class Reader(val d: ByteArray) {
        var p = 0
        val objects = ArrayList<Any?>()

        fun u8() = d[p++].toInt() and 0xFF
        fun le(n: Int): Long {
            var v = 0L
            for (i in 0 until n) v = v or ((d[p + i].toLong() and 0xFF) shl (8 * i))
            p += n
            return v
        }
        fun bytes(n: Int): ByteArray = d.copyOfRange(p, p + n).also { p += n }

        fun read(): Any? {
            val t = u8()
            var remember = true
            val value: Any? = when {
                t == 0x01 -> { remember = false; true }
                t == 0x02 -> { remember = false; false }
                t == 0x04 -> { remember = false; null }
                t == 0x05 -> { val b = ByteBuffer.wrap(bytes(16)); UUID(b.long, b.long) }
                t == 0x06 -> le(8) // absolute time, kept as raw integer
                t in 0x08..0x2F -> { remember = false; (t - 8).toLong() }
                t == 0x35 -> ByteBuffer.wrap(bytes(4)).order(ByteOrder.LITTLE_ENDIAN).float.toDouble()
                t == 0x36 -> ByteBuffer.wrap(bytes(8)).order(ByteOrder.LITTLE_ENDIAN).double
                t and 0xF0 == 0x30 -> le(1 shl (t and 0xF))
                t in 0x40..0x60 -> String(bytes(t - 0x40), Charsets.UTF_8)
                t in 0x61..0x64 -> String(bytes(le(t and 0xF).toInt()), Charsets.UTF_8)
                t in 0x70..0x90 -> bytes(t - 0x70)
                t in 0x91..0x94 -> bytes(le(1 shl ((t and 0xF) - 1)).toInt())
                t and 0xF0 == 0xD0 -> {
                    remember = false
                    val count = t and 0xF
                    val list = ArrayList<Any?>()
                    if (count == 0xF) {
                        while (d[p].toInt() != 0x03) list += read()
                        p++
                    } else repeat(count) { list += read() }
                    list
                }
                t and 0xE0 == 0xE0 -> {
                    remember = false
                    val count = t and 0xF
                    val map = LinkedHashMap<Any?, Any?>()
                    if (count == 0xF) {
                        while (d[p].toInt() != 0x03) { val k = read(); map[k] = read() }
                        p++
                    } else repeat(count) { val k = read(); map[k] = read() }
                    map
                }
                t in 0xA0..0xC0 -> objects[t - 0xA0]
                t in 0xC1..0xC4 -> objects[le(t - 0xC0).toInt()]
                else -> throw IllegalArgumentException("unknown OPACK type 0x%02x".format(t))
            }
            if (remember && objects.none { same(it, value) }) objects += value
            return value
        }

        fun same(a: Any?, b: Any?) =
            if (a is ByteArray && b is ByteArray) a.contentEquals(b) else a == b
    }
}

/** Typed accessors for decoded OPACK maps. */
@Suppress("UNCHECKED_CAST")
fun Any?.asMap(): Map<String, Any?> = (this as? Map<String, Any?>) ?: emptyMap()
fun Any?.asLong(): Long? = (this as? Number)?.toLong()
