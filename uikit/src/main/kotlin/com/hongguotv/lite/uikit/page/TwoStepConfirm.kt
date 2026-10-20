package com.hongguotv.lite.uikit.page

/**
 * 两步确认：第一次按下只进入确认态，同一个键再按才真的执行。
 *
 * 设计系统里至今没有模态组件（弹窗不在 uikit），破坏性操作靠这个：焦点移开即撤销，
 * 手滑多按一次不会删掉进度或收藏。删除记录、取消收藏都走它。
 */
class TwoStepConfirm {

    private var armed: String? = null

    fun isArmed(key: String): Boolean = armed == key

    /** @return true = 本次按下就是确认，调用方可以执行了 */
    fun press(key: String): Boolean {
        if (armed == key) {
            armed = null
            return true
        }
        armed = key
        return false
    }

    fun disarm() {
        armed = null
    }
}
