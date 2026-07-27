package de.kevin.vmaxdashboard

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureTelemetryStore(private val context: Context) {
    companion object {
        private const val KEY_ALIAS = "vmax_telemetry_aes_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }

    private val folder = File(context.filesDir, "secure_telemetry").apply { mkdirs() }

    fun saveEncrypted(report: String): File {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(report.toByteArray(Charsets.UTF_8))

        val payload = buildString {
            append(Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            append("\n")
            append(Base64.encodeToString(ciphertext, Base64.NO_WRAP))
        }

        return File(folder, "report_${System.currentTimeMillis()}.vmaxenc").apply {
            writeText(payload)
        }
    }

    fun count(): Int = folder.listFiles()?.count { it.extension == "vmaxenc" } ?: 0

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}
