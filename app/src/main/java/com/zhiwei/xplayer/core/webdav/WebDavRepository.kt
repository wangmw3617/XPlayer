package com.zhiwei.xplayer.core.webdav

import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WebDAV 账号仓库。
 *
 * 领域层只看到 [DavAccount]，密码的加密和旧数据迁移由 [WebDavAccountStore]
 * 负责。
 */
@Singleton
class WebDavRepository @Inject constructor(
    private val store: WebDavAccountStore,
) {

    val accounts: Flow<List<DavAccount>> = store.accounts

    suspend fun add(account: DavAccount) {
        store.add(account)
    }

    suspend fun remove(account: DavAccount) {
        store.remove(account)
    }

    suspend fun clearAll() {
        store.clear()
    }
}
