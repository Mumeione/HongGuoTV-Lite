package com.hongguotv.core.model

/**
 * 收藏容量（2026-10-26 容量治理：500 → 100）。
 *
 * 放在 core 是**单一来源**：写入端（本地库，按 `addedAt` 最旧淘汰）与页面（收藏前提示
 * "会替换掉哪一部"）读同一个数。两处各写一个 100，改一处就会漂。
 */
object FavoriteLimits {
    const val CAPACITY: Int = 100
}
