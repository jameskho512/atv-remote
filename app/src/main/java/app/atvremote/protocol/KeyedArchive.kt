package app.atvremote.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/** A keyed-archiver object reference (plist UID). */
data class Uid(val value: Int)

/**
 * Just enough binary plist (bplist00) to read the keyboard state the Apple TV sends and to build
 * the NSKeyedArchiver payloads for text input (layouts from pyatv's rti_text_operations).
 */
object KeyedArchive {

    /** Follows `$top[path[0]][path[1]]...`, resolving UIDs through `$objects`. */
    fun property(archive: ByteArray, vararg path: String): Any? {
        val root = BinaryPlist.read(archive) as? Map<*, *> ?: return null
        val objects = root["\$objects"] as? List<*> ?: return null
        var el: Any? = root["\$top"]
        for (key in path) {
            el = (el as? Map<*, *>)?.get(key) ?: return null
            if (el is Uid) el = objects.getOrNull(el.value)
        }
        return el
    }

    private fun cls(name: String) = mapOf("\$classname" to name, "\$classes" to listOf(name, "NSObject"))

    private fun archive(objects: List<Any>) = BinaryPlist.write(
        mapOf(
            "\$version" to 100000,
            "\$archiver" to "RTIKeyedArchiver",
            "\$top" to mapOf("textOperations" to Uid(1)),
            "\$objects" to objects,
        ),
    )

    fun clearText(sessionUuid: ByteArray) = archive(
        listOf(
            "\$null",
            mapOf("\$class" to Uid(7), "targetSessionUUID" to Uid(5), "keyboardOutput" to Uid(2), "textToAssert" to Uid(4)),
            mapOf("\$class" to Uid(3)),
            cls("TIKeyboardOutput"),
            "",
            mapOf("NS.uuidbytes" to sessionUuid, "\$class" to Uid(6)),
            cls("NSUUID"),
            cls("RTITextOperations"),
        ),
    )

    fun insertText(sessionUuid: ByteArray, text: String) = archive(
        listOf(
            "\$null",
            mapOf("keyboardOutput" to Uid(2), "\$class" to Uid(7), "targetSessionUUID" to Uid(5)),
            mapOf("insertionText" to Uid(3), "\$class" to Uid(4)),
            text,
            cls("TIKeyboardOutput"),
            mapOf("NS.uuidbytes" to sessionUuid, "\$class" to Uid(6)),
            cls("NSUUID"),
            cls("RTITextOperations"),
        ),
    )
}

object BinaryPlist {

    fun write(root: Any): ByteArray {
        // Flatten into an object table (no de-duplication; that is optional in bplist).
        val objs = ArrayList<Any>()
        val refs = ArrayList<IntArray>()
        fun add(v: Any): Int {
            val i = objs.size
            objs += v; refs += IntArray(0)
            refs[i] = when (v) {
                is List<*> -> v.map { add(it!!) }.toIntArray()
                is Map<*, *> -> (v.keys.map { add(it!!) } + v.values.map { add(it!!) }).toIntArray()
                else -> IntArray(0)
            }
            return i
        }
        add(root)
        val refSize = if (objs.size < 256) 1 else 2

        val out = ByteArrayOutputStream()
        out.write("bplist00".toByteArray())
        val offsets = IntArray(objs.size)
        fun be(v: Long, n: Int) { for (i in n - 1 downTo 0) out.write((v ushr (8 * i)).toInt() and 0xFF) }
        fun marker(type: Int, count: Int) {
            if (count < 15) out.write(type or count) else { out.write(type or 0xF); writeInt(out, count.toLong()) }
        }
        for ((i, v) in objs.withIndex()) {
            offsets[i] = out.size()
            when (v) {
                is Boolean -> out.write(if (v) 0x09 else 0x08)
                is Int, is Long -> writeInt(out, (v as Number).toLong())
                is Uid -> { val n = if (v.value < 256) 1 else 2; out.write(0x80 or (n - 1)); be(v.value.toLong(), n) }
                is ByteArray -> { marker(0x40, v.size); out.write(v) }
                is String -> if (v.all { it.code < 128 }) {
                    marker(0x50, v.length); out.write(v.toByteArray(Charsets.US_ASCII))
                } else {
                    marker(0x60, v.length); out.write(v.toByteArray(Charsets.UTF_16BE))
                }
                is List<*> -> { marker(0xA0, v.size); refs[i].forEach { be(it.toLong(), refSize) } }
                is Map<*, *> -> { marker(0xD0, v.size); refs[i].forEach { be(it.toLong(), refSize) } }
                else -> throw IllegalArgumentException("bplist cannot encode ${v::class}")
            }
        }
        val tableOffset = out.size()
        val offSize = when { tableOffset < 0x100 -> 1; tableOffset < 0x10000 -> 2; else -> 4 }
        offsets.forEach { be(it.toLong(), offSize) }
        out.write(ByteArray(6)); out.write(offSize); out.write(refSize)
        be(objs.size.toLong(), 8); be(0, 8); be(tableOffset.toLong(), 8)
        return out.toByteArray()
    }

    private fun writeInt(out: ByteArrayOutputStream, v: Long) {
        val n = when { v in 0..0xFF -> 0; v in 0..0xFFFF -> 1; v in 0..0xFFFFFFFFL -> 2; else -> 3 }
        out.write(0x10 or n)
        val bytes = 1 shl n
        for (i in bytes - 1 downTo 0) out.write((v ushr (8 * i)).toInt() and 0xFF)
    }

    fun read(data: ByteArray): Any? {
        require(data.size > 40 && String(data, 0, 8, Charsets.US_ASCII).startsWith("bplist")) { "not a binary plist" }
        val t = data.size - 32
        val offSize = data[t + 6].toInt() and 0xFF
        val refSize = data[t + 7].toInt() and 0xFF
        val count = be(data, t + 8, 8).toInt()
        val top = be(data, t + 16, 8).toInt()
        val table = be(data, t + 24, 8).toInt()
        val offsets = IntArray(count) { be(data, table + it * offSize, offSize).toInt() }

        fun obj(i: Int, depth: Int = 0): Any? {
            require(depth < 64) { "plist nested too deeply" }
            var p = offsets[i]
            val m = data[p].toInt() and 0xFF
            val type = m and 0xF0
            val info = m and 0x0F
            p++
            fun count(): Int {
                if (info != 0xF) return info
                val im = data[p].toInt() and 0x0F
                val n = 1 shl im
                return be(data, p + 1, n).toInt().also { p += 1 + n }
            }
            return when (type) {
                0x00 -> when (m) { 0x08 -> false; 0x09 -> true; else -> null }
                0x10 -> be(data, p, 1 shl info)
                0x20 -> if (info == 2) ByteBuffer.wrap(data, p, 4).float.toDouble() else ByteBuffer.wrap(data, p, 8).double
                0x30 -> ByteBuffer.wrap(data, p, 8).double
                0x40 -> { val n = count(); data.copyOfRange(p, p + n) }
                0x50 -> { val n = count(); String(data, p, n, Charsets.US_ASCII) }
                0x60 -> { val n = count(); String(data, p, n * 2, Charsets.UTF_16BE) }
                0x80 -> Uid(be(data, p, info + 1).toInt())
                0xA0 -> { val n = count(); List(n) { obj(be(data, p + it * refSize, refSize).toInt(), depth + 1) } }
                0xD0 -> {
                    val n = count()
                    val map = LinkedHashMap<Any?, Any?>()
                    for (k in 0 until n) {
                        map[obj(be(data, p + k * refSize, refSize).toInt(), depth + 1)] =
                            obj(be(data, p + (n + k) * refSize, refSize).toInt(), depth + 1)
                    }
                    map
                }
                else -> null
            }
        }
        return obj(top)
    }

    private fun be(d: ByteArray, off: Int, n: Int): Long {
        var v = 0L
        for (i in 0 until n) v = (v shl 8) or (d[off + i].toLong() and 0xFF)
        return v
    }
}
