package com.hongguotv.lite.data.library

/**
 * 测试共用的内存 [KeyValueStore] 假体：行为与 [SharedPreferencesStore] 对齐。
 *
 * 关键是 commit 的类型白名单——假体不校验的话，业务代码存了 prefs 存不了的类型
 * （比如 Double），单测照样全绿，真机上才炸（4b229b8：Long 分支漏掉，636 项单测
 * 也挡不住启动崩溃）。生产实现每加或改一个类型，这里同步改一处即可。
 */
class ValidatingMemoryStore(initial: Map<String, Any?> = emptyMap()) : KeyValueStore {

    /** 直通内存视图：测试直接断言"写了什么"。 */
    val data = LinkedHashMap<String, Any?>(initial)

    /** commit 调用次数：写合并/防抖测试用它断言"没多写盘"。 */
    var commits = 0
        private set

    override fun all(): Map<String, Any?> = data.toMap()

    override fun commit(values: Map<String, Any?>) {
        values.forEach { (key, value) ->
            when (value) {
                null -> data.remove(key)
                is String, is Int, is Long, is Float, is Boolean -> data[key] = value
                is Set<*> -> data[key] = value.filterIsInstance<String>().toSet()
                else -> throw IllegalArgumentException("不支持的本地存储类型：${value::class.java.name}")
            }
        }
        commits++
    }
}
