package com.zhiwei.xplayer

import com.zhiwei.xplayer.core.webdav.DavAccount
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DavAccountTest {

    private val account = DavAccount(
        url = "https://dav.example.com/remote.php/dav/files/alice/",
        username = "alice",
        password = "secret",
    )

    @Test
    fun `owns media below account path`() {
        assertTrue(account.owns("https://dav.example.com/remote.php/dav/files/alice/Movies/a.mkv"))
        assertTrue(account.owns("https://dav.example.com/remote.php/dav/files/alice/%E7%94%B5%E5%BD%B1.mp4"))
    }

    @Test
    fun `does not own sibling path with matching text prefix`() {
        assertFalse(account.owns("https://dav.example.com/remote.php/dav/files/alice2/Movies/a.mkv"))
    }

    @Test
    fun `scheme host and port must match`() {
        assertFalse(account.owns("http://dav.example.com/remote.php/dav/files/alice/a.mkv"))
        assertFalse(account.owns("https://other.example.com/remote.php/dav/files/alice/a.mkv"))
        assertFalse(account.owns("https://dav.example.com:8443/remote.php/dav/files/alice/a.mkv"))
    }

    @Test
    fun `invalid urls are not owned`() {
        assertFalse(account.owns("not a url"))
        assertFalse(DavAccount("bad", "", "").owns("https://dav.example.com/a.mkv"))
    }
}
