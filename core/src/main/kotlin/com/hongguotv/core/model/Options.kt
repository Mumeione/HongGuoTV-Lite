package com.hongguotv.core.model

/**
 * 档位循环：取 [current] 的下一档，用在"按一下换下一个值"的设置项上。
 *
 * 之所以放进 core 而不是各页面自写：设置页与播放侧栏各有一份一模一样的实现（S6 review 发现），
 * 连"盘上的值被改坏时回落到第一档"这条兜底都抄了一遍——两处分别改错一次，用户就会看到
 * "设置页能循环、播放器里点了没反应"。
 *
 * 空表返回原值（没有档位可换，不该崩）。
 */
fun <T> List<T>.nextAfter(current: T): T {
    if (isEmpty()) return current
    val index = indexOf(current)
    return if (index < 0) first() else this[(index + 1) % size]
}
