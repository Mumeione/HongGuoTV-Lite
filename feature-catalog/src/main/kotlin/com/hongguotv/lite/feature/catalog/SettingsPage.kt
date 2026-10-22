package com.hongguotv.lite.feature.catalog

import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import com.hongguotv.core.model.Settings
import com.hongguotv.lite.uikit.focus.FocusKey
import com.hongguotv.lite.uikit.focus.FocusSections
import com.hongguotv.lite.uikit.focus.navKey
import com.hongguotv.lite.uikit.page.TwoStepConfirm
import com.hongguotv.lite.uikit.ui.SettingRow
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

    private lateinit var qualityRow: SettingRow
    private lateinit var speedRow: SettingRow
    private lateinit var frameRow: SettingRow
    private lateinit var autoNextRow: SettingRow
    private lateinit var filterRow: SettingRow
    private lateinit var sortRow: SettingRow
    private lateinit var clearRow: SettingRow
    private lateinit var startupRow: SettingRow

    private val confirm = TwoStepConfirm()

    override fun onBuild() {
        // B 方案（mockup 画面7）：「设置」26 + 说明跟在旁边
        val header = scaffold.showHeader("设置")
        header.titleSize(tokens.type.display)
        header.leadNote("偏好自动保存，下一部剧继续使用")

        // 不滚动：这一页固定两栏 4 行，用会撑满高度的 body 才能把页脚压在页底
        // （mockup：`.setbody{flex:1}` 居中两栏、`.setfoot` 贴底）
        val body = scaffold.body()
        body.addView(spacer(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

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
                syncLabels()
            }
        }

        body.addView(spacer(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        body.addView(ui.text(footerNote(), tokens.type.footnote, tokens.colors.dim).apply {
            setPadding(ui.dp(tokens.space.xs), 0, 0, ui.dp(tokens.space.m))
        })

        syncLabels()
        applyFocus()
    }

    // ---------- 构建小块 ----------

    private fun spacer(): View = View(ui.context)

    private fun heading(group: LinearLayout, title: String, subtitle: String) {
        group.addView(ui.text(title, tokens.type.group).apply { setTypeface(null, Typeface.BOLD) })
        group.addView(ui.text(subtitle, tokens.type.caption, tokens.colors.muted).apply {
            setPadding(0, ui.dp(tokens.space.xs), 0, ui.dp(tokens.space.l))
        })
    }

    private fun setting(group: LinearLayout, label: String, onTap: () -> Unit): SettingRow {
        val row = ui.settingRow(label, "", onTap)
        group.addView(row, ui.params(width = ViewGroup.LayoutParams.MATCH_PARENT, bottomGap = tokens.metrics.setRowGap))
        return row
    }

    private fun footerNote(): String = buildString {
        append("红果 TV")
        if (host.versionName.isNotBlank()) append(" · ").append(host.versionName)
        append(" · 非官方客户端 · 开源许可 GPL-3.0，完整清单见 NOTICE.md")
    }

    private fun syncLabels() {
        val current = host.settings.current()
        qualityRow.value(SettingRows.qualityLabel(current.quality))
        speedRow.value(SettingRows.speedLabel(current.speed))
        frameRow.value(SettingRows.frameLabel(current.frame))
        autoNextRow.value(if (current.autoNext) "开启" else "关闭")
        filterRow.value(SettingRows.filterLabel(current.filter))
        sortRow.value(SettingRows.sortLabel(current.seasonSortAsc))
        // 清空是不可恢复动作：值这一列先说清后果（mockup 的「不可恢复」），
        // 第二次按才换成「再按一次确认」——比把警告塞进主标签更贴近表格的读法
        clearRow.value(if (confirm.isArmed(KEY_CLEAR)) "再按一次确认" else "不可恢复")
        startupRow.value(SettingRows.tabLabel(current.startupTab))
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
            syncLabels()
            return
        }
        syncLabels()
        scope.launch {
            host.progress.clearHistory()
            Toast.makeText(ui.context, "已清空观看记录", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- 焦点：两列网格 ----------

    /** 首焦：第一项设置 → 当前导航项。进设置页就是要改东西，不该先按几下往下走。 */
    override fun startFocus(): List<FocusKey> =
        listOf(FocusKey("setting:L0"), navKey(CatalogTab.SETTINGS.ordinal))

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

        // 侧栏由 `applyPlan` 统一当"侧段"加进去，这里只提交内容段——
        // 再放一次 navSection() 会让同一批 nav key 出现在两处，焦点图直接拒绝
        val sections = listOf(
            FocusSections.section(keys, 2),
        )
        applyPlan(sections, views)
    }

    private companion object {
        const val KEY_CLEAR = "clear-history"
    }
}
