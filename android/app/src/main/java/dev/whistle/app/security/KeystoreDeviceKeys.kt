package dev.whistle.app.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.whistle.app.protocol.DeviceKeys
import java.security.InvalidAlgorithmParameterException
import java.security.KeyPairGenerator
import java.security.KeyStoreException
import java.security.KeyStore
import java.security.PrivateKey
import java.security.ProviderException
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * Device keys in the Android Keystore: EC P-256, sign-only, SHA-256, non-exportable; in StrongBox
 * when the phone has one. Not usable on the JVM (tests use a software [DeviceKeys]).
 */
class KeystoreDeviceKeys : DeviceKeys {
    private fun store() = KeyStore.getInstance(PROVIDER).apply { load(null) }

    override fun generate(alias: String): ByteArray {
        fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .apply { if (strongBox && Build.VERSION.SDK_INT >= 28) setIsStrongBoxBacked(true) }
            .build()
        fun gen(strongBox: Boolean) = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER).run {
            initialize(spec(strongBox))
            generateKeyPair()
        }
        val pair = if (Build.VERSION.SDK_INT < 28) {
            gen(strongBox = false)
        } else {
            try {
                gen(strongBox = true)
            } catch (e: Exception) {
                // No (usable) StrongBox: StrongBoxUnavailableException, or on some devices a
                // ProviderException / KeyStoreException from generateKeyPair. Retry in the TEE.
                if (!strongBoxFailure(e)) throw e
                runCatching { delete(alias) } // a half-created entry must not shadow the retry
                gen(strongBox = false)
            }
        }
        return pair.public.encoded // X.509 SubjectPublicKeyInfo
    }

    override fun sign(alias: String, data: ByteArray): ByteArray {
        val key = store().getKey(alias, null) as? PrivateKey ?: error("no device key $alias")
        return Signature.getInstance("SHA256withECDSA").run {
            initSign(key)
            update(data)
            sign()
        }
    }

    override fun has(alias: String): Boolean = store().containsAlias(alias)

    override fun delete(alias: String) {
        store().run { if (containsAlias(alias)) deleteEntry(alias) }
    }

    internal companion object {
        const val PROVIDER = "AndroidKeyStore"

        /** Whether [e] (from generating a StrongBox key) means "try again without StrongBox". */
        fun strongBoxFailure(e: Throwable): Boolean {
            var t: Throwable? = e
            while (t != null) {
                if (t is ProviderException || t is KeyStoreException || t is InvalidAlgorithmParameterException ||
                    t.javaClass.name == "android.security.keystore.StrongBoxUnavailableException"
                ) return true
                t = t.cause
            }
            return false
        }
    }
}
