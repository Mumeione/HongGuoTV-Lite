package com.hongguotv.lite.data.library

import android.content.Context

/**
 * 本地键值存储的最小面。Android 上由 [SharedPreferencesStore] 实现；
 * 单测用内存假体——业务逻辑（迁移、淘汰、编解码）不依赖 SharedPreferences 本身。
 */
interface KeyValueStore {
    fun all(): Map<String, Any?>

    /**
     * null = 删除该键。同一次 commit 里的键要么全部生效要么全部不生效。
     *
     * 写盘失败（磁盘满 / IO 错误）抛 [IllegalStateException]，不静默：调用方要么接得住
     * 并留痕（PrefsLibrary 的 `lastWriteFailure`），要么确认"丢了无害"后自己包 runCatching
     * （节流时间戳、设备注册落盘）。不接也不包的话，失败会沿调用链向上炸——这是有意的。
     */
    fun commit(values: Map<String, Any?>)
}

class SharedPreferencesStore(context: Context, name: String = PREFS_NAME) : KeyValueStore {

    private val prefs = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun all(): Map<String, Any?> = prefs.all

    override fun commit(values: Map<String, Any?>) {
        val editor = prefs.edit()
        values.forEach { (key, value) ->
            when (value) {
                null -> editor.remove(key)
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                // 不支持的类型是编程错误（静态错误），按端口契约直接抛参数异常，
                // 包成 LocalError 会让人误以为是磁盘坏了
                else -> throw IllegalArgumentException("不支持的本地存储类型：${value::class.java.name}")
            }
        }
        // 写盘走单线程写队列（见 PrefsLibrary），这里同步 commit 换取落盘确定性。
        // commit 返回 false = 磁盘满 / IO 错误（SharedPreferences 不抛异常，只回 false）：
        // 吞掉它，进度与收藏就成了"内存有、盘上无"，重启后静默消失。
        // 抛出去由调用方各自兜底：PrefsLibrary 留痕 lastWriteFailure；
        // UpdateManager 的节流时间戳与 DeviceSession 的注册落盘包 runCatching（丢了无害）。
        if (!editor.commit()) {
            throw IllegalStateException("本地写盘失败：commit 返回 false（磁盘满或 IO 错误）")
        }
    }

    companion object {
        /**
         * 沿用旧版的 prefs 文件名：升级安装后第一件事就是读旧数据（键名与结构见 SeriesCodec 与
         * PrefsLibrary 的迁移注释），换名字等于把用户的历史全丢掉。
         */
        const val PREFS_NAME = "native-library-v1"
    }
}
