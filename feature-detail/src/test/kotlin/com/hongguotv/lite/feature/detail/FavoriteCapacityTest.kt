package com.hongguotv.lite.feature.detail

import com.hongguotv.core.model.FavoriteLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 收藏满时的提示：只钉两件事——第几部开始提醒、提醒里写谁。 */
class FavoriteCapacityTest {

    @Test
    fun theNoticeOnlyShowsWhenTheCapIsReached() {
        // 第 100 部（还没写进去）正好是"再加一条就要淘汰"的那一刻
        assertNull("差一部不提醒", capacityWarning(FavoriteLimits.CAPACITY - 1, "旧剧"))
        assertEquals(
            "收藏已满 100 部，将替换《旧剧》",
            capacityWarning(FavoriteLimits.CAPACITY, "旧剧"),
        )
    }

    @Test
    fun noOldestTitleMeansNothingToSay() {
        assertNull("拿不到要淘汰的标题时不说", capacityWarning(FavoriteLimits.CAPACITY, null))
    }
}
