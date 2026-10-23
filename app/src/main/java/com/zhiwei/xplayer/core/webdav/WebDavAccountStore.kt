package com.zhiwei.xplayer.core.webdav

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.zhiwei.xplayer.core.data.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.webDavAccountDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "xplayer_webdav_accounts")

@Serializable
private data class StoredDavAccount(
    val url: String,
    val username: String = "",
    val password: String = "",
    val displayName: String = "",
)

/**
 * WebDAV 账号与凭据存储。
 *
 * 密码使用 Android Keystore 中的 AES-GCM 密钥加密后再写入独立 DataStore。
 * 普通设置文件不再包含密码，凭据文件也会被备份规则显式排除。
 */
@Singleton
class WebDavAccountStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val cipher = DavCredentialCipher()
    private val migrationMutex = Mutex()

    val accounts: Flow<List<DavAccount>> = flow {
        migrateLegacySafely()
        emitAll(
            context.webDavAccountDataStore.data
                .map { prefs -> decode(prefs[ACCOUNTS_KEY]) }
                .distinctUntilChanged(),
        )
    }

    /**
     * 在应用启动时主动调用一次，不依赖 WebDAV 页面是否被打开。
     * 迁移失败时保留旧字段并记录错误，下一次启动或账号操作会再次尝试。
     */
    suspend fun ensureMigrated() {
        migrateLegacySafely()
    }

    suspend fun add(account: DavAccount) {
        migrateLegacyAccounts()
        context.webDavAccountDataStore.edit { prefs ->
            val next = decode(prefs[ACCOUNTS_KEY])
                .filterNot { it.url == account.url && it.username == account.username }
                .plus(account)
            prefs[ACCOUNTS_KEY] = encode(next)
        }
    }

    suspend fun remove(account: DavAccount) {
        migrateLegacyAccounts()
        context.webDavAccountDataStore.edit { prefs ->
            val next = decode(prefs[ACCOUNTS_KEY])
                .filterNot { it.url == account.url && it.username == account.username }
            prefs[ACCOUNTS_KEY] = encode(next)
        }
    }

    suspend fun clear() {
        context.webDavAccountDataStore.edit { it.clear() }
        cipher.deleteKey()
    }

    private suspend fun migrateLegacySafely() {
        try {
            migrateLegacyAccounts()
        } catch (error: Throwable) {
            Log.e(TAG, "WebDAV credential migration failed", error)
        }
    }

    private suspend fun migrateLegacyAccounts() = migrationMutex.withLock {
        val legacy = settingsRepository.legacyWebDavAccountsJson()
        if (legacy.isNullOrBlank()) return@withLock

        context.webDavAccountDataStore.edit { prefs ->
            if (prefs[ACCOUNTS_KEY].isNullOrBlank()) {
                val migrated = decodeLegacy(legacy)
                if (migrated.isNotEmpty()) prefs[ACCOUNTS_KEY] = encode(migrated)
            }
        }
        settingsRepository.clearLegacyWebDavAccounts()
    }

    private fun decode(raw: String?): List<DavAccount> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString<List<StoredDavAccount>>(raw).map {
                DavAccount(
                    url = it.url,
                    username = it.username,
                    password = cipher.decrypt(it.password),
                    displayName = it.displayName,
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun decodeLegacy(raw: String): List<DavAccount> {
        return runCatching {
            json.decodeFromString<List<StoredDavAccount>>(raw).map {
                DavAccount(
                    url = it.url,
                    username = it.username,
                    password = it.password,
                    displayName = it.displayName,
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun encode(accounts: List<DavAccount>): String {
        if (accounts.isEmpty()) return ""
        return json.encodeToString(
            accounts.map {
                StoredDavAccount(
                    url = it.url,
                    username = it.username,
                    password = cipher.encrypt(it.password),
                    displayName = it.displayName,
                )
            },
        )
    }

    private companion object {
        const val TAG = "XPlayer/webdav"
        val ACCOUNTS_KEY = stringPreferencesKey("accounts")
    }
}

private class DavCredentialCipher {
    private val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    @Synchronized
    fun encrypt(value: String): String {
        if (value.isEmpty()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return "v1:" +
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP) +
            ":" +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    fun decrypt(value: String): String {
        if (value.isEmpty()) return ""
        if (!value.startsWith("v1:")) return value
        return runCatching {
            val parts = value.split(':', limit = 3)
            require(parts.size == 3)
            val iv = Base64.decode(parts[1], Base64.NO_WRAP)
            val encrypted = Base64.decode(parts[2], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(GCM_TAG_BITS, iv),
            )
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        }.getOrDefault("")
    }

    @Synchronized
    fun deleteKey() {
        runCatching {
            if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
        }
    }

    private fun getOrCreateKey(): SecretKey {
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "xplayer_webdav_credentials_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}
