package app.atvremote.protocol

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

internal val random = SecureRandom()

fun randomBytes(n: Int) = ByteArray(n).also { random.nextBytes(it) }

fun sha512(vararg parts: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-512").run { parts.forEach { update(it) }; digest() }

/** HKDF-SHA512 with a 32-byte output, as HAP uses for every derived key. */
fun hkdf(salt: String, info: String, ikm: ByteArray): ByteArray {
    val gen = HKDFBytesGenerator(SHA512Digest())
    gen.init(HKDFParameters(ikm, salt.toByteArray(), info.toByteArray()))
    return ByteArray(32).also { gen.generateBytes(it, 0, 32) }
}

/** ChaCha20-Poly1305 with a 12-byte nonce; shorter nonces are left-padded with zeros (HAP "PS-Msg05" style). */
object ChaCha {
    fun nonce(n: ByteArray) = if (n.size >= 12) n else ByteArray(12 - n.size) + n
    fun nonce(text: String) = nonce(text.toByteArray())

    fun seal(key: ByteArray, nonce: ByteArray, plain: ByteArray, aad: ByteArray? = null): ByteArray =
        run(true, key, nonce, plain, aad)

    fun open(key: ByteArray, nonce: ByteArray, sealed: ByteArray, aad: ByteArray? = null): ByteArray =
        run(false, key, nonce, sealed, aad)

    private fun run(encrypt: Boolean, key: ByteArray, nonce: ByteArray, input: ByteArray, aad: ByteArray?): ByteArray {
        val c = ChaCha20Poly1305()
        c.init(encrypt, AEADParameters(KeyParameter(key), 128, nonce(nonce), aad))
        val out = ByteArray(c.getOutputSize(input.size))
        val n = c.processBytes(input, 0, input.size, out, 0)
        c.doFinal(out, n) // throws InvalidCipherTextException on a bad tag
        return out
    }
}

class Ed25519Key(val seed: ByteArray = randomBytes(32)) {
    private val priv = Ed25519PrivateKeyParameters(seed, 0)
    val publicKey: ByteArray = priv.generatePublicKey().encoded

    fun sign(msg: ByteArray): ByteArray = Ed25519Signer().run {
        init(true, priv); update(msg, 0, msg.size); generateSignature()
    }

    companion object {
        fun verify(publicKey: ByteArray, msg: ByteArray, sig: ByteArray): Boolean = Ed25519Signer().run {
            init(false, Ed25519PublicKeyParameters(publicKey, 0)); update(msg, 0, msg.size); verifySignature(sig)
        }
    }
}

class X25519Key {
    private val priv = X25519PrivateKeyParameters(random)
    val publicKey: ByteArray = priv.generatePublicKey().encoded

    fun agree(peer: ByteArray): ByteArray = ByteArray(32).also {
        X25519Agreement().run { init(priv); calculateAgreement(X25519PublicKeyParameters(peer, 0), it, 0) }
    }
}

/**
 * SRP-6a client as used by HomeKit pair-setup: 3072-bit group (RFC 5054), SHA-512,
 * username "Pair-Setup", the PIN as password. Values are padded to the group size as
 * Apple's implementation expects.
 */
class SrpClient(private val password: String, private val username: String = "Pair-Setup") {
    private val a = BigInteger(1, randomBytes(32))
    val publicKey: ByteArray = pad(G.modPow(a, N))
    lateinit var sessionKey: ByteArray; private set
    lateinit var proof: ByteArray; private set
    private lateinit var expectedServerProof: ByteArray

    fun process(salt: ByteArray, serverPublic: ByteArray) {
        val bInt = BigInteger(1, serverPublic)
        require(bInt.mod(N) != BigInteger.ZERO) { "invalid server public key" }
        val bPad = pad(bInt)
        val k = BigInteger(1, sha512(pad(N), pad(G)))
        val u = BigInteger(1, sha512(publicKey, bPad))
        val x = BigInteger(1, sha512(salt, sha512("$username:$password".toByteArray())))
        val base = bInt.subtract(k.multiply(G.modPow(x, N))).mod(N)
        val s = base.modPow(a.add(u.multiply(x)), N)
        sessionKey = sha512(pad(s))
        val hn = sha512(pad(N)); val hg = sha512(byteArrayOf(G.toInt().toByte()))
        val hnXorHg = ByteArray(hn.size) { (hn[it].toInt() xor hg[it].toInt()).toByte() }
        proof = sha512(hnXorHg, sha512(username.toByteArray()), salt, publicKey, bPad, sessionKey)
        expectedServerProof = sha512(publicKey, proof, sessionKey)
    }

    fun verifyServer(serverProof: ByteArray) = MessageDigest.isEqual(serverProof, expectedServerProof)

    companion object {
        val N = BigInteger(
            ("FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B139B22514A08798E3404DD" +
                "EF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED" +
                "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F" +
                "83655D23DCA3AD961C62F356208552BB9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B" +
                "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF6955817183995497CEA956AE515D2261898FA0510" +
                "15728E5A8AAAC42DAD33170D04507A33A85521ABDF1CBA64ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7" +
                "ABF5AE8CDB0933D71E8C94E04A25619DCEE3D2261AD2EE6BF12FFA06D98A0864D87602733EC86A64521F2B18177B200C" +
                "BBE117577A615D6C770988C0BAD946E208E24FA074E5AB3143DB5BFCE0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF"),
            16,
        )
        val G: BigInteger = BigInteger.valueOf(5)
        private const val LEN = 384

        fun pad(v: BigInteger): ByteArray {
            val b = v.toByteArray().let { if (it.size > LEN) it.copyOfRange(it.size - LEN, it.size) else it }
            return ByteArray(LEN - b.size) + b
        }
    }
}

/** TLV8 as used by HAP pairing; values over 255 bytes are split into consecutive chunks. */
object Tlv8 {
    const val METHOD = 0x00
    const val IDENTIFIER = 0x01
    const val SALT = 0x02
    const val PUBLIC_KEY = 0x03
    const val PROOF = 0x04
    const val ENCRYPTED_DATA = 0x05
    const val SEQ_NO = 0x06
    const val ERROR = 0x07
    const val BACKOFF = 0x08
    const val SIGNATURE = 0x0A

    fun write(vararg items: Pair<Int, ByteArray>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for ((tag, value) in items) {
            var pos = 0
            do {
                val size = minOf(255, value.size - pos)
                out.write(tag); out.write(size); out.write(value, pos, size)
                pos += size
            } while (pos < value.size)
        }
        return out.toByteArray()
    }

    fun read(data: ByteArray): Map<Int, ByteArray> {
        val result = LinkedHashMap<Int, ByteArray>()
        var p = 0
        while (p + 2 <= data.size) {
            val tag = data[p].toInt() and 0xFF
            val len = data[p + 1].toInt() and 0xFF
            val v = data.copyOfRange(p + 2, p + 2 + len)
            result[tag] = result[tag]?.plus(v) ?: v
            p += 2 + len
        }
        return result
    }
}
