package net.ankio.auto.adapter.ezb

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import net.ankio.auto.autoApp
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Never PrefManager/shared_prefs: those are exported by the app's backup code. */
class EzbConfig(val url: String = "https://books.aerybaby.com", val token: String = "",
                val accountId: String = "", val categoryId: String = "") {
    override fun toString() = "EzbConfig(redacted)"
}

object EzbConfigStore {
    private const val ALIAS = "autoaccounting.ezb.config.v1"
    private val file get() = AtomicFile(File(autoApp.noBackupFilesDir, "ezb-config.enc"))
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun load(): EzbConfig {
        if (!file.baseFile.exists()) return EzbConfig()
        try {
            val bytes = file.readFully()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            val json = JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
            return EzbConfig(json.getString("url"), json.getString("token"), json.optString("account"), json.optString("category"))
        } catch (_: Exception) { throw EzbFailure("安全配置无法读取，请重新填写连接设置") }
    }
    @Synchronized fun save(config: EzbConfig) {
        try {
            val json = JSONObject().put("url", config.url).put("token", config.token)
                .put("account", config.accountId).put("category", config.categoryId).toString()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val bytes = cipher.iv + cipher.doFinal(json.toByteArray(Charsets.UTF_8))
            val atomic = file
            val stream = atomic.startWrite()
            try { stream.write(bytes); atomic.finishWrite(stream) }
            catch (e: Exception) { atomic.failWrite(stream); throw e }
        } catch (_: Exception) { throw EzbFailure("安全配置保存失败，请重试") }
    }
}
