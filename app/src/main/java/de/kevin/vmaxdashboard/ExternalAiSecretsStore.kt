package de.kevin.vmaxdashboard

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class ExternalAiSecretStatus(
    val geminiConfigured: Boolean,
    val glmConfigured: Boolean,
    val openAiConfigured: Boolean
)

/**
 * Stores provider API keys encrypted with Android Keystore.
 *
 * No API key is compiled into the APK or written to the repository. Keys only
 * leave this store in memory immediately before an HTTPS request is created.
 */
class ExternalAiSecretsStore(context: Context) {
    companion object {
        private const val PREFS = "vmax_external_ai"
        private const val KEY_ALIAS = "vmax_external_ai_provider_keys_v1"
        private const val GEMINI = "gemini"
        private const val GLM = "glm"
        private const val OPENAI = "openai"
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun status(): ExternalAiSecretStatus = ExternalAiSecretStatus(
        geminiConfigured = readEncrypted(GEMINI)?.isNotBlank() == true,
        glmConfigured = readEncrypted(GLM)?.isNotBlank() == true,
        openAiConfigured = readEncrypted(OPENAI)?.isNotBlank() == true
    )

    fun saveGeminiKey(value: String) = saveEncrypted(GEMINI, normalize(value, "Gemini"))

    fun saveGlmKey(value: String) = saveEncrypted(GLM, normalize(value, "GLM"))

    fun saveOpenAiKey(value: String) = saveEncrypted(OPENAI, normalize(value, "OpenAI"))

    fun clearGeminiKey() = clear(GEMINI)

    fun clearGlmKey() = clear(GLM)

    fun clearOpenAiKey() = clear(OPENAI)

    internal fun geminiKeyOrNull(): String? = readEncrypted(GEMINI)?.takeIf { it.isNotBlank() }

    internal fun glmKeyOrNull(): String? = readEncrypted(GLM)?.takeIf { it.isNotBlank() }

    internal fun openAiKeyOrNull(): String? = readEncrypted(OPENAI)?.takeIf { it.isNotBlank() }

    private fun normalize(value: String, provider: String): String {
        val normalized = value.trim()
        require(normalized.length >= 12) { "$provider API-Key ist zu kurz" }
        require(!normalized.any { it.isWhitespace() }) { "$provider API-Key enthält Leerzeichen" }
        return normalized
    }

    private fun saveEncrypted(prefix: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString("${prefix}_iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("${prefix}_data", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
    }

    private fun readEncrypted(prefix: String): String? {
        val ivText = prefs.getString("${prefix}_iv", null) ?: return null
        val dataText = prefs.getString("${prefix}_data", null) ?: return null
        return runCatching {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val key = keyStore.getKey(KEY_ALIAS, null) as? SecretKey ?: return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(128, Base64.decode(ivText, Base64.NO_WRAP))
            )
            String(cipher.doFinal(Base64.decode(dataText, Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun clear(prefix: String) {
        prefs.edit()
            .remove("${prefix}_iv")
            .remove("${prefix}_data")
            .apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
