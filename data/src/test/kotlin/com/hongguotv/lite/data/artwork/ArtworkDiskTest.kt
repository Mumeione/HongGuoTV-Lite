package com.hongguotv.lite.data.artwork

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 封面落盘：文件名稳定、能读写、写坏的读回是 null（不炸）。
 * 用 `java.io` 与临时目录，不碰 Android——收藏/历史封面丢了只是重下一次，
 * 但"写进去读不出来"会让用户每次打开都没图，值得钉住。
 */
class ArtworkDiskTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val url = "https://p3.example.com/cover/abc.jpg?x-expires=1&x-signature=deadbeef"

    @Test
    fun `文件名稳定且不含路径分隔符`() {
        val name = ArtworkDisk.nameFor(url)
        assertEquals(name, ArtworkDisk.nameFor(url))
        assertFalse(name.contains('/'))
        assertFalse(name.contains('\\'))
        assertFalse(name.contains('?'))
        assertTrue(name.matches(Regex("[0-9a-f]{40}")))
    }

    @Test
    fun `不同地址不同文件名`() {
        assertNotEquals(ArtworkDisk.nameFor("$url&a=1"), ArtworkDisk.nameFor("$url&a=2"))
    }

    @Test
    fun `写进去能读回来`() {
        val disk = ArtworkDisk(temp.newFolder("artwork"))
        val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        assertFalse(disk.has(url))
        disk.put(url, bytes)
        assertTrue(disk.has(url))
        assertArrayEquals(bytes, disk.get(url))
    }

    @Test
    fun `没写过的地址读回 null`() {
        val disk = ArtworkDisk(temp.newFolder("artwork"))
        assertNull(disk.get(url))
    }

    @Test
    fun `超过上限时删掉最旧的`() {
        val disk = ArtworkDisk(temp.newFolder("artwork"), limit = 3)
        val urls = (1..8).map { index -> "$url&index=$index" }
        urls.forEachIndexed { index, value ->
            disk.put(value, byteArrayOf(index.toByte()))
            // 让 mtime 有先后，否则同一毫秒内删哪个都"对"
            Thread.sleep(5)
        }
        val remaining = temp.root.listFiles()!!.flatMap { dir -> dir.listFiles()!!.toList() }
        assertTrue("上限没生效：${remaining.size}", remaining.size <= 3)
        // 最新写的那张必须还在
        assertTrue(disk.has(urls.last()))
    }
}
