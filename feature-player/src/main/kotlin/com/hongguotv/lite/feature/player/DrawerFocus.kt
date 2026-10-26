package com.hongguotv.lite.feature.player

import com.hongguotv.lite.uikit.focus.FocusKey

/**
 * 播放抽屉的焦点槽位：每行 [columns] 个分集格，行末再挂一个设置项。
 *
 * 为什么单独成纯函数：[FocusSection] 的槽位决定"程序性落焦"与方向键的列号映射，
 * 而末页不足 50 集时，缺的格子必须补 `null`——设置项否则会落到第 0 行的中间列，
 * 与视觉上右侧那一条竖排对不上。真实矩形绑定看不出这个问题，只有焦点图会算错。
 */
internal object DrawerFocus {

    /**
     * @param episodeKeys 本页可用的分集格 key，按集数顺序。
     * @param settingKeys 设置行 key，按视觉顺序（每行一个）。
     * @param columns 分集段的列数（设置列在它右边，整段列数 = columns + 1）。
     * @return 从上到下、从左到右的槽位；末行不足 10 格与多余的行都补 `null`。
     */
    fun slots(episodeKeys: List<FocusKey>, settingKeys: List<FocusKey>, columns: Int): List<FocusKey?> {
        require(columns >= 1) { "分集段的列数至少为 1，实际 $columns" }
        val rows = maxOf((episodeKeys.size + columns - 1) / columns, settingKeys.size).coerceAtLeast(1)
        val slots = ArrayList<FocusKey?>(rows * (columns + 1))
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                slots += episodeKeys.getOrNull(row * columns + column)
            }
            slots += settingKeys.getOrNull(row)
        }
        return slots
    }
}
