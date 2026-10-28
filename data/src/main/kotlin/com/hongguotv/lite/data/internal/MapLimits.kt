package com.hongguotv.lite.data.internal

/**
 * 把最旧的条目淘汰到不超过 [limit] 条。
 *
 * 接收者限定为 `LinkedHashMap`：只有它保证 `keys.first()` 是"最早插入的那条"。
 * 两处调用都用「命中先 `remove` 再 `put`」把命中的键挪到队尾，所以按插入顺序淘汰
 * 等价于按最近使用淘汰——换成 `HashMap` 就变成随机淘汰一条。
 */
internal fun <K, V> LinkedHashMap<K, V>.evictOldestUntil(limit: Int) {
    while (size > limit) remove(keys.first())
}
