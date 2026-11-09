package dev.whistle.app.protocol

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.ConcurrentHashMap

/** [DeviceKeys] in memory (the Android Keystore is not available on the JVM). */
class SoftwareDeviceKeys : DeviceKeys {
    val pairs = ConcurrentHashMap<String, KeyPair>()

    override fun generate(alias: String): ByteArray {
        val gen = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        return gen.generateKeyPair().also { pairs[alias] = it }.public.encoded
    }

    override fun sign(alias: String, data: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run {
            initSign((pairs[alias] ?: error("no key $alias")).private)
            update(data)
            sign()
        }

    override fun has(alias: String) = pairs.containsKey(alias)

    override fun delete(alias: String) {
        pairs.remove(alias)
    }
}
