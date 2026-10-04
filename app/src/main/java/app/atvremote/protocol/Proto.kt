package app.atvremote.protocol

import java.io.ByteArrayOutputStream

/** Decoded protobuf message: field number → values (Long for varint/fixed, ByteArray for length-delimited). */
class ProtoMsg(private val fields: Map<Int, List<Any>>) {
    fun has(n: Int) = fields.containsKey(n)
    fun long(n: Int): Long? = fields[n]?.lastOrNull() as? Long
    fun int(n: Int): Int? = long(n)?.toInt()
    fun bool(n: Int): Boolean? = long(n)?.let { it != 0L }
    fun bytes(n: Int): ByteArray? = fields[n]?.lastOrNull() as? ByteArray
    fun string(n: Int): String? = bytes(n)?.let { String(it, Charsets.UTF_8) }
    fun double(n: Int): Double? = long(n)?.let { java.lang.Double.longBitsToDouble(it) }
    fun float(n: Int): Float? = long(n)?.let { java.lang.Float.intBitsToFloat(it.toInt()) }
    /** Nested message; repeated occurrences merge (protobuf semantics), so concatenated updates read correctly. */
    fun msg(n: Int): ProtoMsg? = fields[n]?.filterIsInstance<ByteArray>()?.takeIf { it.isNotEmpty() }
        ?.let { parts -> Proto.decode(parts.reduce { a, b -> a + b }) }
    fun rawRepeated(n: Int): List<ByteArray> = fields[n].orEmpty().filterIsInstance<ByteArray>()
    fun msgs(n: Int): List<ProtoMsg> = fields[n].orEmpty().mapNotNull { (it as? ByteArray)?.let(Proto::decode) }
}

object Proto {
    fun decode(d: ByteArray): ProtoMsg {
        val fields = LinkedHashMap<Int, MutableList<Any>>()
        var p = 0
        fun varint(): Long {
            var v = 0L; var shift = 0
            while (true) {
                val b = d[p++].toInt() and 0xFF
                v = v or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return v
                shift += 7
            }
        }
        fun le(n: Int): Long { var v = 0L; for (i in 0 until n) v = v or ((d[p + i].toLong() and 0xFF) shl (8 * i)); p += n; return v }
        while (p < d.size) {
            val key = varint()
            val field = (key ushr 3).toInt()
            val value: Any = when ((key and 7).toInt()) {
                0 -> varint()
                1 -> le(8)
                2 -> { val n = varint().toInt(); d.copyOfRange(p, p + n).also { p += n } }
                5 -> le(4)
                else -> throw IllegalArgumentException("unsupported protobuf wire type")
            }
            fields.getOrPut(field) { ArrayList() } += value
        }
        return ProtoMsg(fields)
    }

    fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (v and 0x7FL.inv() != 0L) { out.write(((v and 0x7F) or 0x80).toInt()); v = v ushr 7 }
        out.write(v.toInt())
    }

    fun readVarint(d: ByteArray, start: Int): Pair<Long, Int> {
        var v = 0L; var shift = 0; var p = start
        while (true) {
            val b = d[p++].toInt() and 0xFF
            v = v or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return v to p
            shift += 7
        }
    }
}

/** Protobuf builder: `proto { int(1, 15); string(2, "x"); msg(20) { ... } }`. */
class ProtoBuilder {
    private val out = ByteArrayOutputStream()
    private fun key(n: Int, wire: Int) = Proto.writeVarint(out, ((n shl 3) or wire).toLong())
    fun int(n: Int, v: Long) { key(n, 0); Proto.writeVarint(out, v) }
    fun int(n: Int, v: Int) = int(n, v.toLong())
    fun bool(n: Int, v: Boolean) = int(n, if (v) 1L else 0L)
    fun double(n: Int, v: Double) {
        key(n, 1); val b = java.lang.Double.doubleToLongBits(v)
        for (i in 0 until 8) out.write(((b ushr (8 * i)) and 0xFF).toInt())
    }
    fun bytes(n: Int, v: ByteArray) { key(n, 2); Proto.writeVarint(out, v.size.toLong()); out.write(v) }
    fun string(n: Int, v: String) = bytes(n, v.toByteArray(Charsets.UTF_8))
    fun msg(n: Int, block: ProtoBuilder.() -> Unit) = bytes(n, proto(block))
    fun build(): ByteArray = out.toByteArray()
}

fun proto(block: ProtoBuilder.() -> Unit): ByteArray = ProtoBuilder().apply(block).build()
