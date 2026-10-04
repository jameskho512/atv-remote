package app.atvremote.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class OpackTest {
    // Packed by pyatv (with its back-references: the second "_hidC" is 0xA1).
    private val fromPyatv = ("e4425f69455f68696443425f740a425f63e7455f6842745309a10e425f77360000000000408f404362696732701101" +
        "004162720102416cd409417804014173612861616161616161616161616161616161616161616161616161616161616161616161616161616161" +
        "425f78313930").hexToBytes()

    @Test fun decodesPyatvOutput() {
        val m = Opack.unpack(fromPyatv).asMap()
        assertEquals("_hidC", m["_i"])
        assertEquals(2L, m["_t"])
        assertEquals(12345L, m["_x"])
        val c = m["_c"].asMap()
        assertEquals(1L, c["_hBtS"])
        assertEquals(6L, c["_hidC"])
        assertEquals(1000.0, c["_w"])
        assertEquals(70000L, c["big"])
        assertArrayEquals(byteArrayOf(1, 2), c["b"] as ByteArray)
        assertEquals(listOf(1L, "x", null, true), c["l"])
        assertEquals("a".repeat(40), c["s"])
    }

    @Test fun roundTrips() {
        val big = (0 until 20).associate { "k$it" to it }
        val v = mapOf("a" to "é".repeat(300), "n" to 0xFFFFFFFFFFL, "list" to List(16) { it }, "big" to big, "d" to ByteArray(70000) { it.toByte() })
        val back = Opack.unpack(Opack.pack(v)).asMap()
        assertEquals(v["a"], back["a"])
        assertEquals(0xFFFFFFFFFFL, back["n"])
        assertEquals(List(16) { it.toLong() }, back["list"])
        assertEquals(big.mapValues { it.value.toLong() }, back["big"])
        assertArrayEquals(v["d"] as ByteArray, back["d"] as ByteArray)
    }

    @Test fun backReference() {
        assertEquals(mapOf("a" to "xyz", "b" to "xyz"), Opack.unpack("e241614378797a4162a1".hexToBytes()))
    }

    @Test fun plistRoundTrip() {
        val uuid = "0123456789abcdef".toByteArray()
        val archive = KeyedArchive.insertText(uuid, "héllo")
        assertArrayEquals(uuid, KeyedArchive.property(archive, "textOperations", "targetSessionUUID", "NS.uuidbytes") as ByteArray)
        assertEquals("héllo", KeyedArchive.property(archive, "textOperations", "keyboardOutput", "insertionText"))
    }

    @Test fun hidEventMatchesPyatv() {
        val msg = Proto.decode(AirPlayClient.hidEvent(0x0C, 0xE2, true))
        assertEquals("438922cf080200000000000000000000010000000000000002000000200000000300000001000000000000000c00e200010000000000000001000000", msg.msg(13)!!.bytes(1)!!.toHex())
    }
}
