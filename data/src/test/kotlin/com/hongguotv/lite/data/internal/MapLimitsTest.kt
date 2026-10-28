package com.hongguotv.lite.data.internal

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 插入顺序淘汰。两处调用（搜索锚点证据、季线缓存）都靠"命中先 remove 再 put"维持
 * 最近使用在队尾，所以淘汰顺序必须是**最早插入的那条**——顺序错了淘汰的就是还在用的数据。
 */
class MapLimitsTest {

    @Test
    fun keepsTheMapUntouchedWhileUnderTheLimit() {
        val map = linkedMapOf("a" to 1, "b" to 2)

        map.evictOldestUntil(4)

        assertEquals(listOf("a", "b"), map.keys.toList())
    }

    @Test
    fun evictsTheOldestEntriesFirst() {
        val map = linkedMapOf("a" to 1, "b" to 2, "c" to 3, "d" to 4)

        map.evictOldestUntil(2)

        assertEquals(listOf("c", "d"), map.keys.toList())
    }

    @Test
    fun aReinsertedKeyCountsAsTheNewest() {
        val map = linkedMapOf("a" to 1, "b" to 2, "c" to 3)
        // 命中最旧的那条：挪到队尾之后，该被淘汰的是原来的第二位
        map.remove("a")
        map["a"] = 1

        map.evictOldestUntil(2)

        assertEquals(listOf("c", "a"), map.keys.toList())
    }

    @Test
    fun aZeroLimitEmptiesTheMap() {
        val map = linkedMapOf("a" to 1, "b" to 2)

        map.evictOldestUntil(0)

        assertEquals(0, map.size)
    }
}
