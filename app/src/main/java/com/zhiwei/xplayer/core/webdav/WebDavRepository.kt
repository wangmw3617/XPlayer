package com.zhiwei.xplayer.core.webdav

import com.zhiwei.xplayer.core.data.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 落盘用的账号结构。
 *
 * 与 [DavAccount] 分开：落盘格式是「持久化契约」，一旦发布就不能随便改字段名；
 * 领域模型之后想加减字段、改默认值，不应该牵动存储。
 */
@Serializable
private data class StoredDavAccount(
    val url: String,
    val username: String = "",
    val password: String = "",
    val displayName: String = "",
)

/**
 * WebDAV 账号仓库。
 *
 * 账号存在 DataStore 的单个 JSON 字符串里（见 `AppSettings.webDavAccountsJson`），
 * 这里负责编解码与增删改。
 *
 * 注意密码是**明文**存的。WebDAV 的 Basic 认证本来就要明文口令，
 * 且这是本机单用户的播放器，用 EncryptedSharedPreferences 需要额外引
 * androidx.security 且要处理密钥轮换，收益与复杂度不成正比。
 * 真要更安全应把整个应用数据区加密，那是另一个量级的改造。
 */
@Singleton
class WebDavRepository @Inject constructor(
    private val settingsRepository: SettingsRepository,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val accounts: Flow<List<DavAccount>> = settingsRepository.settings
        .map { decode(it.webDavAccountsJson) }

    suspend fun add(account: DavAccount) {
        settingsRepository.update { current ->
            val list = decode(current.webDavAccountsJson)
                .filterNot { it.url == account.url && it.username == account.username }
                .plus(account)
            current.copy(webDavAccountsJson = encode(list))
        }
    }

    suspend fun remove(account: DavAccount) {
        settingsRepository.update { current ->
            val list = decode(current.webDavAccountsJson)
                .filterNot { it.url == account.url && it.username == account.username }
            current.copy(webDavAccountsJson = encode(list))
        }
    }

    private fun decode(raw: String): List<DavAccount> {
        if (raw.isBlank()) return emptyList()
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

    private fun encode(list: List<DavAccount>): String {
        if (list.isEmpty()) return ""
        return json.encodeToString(
            list.map {
                StoredDavAccount(
                    url = it.url,
                    username = it.username,
                    password = it.password,
                    displayName = it.displayName,
                )
            },
        )
    }
}
