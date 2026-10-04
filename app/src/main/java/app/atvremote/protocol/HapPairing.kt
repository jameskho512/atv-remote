package app.atvremote.protocol

/**
 * HomeKit-style pair-setup and pair-verify, independent of transport. Companion carries the
 * TLV messages in OPACK frames, AirPlay in HTTP POSTs; [Exchange] sends one TLV message
 * (step = the M number being sent: 1, 3, 5) and returns the device's TLV reply.
 */
object HapPairing {
    fun interface Exchange {
        suspend fun send(step: Int, tlv: ByteArray): Map<Int, ByteArray>
    }

    class SetupState(val salt: ByteArray, val serverPublic: ByteArray)

    /** M1: asks the device to show a PIN and returns its SRP salt and public key. */
    suspend fun setupStart(x: Exchange): SetupState {
        val m2 = x.send(1, Tlv8.write(Tlv8.METHOD to byteArrayOf(0), Tlv8.SEQ_NO to byteArrayOf(1)))
        return SetupState(
            m2[Tlv8.SALT] ?: throw CompanionException("no salt from device"),
            m2[Tlv8.PUBLIC_KEY] ?: throw CompanionException("no public key from device"),
        )
    }

    /** M3–M6: proves the PIN and exchanges long-term keys. */
    suspend fun setupFinish(x: Exchange, state: SetupState, pin: String, displayName: String?): Credentials {
        val srp = SrpClient(pin)
        srp.process(state.salt, state.serverPublic)
        val m4 = x.send(3, Tlv8.write(Tlv8.SEQ_NO to byteArrayOf(3), Tlv8.PUBLIC_KEY to srp.publicKey, Tlv8.PROOF to srp.proof))
        val serverProof = m4[Tlv8.PROOF] ?: throw CompanionException("no proof from device")
        if (!srp.verifyServer(serverProof)) throw CompanionException("Wrong PIN")

        val ltsk = Ed25519Key()
        val clientId = java.util.UUID.randomUUID().toString().uppercase().toByteArray()
        val signX = hkdf("Pair-Setup-Controller-Sign-Salt", "Pair-Setup-Controller-Sign-Info", srp.sessionKey)
        val encKey = hkdf("Pair-Setup-Encrypt-Salt", "Pair-Setup-Encrypt-Info", srp.sessionKey)
        val items = mutableListOf(
            Tlv8.IDENTIFIER to clientId,
            Tlv8.PUBLIC_KEY to ltsk.publicKey,
            Tlv8.SIGNATURE to ltsk.sign(signX + clientId + ltsk.publicKey),
        )
        if (displayName != null) items += 0x11 to Opack.pack(mapOf("name" to displayName))
        val inner = Tlv8.write(*items.toTypedArray())
        val m6 = x.send(5, Tlv8.write(Tlv8.SEQ_NO to byteArrayOf(5), Tlv8.ENCRYPTED_DATA to ChaCha.seal(encKey, ChaCha.nonce("PS-Msg05"), inner)))
        val enc = m6[Tlv8.ENCRYPTED_DATA] ?: throw CompanionException("no encrypted data from device")
        val atv = Tlv8.read(ChaCha.open(encKey, ChaCha.nonce("PS-Msg06"), enc))
        return Credentials(
            ltpk = atv[Tlv8.PUBLIC_KEY] ?: throw CompanionException("device sent no key"),
            ltsk = ltsk.seed,
            atvId = atv[Tlv8.IDENTIFIER] ?: throw CompanionException("device sent no identifier"),
            clientId = clientId,
        )
    }

    /** Pair-verify; returns the shared secret that session keys are derived from. */
    suspend fun verify(x: Exchange, creds: Credentials): ByteArray {
        val eph = X25519Key()
        val m2 = x.send(1, Tlv8.write(Tlv8.SEQ_NO to byteArrayOf(1), Tlv8.PUBLIC_KEY to eph.publicKey))
        val serverPub = m2[Tlv8.PUBLIC_KEY] ?: throw CompanionException("no public key from device")
        val shared = eph.agree(serverPub)
        val key = hkdf("Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info", shared)
        val inner = Tlv8.read(ChaCha.open(key, ChaCha.nonce("PV-Msg02"), m2[Tlv8.ENCRYPTED_DATA] ?: throw CompanionException("no encrypted data")))
        val id = inner[Tlv8.IDENTIFIER] ?: ByteArray(0)
        val sig = inner[Tlv8.SIGNATURE] ?: ByteArray(0)
        if (!id.contentEquals(creds.atvId) || !Ed25519Key.verify(creds.ltpk, serverPub + id + eph.publicKey, sig)) {
            throw CompanionException("This Apple TV does not match the saved pairing. Pair again.")
        }
        val mySig = Ed25519Key(creds.ltsk).sign(eph.publicKey + creds.clientId + serverPub)
        val enc = ChaCha.seal(key, ChaCha.nonce("PV-Msg03"), Tlv8.write(Tlv8.IDENTIFIER to creds.clientId, Tlv8.SIGNATURE to mySig))
        x.send(3, Tlv8.write(Tlv8.SEQ_NO to byteArrayOf(3), Tlv8.ENCRYPTED_DATA to enc))
        return shared
    }

    /** Throws a readable error if the TLV reply carries a HAP error code. */
    fun checkError(tlv: Map<Int, ByteArray>) {
        val err = tlv[Tlv8.ERROR] ?: return
        val code = err.firstOrNull()?.toInt() ?: 0
        throw CompanionException(
            when (code) {
                2 -> "Wrong PIN or the Apple TV rejected the pairing"
                3, 5 -> "Too many attempts. Wait a moment and try again"
                4 -> "The Apple TV has too many paired devices"
                6 -> "Pairing unavailable on the Apple TV"
                7 -> "The Apple TV is busy with another pairing"
                else -> "Pairing error $code"
            },
        )
    }
}
