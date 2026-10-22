package com.hongguotv.lite.feature.player

import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import com.hongguotv.core.usecase.EpisodeJump
import com.hongguotv.core.usecase.EpisodePages
import com.hongguotv.lite.uikit.focus.FocusKey
import com.hongguotv.lite.uikit.focus.FocusSections
import com.hongguotv.lite.uikit.page.PagerRow
import com.hongguotv.lite.uikit.token.Tokens
import com.hongguotv.lite.uikit.ui.ButtonVariant
import com.hongguotv.lite.uikit.ui.TvButton
import com.hongguotv.lite.uikit.ui.UiKit

/** 播放设置的五项（顺序即视觉顺序）。枚举而不是下标：以后加一项不会把回调顺序全错位。 */
internal enum class PlayerSetting { SPEED, QUALITY, FRAME, SLEEP, RESTART }

/**
 * MENU 键呼出的侧栏：选集 / 播放设置 / 数字跳集三种模式共用一块区域。
 *
 * **不是 Dialog**：Dialog 是独立窗口，`dispatchKeyEvent` 收不到它的按键，焦点还要跨窗口恢复
 * （旧实现为此写了 `onKeyListener` + `pendingFocus` 两处补丁）。这里只是播放页里的一层覆盖，
 * 焦点图、焦点记忆、按键表全部照用。
 *
 * 集数格复用 `EpisodePages` 的同一份算术、侧栏自己的参数（10 列 × 5 行 = 50 集/页，
 * 维护者口径：画面外只留一小块侧栏，格不能太密）与 S5 的 `PagerRow`：
 * 详情页与侧栏看到的是**同一种格子**，352 集的剧在详情页翻 4 页、在侧栏翻 8 页。
 */
internal class PlayerSidebar(private val ui: UiKit, private val callbacks: Callbacks) {

    enum class Mode { EPISODES, SETTINGS, JUMP }

    interface Callbacks {
        /** 侧栏要求换模式（"播放设置 / 返回选集"按钮）。 */
        fun onMode(mode: Mode)

        fun selectEpisode(index: Int)

        fun changeSetting(setting: PlayerSetting)

        fun close()
    }

    data class Model(
        val title: String,
        val status: String,
        val total: Int,
        val current: Int,
        val watchedUpTo: Int,
        /** 播放设置五行的标签（文案由 `PlayerText.settingsRows` 给）。 */
        val settings: List<String>,
    )

    private val tokens: Tokens = ui.tokens

    /** 侧栏的分页口径：一行 10 格，一页 5 行 = 50 集。 */
    private val pagination = EpisodePages.SIDEBAR

    var mode: Mode = Mode.EPISODES
        private set

    private var model: Model? = null
    private var page = 0
    private var lastCurrent = -1
    private var digits = ""

    private val titleView: TextView = ui.text("", tokens.type.title, tokens.colors.text).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    private val statusView: TextView = ui.text("", tokens.type.caption, tokens.colors.muted).apply {
        maxLines = 1
        setPadding(0, ui.dp(tokens.space.xxs), 0, ui.dp(tokens.space.s))
    }

    private val modeButton: TvButton
    private val jumpButton: TvButton
    private val pager = PagerRow(ui)
    private val gridHost = ui.column()
    private val episodesArea = ui.column()
    private val settingsArea = ui.column()
    private val jumpArea = ui.column()
    private val digitsView: TextView = ui.text("", tokens.type.body * tokens.metrics.jumpDigitsScale, tokens.colors.text).apply {
        gravity = Gravity.CENTER
        setTypeface(null, android.graphics.Typeface.BOLD)
        setPadding(0, ui.dp(tokens.space.xs), 0, ui.dp(tokens.space.xs))
    }
    private val jumpHintView: TextView = ui.text("", tokens.type.caption, tokens.colors.muted).apply {
        gravity = Gravity.CENTER
        setPadding(0, 0, 0, ui.dp(tokens.space.s))
    }
    private val hintView: TextView = ui.text("", tokens.type.caption, tokens.colors.muted).apply {
        setPadding(0, ui.dp(tokens.space.s), 0, 0)
    }

    private var cells: List<TvButton> = emptyList()
    private var cellIndexes: List<Int> = emptyList()
    private var builtPage = -1
    private var builtTotal = -1

    /** 集数格重建次数。重建会换掉一批 View 实例，页面据此必须重提一次焦点图。 */
    private var buildStamp = 0
    private val settingRows = mutableListOf<TvButton>()
    private val keypad = mutableListOf<TvButton>()

    val view: LinearLayout

    init {
        modeButton = ui.button("播放设置") { callbacks.onMode(if (mode == Mode.EPISODES) Mode.SETTINGS else Mode.EPISODES) }
        jumpButton = ui.button("跳转集数") {
            if (mode == Mode.JUMP) submitDigits() else callbacks.onMode(Mode.JUMP)
        }
        pager.onPage = { delta -> goPage(delta) }

        val actionRow = ui.row().apply {
            addView(modeButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = ui.dp(tokens.space.s)
            })
            addView(jumpButton)
        }

        // 标题与两个动作同排：侧栏是覆盖层，每省下一行就多留一行画面。
        // 标题吃剩余宽度，动作靠右，视线从"正在看哪一集"自然滑到"还能干什么"。
        val titleRow = ui.row().apply {
            addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = ui.dp(tokens.space.m)
            })
            addView(actionRow)
        }

        episodesArea.addView(pager.view)
        episodesArea.addView(gridHost)

        PlayerSetting.entries.forEachIndexed { index, setting ->
            val row = ui.button("", variant = ButtonVariant.ROW) { callbacks.changeSetting(setting) }
            settingRows += row
            settingsArea.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = if (index == PlayerSetting.entries.lastIndex) 0 else ui.dp(tokens.space.xs)
            })
        }

        jumpArea.addView(digitsView)
        jumpArea.addView(jumpHintView)
        listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("清空", "0", "退格"),
        ).forEach { line ->
            val row = ui.row()
            line.forEach { label ->
                val key = ui.button(label) { onKeypad(label) }
                keypad += key
                row.addView(key, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    val gap = ui.dp(tokens.space.xxs)
                    leftMargin = gap
                    rightMargin = gap
                    topMargin = gap
                })
            }
            jumpArea.addView(row)
        }

        view = ui.tile(radiusDp = tokens.shapes.panel, paddingDp = 0).apply {
            visibility = View.GONE
            setPadding(
                ui.dp(tokens.metrics.sidebarPaddingH),
                ui.dp(tokens.metrics.sidebarPaddingV),
                ui.dp(tokens.metrics.sidebarPaddingH),
                ui.dp(tokens.metrics.sidebarPaddingV),
            )
            val gap = ui.dp(tokens.metrics.sidebarGap)
            val wrap = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(titleRow)
            addView(statusView)
            addView(episodesArea, LinearLayout.LayoutParams(wrap).apply { topMargin = gap })
            addView(settingsArea, LinearLayout.LayoutParams(wrap).apply { topMargin = gap })
            addView(jumpArea, LinearLayout.LayoutParams(wrap).apply { topMargin = gap })
            addView(hintView)
        }
        settingsArea.visibility = View.GONE
        jumpArea.visibility = View.GONE
    }

    // ---------- 显隐 ----------

    fun show() {
        view.visibility = View.VISIBLE
    }

    fun hide() {
        view.visibility = View.GONE
    }

    val visible: Boolean get() = view.visibility == View.VISIBLE

    /**
     * 当前焦点结构的"指纹"：模式、页码、各类可聚焦项的数量。
     * 页面据此判断"要不要重提焦点图"——每秒一次的刷新不能重提，否则用户按着方向键会被按回第一行。
     */
    fun bindingSignature(): String = "$mode:$page:${cells.size}:${settingRows.size}:${keypad.size}:$buildStamp"

    fun setMode(next: Mode) {
        mode = next
        digits = ""
        model?.let { bind(it) }
    }

    // ---------- 渲染 ----------

    fun bind(model: Model) {
        this.model = model
        titleView.text = model.title
        statusView.text = model.status
        if (model.total > 0 && lastCurrent != model.current) {
            // 换集（含片尾自动连播）之后，侧栏要翻到当前集所在的那一组
            page = pagination.pageOf(model.current)
            builtPage = -1
        }
        lastCurrent = model.current
        page = page.coerceIn(0, pagination.pages(model.total) - 1)

        modeButton.label(if (mode == Mode.EPISODES) "播放设置" else "返回选集")
        jumpButton.label(if (mode == Mode.JUMP) "定位" else "跳转集数")
        episodesArea.visibility = if (mode == Mode.EPISODES) View.VISIBLE else View.GONE
        settingsArea.visibility = if (mode == Mode.SETTINGS) View.VISIBLE else View.GONE
        jumpArea.visibility = if (mode == Mode.JUMP) View.VISIBLE else View.GONE

        when (mode) {
            Mode.EPISODES -> bindEpisodes(model)
            Mode.SETTINGS -> bindSettings(model)
            Mode.JUMP -> bindJump()
        }
        // 提示只写"侧栏开着时真的能按的键"：侧栏里方向键走焦点图（不再快进/换台），
        // 写"←→ 快退快进"会让人按了发现只挪光标。
        hintView.text = when (mode) {
            Mode.EPISODES -> "方向键 选集　确认 播放　MENU 关闭"
            Mode.SETTINGS -> "确认 修改　返回 回到选集"
            Mode.JUMP -> "数字键输入　确认 定位　返回 回到选集"
        }
    }

    private fun bindEpisodes(model: Model) {
        val pages = pagination.pages(model.total)
        val range = pagination.page(model.total, page)
        if (builtPage != page || builtTotal != model.total) {
            buildGrid(range, model)
            builtPage = page
            builtTotal = model.total
        } else {
            cells.forEachIndexed { position, cell -> cell.isSelected = cellIndexes[position] == model.current }
        }
        if (pages > 1) {
            pager.show(PlayerText.range(range, model.total), hasPrev = page > 0, hasNext = page < pages - 1)
        } else {
            pager.hide()
        }
    }

    private fun buildGrid(range: IntRange, model: Model) {
        gridHost.removeAllViews()
        val built = mutableListOf<TvButton>()
        val indexes = mutableListOf<Int>()
        val gap = ui.dp(tokens.metrics.episodeCellGap)
        range.chunked(pagination.columns).forEach { rowIndexes ->
            val row = ui.rowTop()
            rowIndexes.forEachIndexed { position, index ->
                val cell = ui.button(
                    label = "${index + 1}",
                    selected = index == model.current,
                    watched = index < model.watchedUpTo,
                    variant = ButtonVariant.CELL,
                ) { callbacks.selectEpisode(index) }
                row.addView(
                    cell,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (position < rowIndexes.lastIndex) rightMargin = gap
                    },
                )
                built += cell
                indexes += index
            }
            // 末行补空格：缺格会让最后几个集数被拉宽，10 列的视觉对齐就断了
            repeat(pagination.columns - rowIndexes.size) {
                row.addView(Space(ui.context), LinearLayout.LayoutParams(0, 1, 1f))
            }
            gridHost.addView(
                row,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = gap
                },
            )
        }
        cells = built
        cellIndexes = indexes
        buildStamp++
    }

    /** 设置行只改标签，**不重建**：重建会把光标拉回第一行。 */
    private fun bindSettings(model: Model) {
        model.settings.forEachIndexed { index, label -> settingRows.getOrNull(index)?.label(label) }
    }

    private fun bindJump() {
        digitsView.text = if (digits.isEmpty()) "—" else digits
        jumpHintView.text = PlayerText.jumpHint(digits)
    }

    private fun goPage(delta: Int) {
        val model = model ?: return
        val pages = pagination.pages(model.total)
        val next = (page + delta).coerceIn(0, pages - 1)
        if (next == page) return
        page = next
        bind(model)
    }

    // ---------- 数字跳集 ----------

    fun appendDigit(digit: Int): Boolean {
        if (mode != Mode.JUMP) return false
        if (digits.length >= MAX_DIGITS) return true
        digits += digit.toString()
        bindJump()
        return true
    }

    fun backspace(): Boolean {
        if (mode != Mode.JUMP) return false
        digits = digits.dropLast(1)
        bindJump()
        return true
    }

    /** 定位。成功则通知页面换集（页面会把模式切回选集），失败就地提示范围。 */
    fun submitDigits(): Boolean {
        if (mode != Mode.JUMP) return false
        val total = model?.total ?: return true
        val target = EpisodeJump.parse(digits, total)
        if (target == null) {
            jumpHintView.text = PlayerText.jumpRejected(total)
            return true
        }
        digits = ""
        callbacks.selectEpisode(target)
        return true
    }

    private fun onKeypad(label: String) {
        when (label) {
            "清空" -> digits = ""
            "退格" -> digits = digits.dropLast(1)
            else -> if (digits.length < MAX_DIGITS) digits += label
        }
        bindJump()
    }

    // ---------- 焦点 ----------

    fun focusBinding(preferred: FocusKey? = null): FocusBinding? {
        val model = this.model ?: return null
        val views = LinkedHashMap<FocusKey, View>()
        val actionKeys = mutableListOf(KEY_MODE, KEY_JUMP)
        views[KEY_MODE] = modeButton
        views[KEY_JUMP] = jumpButton

        val pagerKeys = mutableListOf<FocusKey>()
        val gridKeys = mutableListOf<FocusKey>()
        val settingKeys = mutableListOf<FocusKey>()
        val keypadKeys = mutableListOf<FocusKey>()

        if (mode == Mode.EPISODES && pager.view.visibility == View.VISIBLE) {
            if (pager.prev.isEnabled) {
                views[KEY_PREV] = pager.prev
                pagerKeys += KEY_PREV
            }
            if (pager.next.isEnabled) {
                views[KEY_NEXT] = pager.next
                pagerKeys += KEY_NEXT
            }
        }
        if (mode == Mode.EPISODES) {
            cellIndexes.forEachIndexed { position, index ->
                val key = episodeKey(index)
                views[key] = cells[position]
                gridKeys += key
            }
        }
        if (mode == Mode.SETTINGS) {
            settingRows.forEachIndexed { index, row ->
                val key = FocusKey("$PREFIX:set:$index")
                views[key] = row
                settingKeys += key
            }
        }
        if (mode == Mode.JUMP) {
            keypad.forEachIndexed { index, key ->
                val focusKey = FocusKey("$PREFIX:key:$index")
                views[focusKey] = key
                keypadKeys += focusKey
            }
        }

        val plan = FocusSections.plan(
            listOf(
                FocusSections.section(actionKeys, actionKeys.size),
                FocusSections.section(pagerKeys, pagerKeys.size),
                FocusSections.section(gridKeys, pagination.columns),
                FocusSections.section(settingKeys, 1),
                FocusSections.section(keypadKeys, JUMP_COLUMNS),
            ),
        ) ?: return null

        val fallback = when (mode) {
            Mode.EPISODES -> episodeKey(model.current).takeIf { views.containsKey(it) } ?: gridKeys.firstOrNull() ?: KEY_MODE
            Mode.SETTINGS -> settingKeys.firstOrNull() ?: KEY_MODE
            Mode.JUMP -> keypadKeys.firstOrNull() ?: KEY_MODE
        }
        return FocusBinding(plan, views, preferred ?: fallback)
    }

    companion object {
        private const val PREFIX = "sb"
        private const val MAX_DIGITS = 6
        private const val JUMP_COLUMNS = 3

        val KEY_MODE = FocusKey("$PREFIX:mode")
        val KEY_JUMP = FocusKey("$PREFIX:jump")
        val KEY_PREV = FocusKey("$PREFIX:prev")
        val KEY_NEXT = FocusKey("$PREFIX:next")

        fun episodeKey(index: Int): FocusKey = FocusKey("$PREFIX:ep:$index")
    }
}
