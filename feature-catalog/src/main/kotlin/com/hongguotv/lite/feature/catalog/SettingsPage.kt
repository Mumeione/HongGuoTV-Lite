package com.hongguotv.lite.feature.catalog

import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import com.hongguotv.core.model.Settings
import com.hongguotv.lite.uikit.focus.FocusKey
import com.hongguotv.lite.uikit.focus.FocusSections
import com.hongguotv.lite.uikit.page.TwoStepConfirm
import com.hongguotv.lite.uikit.ui.ButtonVariant
import com.hongguotv.lite.uikit.ui.TvButton
import kotlinx.coroutines.launch

/**
 * 设置页。
 *
 * 左栏播放偏好、右栏片库与数据，各四行；焦点是一个两列的网格（左右横移、上下逐行）。
 * 每一项按下即切到下一档（循环），不弹选择框——档位最多 6 个，循环比弹窗少一次按键。
 *
 * 这里改的是 `SettingsPort` 的同一份事实：搜索页的筛选与排序开关读的也是它。
 */
internal class SettingsPage(host: CatalogFeature) : CatalogPage(host, CatalogTab.SETTINGS) {

    private lateinit var qualityRow: TvButton
    private lateinit var speedRow: TvButton
    private lateinit var frameRow: TvButton
    private lateinit var autoNextRow: TvButton
    private lateinit var filterRow: TvButton
    private lateinit var sortRow: TvButton
    private lateinit var clearRow: TvButton
    private lateinit var startupRow: TvButton

    private val confirm = TwoStepConfirm()

    override fun onBuild() {
        val header = scaffold.showHeader("按你的习惯播放")
        header.note("偏好自动保存，下一部剧继续使用。")

        val body = scaffold.scrollBody()
        val groups = ui.rowTop()
        val left = ui.panel()
        val right = ui.panel()
        groups.addView(left, ui.params(width = 0, weight = 1f, rightGap = tokens.space.m))
        groups.addView(right, ui.params(width = 0, weight = 1f))
        body.addView(groups, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        heading(left, "播放偏好", "所有剧集共用，播放中也可调整")
        qualityRow = setting(left, "默认清晰度") { cycleQuality() }
        speedRow = setting(left, "默认倍速") { cycleSpeed() }
        frameRow = setting(left, "画面模式") { cycleFrame() }
        autoNextRow = setting(left, "自动连播") { toggleAutoNext() }

        heading(right, "片库与数据", "数据只保存在本机，与红果官方无关")
        filterRow = setting(right, "默认片库筛选") { cycleFilter() }
        sortRow = setting(right, "搜索结果顺序") { toggleSort() }
        clearRow = setting(right, "清空观看记录") { requestClear() }
        startupRow = setting(right, "启动页面") { cycleStartup() }
        // 焦点移开就撤销确认：手滑"按过一次"不该一直挂着
        clearRow.onFocused = { focused ->
            if (!focused) {
                confirm.disarm()
                clearRow.label("清空观看记录")
            }
        }

        body.addView(ui.text(footerNote(), tokens.type.caption, tokens.colors.muted).apply {
            setPadding(ui.dp(tokens.space.xxs), ui.dp(tokens.space.xl), 0, ui.dp(tokens.space.m))
        })

        syncLabels()
        applyFocus()
    }

    // ---------- 构建小块 ----------

    private fun heading(group: LinearLayout, title: String, subtitle: String) {
        group.addView(ui.text(title, tokens.type.title).apply { setTypeface(null, Typeface.BOLD) })
        group.addView(ui.text(subtitle, tokens.type.caption, tokens.colors.muted).apply {
            setPadding(0, ui.dp(tokens.space.xs), 0, ui.dp(tokens.space.l))
        })
    }

    private fun setting(group: LinearLayout, label: String, onTap: () -> Unit): TvButton {
        val row = ui.button(label, variant = ButtonVariant.ROW, onTap = onTap)
        group.addView(row, ui.params(width = ViewGroup.LayoutParams.MATCH_PARENT, bottomGap = tokens.space.s))
        return row
    }

    private fun footerNote(): String = buildString {
        append("红果 TV")
        if (host.versionName.isNotBlank()) append(" · ").append(host.versionName)
        append(" · 非官方客户端 · 开源许可 GPL-3.0，完整清单见 NOTICE.md")
    }

    private fun syncLabels() {
        val current = host.settings.current()
        qualityRow.label("默认清晰度  ·  ${SettingRows.qualityLabel(current.quality)}")
        speedRow.label("默认倍速  ·  ${SettingRows.speedLabel(current.speed)}")
        frameRow.label("画面模式  ·  ${SettingRows.frameLabel(current.frame)}")
        autoNextRow.label("自动连播  ·  ${if (current.autoNext) "开启" else "关闭"}")
        filterRow.label("默认片库筛选  ·  ${SettingRows.filterLabel(current.filter)}")
        sortRow.label("搜索结果顺序  ·  ${SettingRows.sortLabel(current.seasonSortAsc)}")
        clearRow.label("清空观看记录")
        startupRow.label("启动页面  ·  ${SettingRows.tabLabel(current.startupTab)}")
    }

    // ---------- 交互 ----------

    private fun cycleQuality() {
        val next = SettingRows.next(host.settings.current().quality, SettingRows.qualities)
        persist { it.copy(quality = next) }
    }

    private fun cycleSpeed() {
        val next = SettingRows.next(host.settings.current().speed, SettingRows.speeds)
        persist { it.copy(speed = next) }
    }

    private fun cycleFrame() {
        val next = SettingRows.next(host.settings.current().frame, SettingRows.frames)
        persist { it.copy(frame = next) }
    }

    private fun toggleAutoNext() {
        val next = !host.settings.current().autoNext
        persist { it.copy(autoNext = next) }
    }

    private fun cycleFilter() {
        val next = SettingRows.next(host.settings.current().filter, SettingRows.filters)
        persist { it.copy(filter = next) }
    }

    private fun toggleSort() {
        val next = !host.settings.current().seasonSortAsc
        persist { it.copy(seasonSortAsc = next) }
    }

    private fun cycleStartup() {
        val next = SettingRows.next(host.settings.current().startupTab, List(CatalogTab.entries.size) { it })
        persist { it.copy(startupTab = next) }
    }

    /**
     * 写偏好并**从端口重读标签**：文案只从一份事实派生，不在七个 cycle 里各拼一遍。
     * 端口的更新是同步生效、异步落盘，所以紧接着读到的就是新值。
     */
    private fun persist(transform: (Settings) -> Settings) {
        scope.launch {
            host.settings.update(transform)
            syncLabels()
        }
    }

    private fun requestClear() {
        if (!confirm.press(KEY_CLEAR)) {
            clearRow.label("再按一次确认清空（收藏会保留）")
            return
        }
        clearRow.label("清空观看记录")
        scope.launch {
            host.progress.clearHistory()
            Toast.makeText(ui.context, "已清空观看记录", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- 焦点：两列网格 ----------

    private fun applyFocus() {
        val leftRows = listOf(qualityRow, speedRow, frameRow, autoNextRow)
        val rightRows = listOf(filterRow, sortRow, clearRow, startupRow)

        val views = LinkedHashMap<FocusKey, View>()
        views.putAll(navViews())
        leftRows.forEachIndexed { index, row -> views[FocusKey("setting:L$index")] = row }
        rightRows.forEachIndexed { index, row -> views[FocusKey("setting:R$index")] = row }

        // 视觉上是左右两栏，焦点图里是"两列的网格"：行内左右、行间上下
        val keys = mutableListOf<FocusKey>()
        for (index in 0 until maxOf(leftRows.size, rightRows.size)) {
            if (index < leftRows.size) keys += FocusKey("setting:L$index")
            if (index < rightRows.size) keys += FocusKey("setting:R$index")
        }

        val sections = listOf(
            navSection(),
            FocusSections.section(keys, 2),
        )
        applyPlan(sections, views)
    }

    private companion object {
        const val KEY_CLEAR = "clear-history"
    }
}
