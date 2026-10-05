package app.nya.password.core

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val KEYSTORE = "AndroidKeyStore"
private const val GCM = "AES/GCM/NoPadding"

private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

/** File layout of a wrapped secret: 1 byte IV length, IV, ciphertext + tag. */
private fun pack(iv: ByteArray, ct: ByteArray): ByteArray = byteArrayOf(iv.size.toByte()) + iv + ct

private fun unpack(b: ByteArray): Pair<ByteArray, ByteArray> {
    val n = b[0].toInt()
    return b.copyOfRange(1, 1 + n) to b.copyOfRange(1 + n, b.size)
}

/**
 * The core's device key: 32 random bytes that seal the Secret Key and the
 * session in the replica. Stored in no-backup storage, wrapped by an Android
 * Keystore AES key that needs no user authentication (it only binds the file
 * to this device).
 */
object DeviceKey {
    private const val ALIAS = "npw_device_key_wrap"
    private const val FILE = "device_key.bin"

    /** The key, and whether it was just created (an older replica can't be read with it). */
    fun load(context: Context, random: () -> ByteArray): Pair<ByteArray, Boolean> {
        val f = File(context.noBackupFilesDir, FILE)
        val wrap = wrapKey()
        if (f.exists()) {
            runCatching {
                val (iv, ct) = unpack(f.readBytes())
                val c = Cipher.getInstance(GCM)
                c.init(Cipher.DECRYPT_MODE, wrap, GCMParameterSpec(128, iv))
                return c.doFinal(ct) to false
            }
        }
        val key = random()
        val c = Cipher.getInstance(GCM)
        c.init(Cipher.ENCRYPT_MODE, wrap)
        val tmp = File(context.noBackupFilesDir, "$FILE.tmp")
        tmp.writeBytes(pack(c.iv, c.doFinal(key)))
        tmp.renameTo(f)
        return key to true
    }

    private fun wrapKey(): SecretKey {
        val ks = keyStore()
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        g.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return g.generateKey()
    }
}

/**
 * Biometric quick unlock (design doc §4.5): the core's quick-unlock key,
 * encrypted with a Keystore key that needs a biometric for every use and is
 * invalidated when fingerprints or faces are added. Offered only after a
 * master-password unlock in the same process run and within 14 days of the
 * last one (see [Vault.quickUnlockOffered]).
 */
object QuickUnlock {
    private const val ALIAS = "npw_quick_unlock"
    private const val FILE = "quick_unlock.bin"

    class Invalidated : Exception("biometrics changed")

    fun stored(context: Context): Boolean = File(context.noBackupFilesDir, FILE).exists()

    /** A cipher to encrypt a new quick-unlock key; the biometric prompt authorizes it. */
    fun encryptCipher(): Cipher {
        val ks = keyStore()
        if (ks.containsAlias(ALIAS)) ks.deleteEntry(ALIAS)
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        val b = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            b.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            @Suppress("DEPRECATION")
            b.setUserAuthenticationValidityDurationSeconds(-1)
        }
        g.init(b.build())
        val key = g.generateKey()
        return Cipher.getInstance(GCM).apply { init(Cipher.ENCRYPT_MODE, key) }
    }

    fun save(context: Context, cipher: Cipher, quickKey: ByteArray) {
        val ct = cipher.doFinal(quickKey)
        File(context.noBackupFilesDir, FILE).writeBytes(pack(cipher.iv, ct))
    }

    /** A cipher to decrypt the stored key; throws [Invalidated] when biometrics changed (then [clear] was called). */
    fun decryptCipher(context: Context): Cipher {
        val f = File(context.noBackupFilesDir, FILE)
        val key = keyStore().getKey(ALIAS, null) as? SecretKey
        if (key == null || !f.exists()) {
            clear(context)
            throw Invalidated()
        }
        val (iv, _) = unpack(f.readBytes())
        try {
            return Cipher.getInstance(GCM).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv)) }
        } catch (e: KeyPermanentlyInvalidatedException) {
            clear(context)
            throw Invalidated()
        }
    }

    fun open(context: Context, cipher: Cipher): ByteArray {
        val (_, ct) = unpack(File(context.noBackupFilesDir, FILE).readBytes())
        return cipher.doFinal(ct)
    }

    fun clear(context: Context) {
        File(context.noBackupFilesDir, FILE).delete()
        runCatching { keyStore().deleteEntry(ALIAS) }
    }
}
