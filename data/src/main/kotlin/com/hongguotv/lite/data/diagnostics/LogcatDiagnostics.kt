package com.hongguotv.lite.data.diagnostics

import android.util.Log
import com.hongguotv.core.port.DiagnosticsPort

/**
 * [DiagnosticsPort] 的生产实现：五段探针与关键事件统一进 logcat。
 *
 * 日志口径（改字段前先看这里）：
 * - `stage` 只记**阶段名 + 目标 + 耗时 + 成败**，不记 query / body / 设备号——
 *   这些是风控身份字段，进了日志就等于把身份写进别人能抓的地方。
 * - `event` 记的是"这次请求得到了什么"（条数、清晰度、是否加密），不记用户输入原文。
 * - 失败用 WARN，成功用 DEBUG；`adb logcat -s HongGuoTV` 默认能看到 WARN 与 event。
 */
class LogcatDiagnostics : DiagnosticsPort {

    override fun stage(stage: DiagnosticsPort.Stage, ok: Boolean, detail: String) {
        Log.println(
            if (ok) Log.DEBUG else Log.WARN,
            TAG,
            "${stage.name} ${if (ok) "ok" else "FAIL"} $detail",
        )
    }

    override fun event(name: String, fields: Map<String, Any?>) {
        val text = fields.entries.joinToString(" ") { (key, value) -> "$key=$value" }
        Log.println(Log.INFO, TAG, "EVENT $name${if (text.isEmpty()) "" else " $text"}")
    }

    companion object {
        /** `adb logcat -s HongGuoTV` */
        const val TAG = "HongGuoTV"
    }
}
