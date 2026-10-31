package com.zz213119.virtualclicker.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AI 助手的用户配置。服务商、地址、模型都由用户自己填；密钥用 Android Keystore 里的 AES-GCM 加密后才存盘
 * （自己实现，不引入 security-crypto，省得多带几 MB 的依赖）。
 */
object AiConfig {
    const val FORMAT_OPENAI = "openai"
    const val FORMAT_ANTHROPIC = "anthropic"

    private const val KEY_ALIAS = "vc_ai_api_key"

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("vc_ai", Context.MODE_PRIVATE)

    fun format(c: Context): String = sp(c).getString("format", FORMAT_OPENAI) ?: FORMAT_OPENAI
    fun baseUrl(c: Context): String = sp(c).getString("base_url", "") ?: ""
    fun model(c: Context): String = sp(c).getString("model", "") ?: ""
    fun systemPrompt(c: Context): String = sp(c).getString("system_prompt", "") ?: ""
    fun sendScreenshot(c: Context): Boolean = sp(c).getBoolean("send_screenshot", false)

    fun save(
        c: Context, format: String, baseUrl: String, model: String,
        systemPrompt: String, sendScreenshot: Boolean
    ) {
        sp(c).edit()
            .putString("format", format)
            .putString("base_url", baseUrl.trim())
            .putString("model", model.trim())
            .putString("system_prompt", systemPrompt)
            .putBoolean("send_screenshot", sendScreenshot)
            .apply()
    }

    fun hasApiKey(c: Context) = !sp(c).getString("api_key_enc", "").isNullOrEmpty()

    fun isReady(c: Context) =
        baseUrl(c).isNotBlank() && model(c).isNotBlank() && hasApiKey(c)

    fun setApiKey(c: Context, key: String) {
        val enc = if (key.isBlank()) "" else encrypt(key.trim())
        sp(c).edit().putString("api_key_enc", enc).apply()
    }

    fun apiKey(c: Context): String {
        val s = sp(c).getString("api_key_enc", "") ?: ""
        if (s.isEmpty()) return ""
        return runCatching { decrypt(s) }.getOrDefault("")
    }

    // ---- Keystore AES-GCM ----
    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
    }

    private fun decrypt(s: String): String {
        val (ivB64, ctB64) = s.split(":", limit = 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(ivB64, Base64.NO_WRAP)))
        return String(cipher.doFinal(Base64.decode(ctB64, Base64.NO_WRAP)), Charsets.UTF_8)
    }
}
