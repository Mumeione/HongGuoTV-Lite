package com.hongguotv.lite.feature.catalog

import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.hongguotv.core.model.Settings
import com.hongguotv.core.model.UpdateState
import com.hongguotv.lite.uikit.focus.FocusKey
import com.hongguotv.lite.uikit.focus.FocusSections
import com.hongguotv.lite.uikit.focus.navKey
import com.hongguotv.lite.uikit.page.TwoStepConfirm
import com.hongguotv.lite.uikit.ui.ButtonVariant
import com.hongguotv.lite.uikit.ui.SettingRow
import com.hongguotv.lite.uikit.ui.TvButton
import kotlinx.coroutines.launch

/**
 * 设置页。
 *
 * 「检查更新」在页头（与收藏页同款：小胶囊按钮 + 旁边的状态说明文字，不弹窗）；
 * 左栏播放偏好四行；右栏片库与数据四行。焦点是"页头一粒 + 两列网格"：
 * 左右横移、上下逐行。每一项按下即切到下一档（循环），不弹选择框——
 * 档位最多 6 个，循环比弹窗少一次按键。
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
    private lateinit var updateButton: TvButton
    private lateinit var updateNote: TextView

    private val confirm = TwoStepConfirm()

    override fun onBuild() {
        // B 方案（mockup 画面7）：「设置」26 + 检查更新跟在旁边（收藏页同款）
        val header = scaffold.showHeader("设置")
        header.titleSize(tokens.type.display)
        updateButton = ui.button("检查更新", variant = ButtonVariant.SMALL) { onUpdateTap() }
        // 定宽（不是 minWidth）：按钮宽度不随旁边说明的长短变化（收藏页同一条纪律）
        header.lead().addView(
            updateButton,
            LinearLayout.LayoutParams(ui.dp(tokens.metrics.leadActionWidth), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = ui.dp(tokens.space.m)
            },
        )
        // 无更新 / 检查失败都写在这句话里，不做弹窗
        updateNote = header.leadNote("").apply { textSize = tokens.type.footnote }

        // 不滚动：这一页固定两栏（左 4 右 4）行，用会撑满高度的 body 才能把页脚压在页底
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
        // 启动时的静默检查可能已经把状态推到 Available：这行一建好就跟上。
        // 离开页面时在 release() 里摘除，别让管理器通知一个已销毁的页面。
        host.updates.onChange { onUpdatedRemotely() }
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
        append("红果TV-Lite")
        if (host.versionName.isNotBlank()) append(" · ").append(host.versionName)
        append(" · 非官方客户端 · GPL-3.0")
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
        updateNote.text = SettingRows.updateLabel(host.updates.state())
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

    // ---------- 检查更新 ----------

    /**
     * 「检查更新」的点击行为按当前状态分派：
     * 没查过/失败/已最新 → 检查；有新版 → 开始下载；下载完 → 拉起安装器；
     * 检查与下载进行中 → 忽略（防抖）。状态变化统一走 [syncLabels] 刷新值列。
     */
    private fun onUpdateTap() {
        when (val state = host.updates.state()) {
            is UpdateState.Idle, is UpdateState.Failed, is UpdateState.UpToDate ->
                scope.launch { host.updates.check() }
            is UpdateState.Available -> host.updates.download()
            is UpdateState.ReadyToInstall -> host.onInstallApk(state.apkPath)
            is UpdateState.Checking, is UpdateState.Downloading -> Unit
        }
        syncLabels()
    }

    /** 管理器的状态回调（已在主线程）：只刷值列，不动焦点。 */
    private fun onUpdatedRemotely() {
        scope.launch { syncLabels() }
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
        // 「检查更新」在页头：段在网格前面，网格首行往上一按就到（收藏页同款落法）
        views[FocusKey("settings:update")] = updateButton
        leftRows.forEachIndexed { index, row -> views[FocusKey("setting:L$index")] = row }
        rightRows.forEachIndexed { index, row -> views[FocusKey("setting:R$index")] = row }

        // 视觉上是左右两栏，焦点图里是"两列的网格"：行内左右、行间上下
        val keys = mutableListOf<FocusKey>()
        for (index in 0 until maxOf(leftRows.size, rightRows.size)) {
            if (index < leftRows.size) keys += FocusKey("setting:L$index")
            if (index < rightRows.size) keys += FocusKey("setting:R$index")
        }

        // 侧栏由 `applyPlan` 统一当"侧段"加进去，这里只提交内容段——
        // 再放一次 navSection() 会让同一批 nav key 出现在两处，焦点图直接拒绝。
        // 侧栏按右直接进「默认清晰度」行；页头「检查更新」只从下方（设置行按上）可达
        val sections = listOf(
            FocusSections.section(listOf(FocusKey("settings:update")), 1),
            FocusSections.section(keys, 2),
        )
        applyPlan(sections, views, sideEntry = FocusKey("setting:L0"))
    }

    private companion object {
        const val KEY_CLEAR = "clear-history"
    }

    override fun release() {
        // 先摘监听再取消作用域：管理器持有的回调不该指向一个已销毁的页面
        host.updates.onChange(null)
        super.release()
    }
}
