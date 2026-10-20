package com.hongguotv.lite.nav

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 目录页按返回的产品规则（旧 `MainActivity.goBack()` 的行为）：
 * 不在搜索页先回搜索页，已经在搜索页才退出应用。
 *
 * 单独一个文件是因为它是**产品的返回语义**，与"栈怎么弹"是两件事：
 * 栈只回答"还能不能退"，退到哪由这条规则决定。
 */
class CatalogBackTest {

    @Test
    fun `不在搜索页按返回先回搜索页`() {
        assertEquals(CatalogBack.TO_SEARCH, backFromCatalog(onSearchTab = false))
    }

    @Test
    fun `已经在搜索页按返回才退出应用`() {
        assertEquals(CatalogBack.EXIT, backFromCatalog(onSearchTab = true))
    }
}
