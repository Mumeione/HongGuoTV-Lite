package com.hongguotv.lite.feature.player

import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import com.hongguotv.core.usecase.EpisodeJump
import com.hongguotv.core.usecase.EpisodePages
import com.hongguotv.lite.uikit.focus.FocusKey
import com.hongguotv.lite.uikit.focus.FocusSection
import com.hongguotv.lite.uikit.focus.FocusSections
import com.hongguotv.lite.uikit.page.SideStrips
import com.hongguotv.lite.uikit.token.Tokens
import com.hongguotv.lite.uikit.ui.ButtonVariant
import com.hongguotv.lite.uikit.ui.SettingRow
import com.hongguotv.lite.uikit.ui.SettingRowStyle
import com.hongguotv.lite.uikit.ui.TvButton
import com.hongguotv.lite.uikit.ui.UiKit

/** 播放设置的五项（顺序即视觉顺序）。枚举而不是下标：以后加一项不会把回调顺序全错位。 */
internal enum class PlayerSetting { SPEED, QUALITY, FRAME, SLEEP, RESTART }

/**
 * MENU 键呼出的底部抽屉（B 方案，mockup 画面6）：左列选集、右列五项设置**同屏**。
 *
 * **不是 Dialog**：Dialog 是独立窗口，`dispatchKeyEvent` 收不到它的按键，焦点还要跨窗口恢复
 * （旧实现为此写了 `onKeyListener` + `pendingFocus` 两处补丁）。这里只是播放页里的一层覆盖，
 * 焦点图、焦点记忆、按键表全部照用。
 *
 * 与旧侧栏的三处不同：
 * - **选集与设置不再分页切换**：左列 10×5 选集、右列五项设置垂直居中，打开就都在——
     焦点图把右列当成选集格右侧的"第 11 列"，左右键直接互达；
 * - **状态行只报失败**（[Model.status] 由 [PlayerText.drawerStatus] 给）；
 * - 数字跳集保留：抽屉开着时**直接按数字键**进入跳集模式（不再需要先按「跳转集数」按钮），
 *   返回键回到选集。
 */
internal class PlayerSidebar(private val ui: UiKit, private val callbacks: Callbacks) {

    enum class Mode { EPISODES, JUMP }

    interface Callbacks {
        fun selectEpisode(index: Int)

        fun changeSetting(setting: PlayerSetting)

        fun close()

        /**
         * 翻组后分集格被整批重建：页面必须重提一次焦点图。
         * 不重提的话，新格子没有 `nextFocus*` 绑定，长条按右会指向已经销毁的旧格子。
         */
        fun pageChanged()
    }

    data class Model(
        val title: String,
        /** 失败文案；空串 = 状态行不占位（B 方案：状态行只报失败）。 */
        val status: String,
        val total: Int,
        val current: Int,
        val watchedUpTo: Int,
        /** 播放设置五行：名称 + 当前值（文案由 `PlayerText.settingsRows` 给）。 */
        val settings: List<PlayerSettingLine>,
    )

    private val tokens: Tokens = ui.tokens

    /** 抽屉的分页口径：一行 10 格，一页 5 行 = 50 集。 */
    private val pagination = EpisodePages.SIDEBAR

    var mode: Mode = Mode.EPISODES
        private set

    private var model: Model? = null
    private var page = 0
    private var lastCurrent = -1
    private var digits = ""

    private val titleView: TextView = ui.text("", tokens.type.body, tokens.colors.text).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setTypeface(null, Typeface.BOLD)
    }

    /** 状态行：danger 色、只报失败（"播放出错了，正在自动重试…"）。 */
    /** 抽屉的状态行（只报失败）。与播放页大字同色：正文色，不用红。 */
    private val statusView: TextView = ui.text("", tokens.type.caption, tokens.colors.text).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        visibility = View.GONE
    }

    /** 分集范围（`第 1—50 集 / 共 300 集`）：翻组键只剩两侧长条，这段文字负责说"现在在哪一段"。 */
    private val rangeView: TextView = ui.text("", tokens.type.caption, tokens.colors.muted).apply {
        maxLines = 1
        setPadding(0, 0, 0, ui.dp(tokens.space.xs))
    }

    /**
     * 分集格两侧的翻组长条（2026-10-24 口径：与详情页同一种控件）。
     * 抽屉里原来顶行的「上一组/下一组」小胶囊取消——长条和它翻的内容在同一行，更直接。
     *
     * **高度按整页满行固定**（与详情页同一条纪律）：末页只剩一两行格子时，长条不跟着缩，
     * 连翻两组时位置不变、遥控器不用重新找。
     */
    private val strips = SideStrips(
        ui,
        KEY_PREV,
        KEY_NEXT,
        blockHeightDp = pagination.rows * (tokens.metrics.episodeCellHeight + tokens.metrics.episodeCellGap),
    ) { delta -> goPage(delta) }
    private val gridHost = ui.column()
    private val jumpArea = ui.column()
    private val digitsView: TextView = ui.text("", tokens.type.body * tokens.metrics.jumpDigitsScale, tokens.colors.text).apply {
        gravity = Gravity.CENTER
        setTypeface(null, Typeface.BOLD)
        setPadding(0, ui.dp(tokens.space.xs), 0, ui.dp(tokens.space.xs))
    }
    private val jumpHintView: TextView = ui.text("", tokens.type.caption, tokens.colors.muted).apply {
        gravity = Gravity.CENTER
        setPadding(0, 0, 0, ui.dp(tokens.space.s))
    }

    private var cells: List<TvButton> = emptyList()
    private var cellIndexes: List<Int> = emptyList()
    private var builtPage = -1
    private var builtTotal = -1

    /** 集数格重建次数。重建会换掉一批 View 实例，页面据此必须重提一次焦点图。 */
    private var buildStamp = 0
    private val settingRows = mutableListOf<SettingRow>()
    private val keypad = mutableListOf<TvButton>()

    /** 抽屉顶行之下的内容区：选集态 = 左列 + 右列，跳集态 = 数字键盘。 */
    private val bodyHost: FrameLayout
    private val episodesBody: LinearLayout
    private val settingsColumn: LinearLayout = ui.column()

    val view: LinearLayout

    init {
        PlayerSetting.entries.forEach { setting ->
            // 名称与值分列（2026-10-24 验收）：整行一个标签时，用户分不出哪个是设置项、哪个是当前档
            val row = ui.settingRow("", "", style = SettingRowStyle.DRAWER) { callbacks.changeSetting(setting) }
            settingRows += row
            settingsColumn.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = ui.dp(tokens.space.xs)
            })
        }

        // 顶行：片名 + 「MENU 关闭」（mockup .b-drawer .toprow）。键帽画法走 uikit，
        // 与信息栏右侧的「OK」是同一份（描边版）
        val topRow = ui.row().apply { setPadding(0, 0, 0, ui.dp(tokens.space.m)) }
        topRow.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            rightMargin = ui.dp(tokens.space.m)
        })
        val closeHint = ui.text("", tokens.type.footnote, tokens.colors.muted)
        closeHint.text = "关闭"
        topRow.addView(ui.keyCap("MENU", outlined = true))
        topRow.addView(closeHint)

        episodesBody = ui.column().apply {
            addView(statusView)
            addView(rangeView)
            // 分集格夹在左右长条之间：行尾按右就撞到条上，按一下翻一组
            addView(strips.wrap(gridHost))
        }

        // 右列：设置五行垂直居中，与左列之间隔一条 1px 结构线（mockup .b-drawer .right）
        val rightColumn = FrameLayout(ui.context)
        rightColumn.addView(
            View(ui.context).apply { setBackgroundColor(tokens.colors.line) },
            FrameLayout.LayoutParams(ui.dp(tokens.metrics.dividerHeight), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START),
        )
        rightColumn.addView(
            settingsColumn,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL).apply {
                leftMargin = ui.dp(tokens.space.xl)
            },
        )

        // 选集列与右列**垂直居中**（2026-10-24 验收：顶行的分组按钮删掉后，选集区贴着抽屉顶、
        // 下面空一大块，看起来上紧下宽）。左右两列都以 body 的中线对齐。
        val bodyRow = ui.row().apply {
            addView(episodesBody, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = ui.dp(tokens.space.l)
            })
            addView(
                rightColumn,
                LinearLayout.LayoutParams(ui.dp(tokens.metrics.drawerSettingsWidth), ViewGroup.LayoutParams.MATCH_PARENT),
            )
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

        // 抽屉本体：286dp 高、内边距 12/18/14、圆角 16（spec"播放抽屉"）
        bodyHost = FrameLayout(ui.context)
        bodyHost.addView(bodyRow, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        bodyHost.addView(jumpArea, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL))

        view = ui.tile(radiusDp = tokens.shapes.drawer, paddingDp = 0).apply {
            visibility = View.GONE
            setPadding(
                ui.dp(tokens.metrics.drawerPaddingH),
                ui.dp(tokens.metrics.drawerPaddingTop),
                ui.dp(tokens.metrics.drawerPaddingH),
                ui.dp(tokens.metrics.drawerPaddingBottom),
            )
            val height = ui.dp(tokens.metrics.drawerHeight)
            addView(
                topRow,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
            // 顶行与内容之间一条 1px 结构线（mockup toprow 的 border-bottom）
            addView(
                View(ui.context).apply { setBackgroundColor(tokens.colors.line) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(tokens.metrics.dividerHeight)),
            )
            addView(
                bodyHost,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = ui.dp(tokens.space.m) },
            )
            minimumHeight = height
        }
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

    /**
     * 当前焦点对应的 key（没有就是 null）。
     *
     * 翻组后焦点要留在被按的那条长条上：重提焦点图时若一律回落到"当前集"，
     * 用户连按两次下一组得先把光标从格子里挪回长条——翻页变成三步。
     */
    fun focusedKey(): FocusKey? {
        if (mode == Mode.EPISODES) {
            cells.forEachIndexed { index, cell -> if (cell.isFocused) return episodeKey(cellIndexes[index]) }
            settingRows.forEachIndexed { index, row -> if (row.isFocused) return settingKey(index) }
            if (strips.prev.isFocused) return KEY_PREV
            if (strips.next.isFocused) return KEY_NEXT
        } else {
            keypad.forEachIndexed { index, key -> if (key.isFocused) return FocusKey("$PREFIX:key:$index") }
        }
        return null
    }

    fun setMode(next: Mode) {
        mode = next
        digits = ""
        model?.let { bind(it) }
    }

    /** 数字键直接进入跳集（B 方案：不再需要先按「跳转集数」按钮）。返回 false 表示已在该模式。 */
    fun enterJump(): Boolean {
        if (mode == Mode.JUMP) return false
        setMode(Mode.JUMP)
        return true
    }

    // ---------- 渲染 ----------

    fun bind(model: Model) {
        this.model = model
        titleView.text = model.title
        statusView.text = model.status
        statusView.visibility = if (model.status.isBlank()) View.GONE else View.VISIBLE
        if (model.total > 0 && lastCurrent != model.current) {
            // 换集（含片尾自动连播）之后，抽屉要翻到当前集所在的那一组
            page = pagination.pageOf(model.current)
            builtPage = -1
        }
        lastCurrent = model.current
        page = page.coerceIn(0, pagination.pages(model.total) - 1)

        episodesBody.visibility = if (mode == Mode.EPISODES) View.VISIBLE else View.GONE
        jumpArea.visibility = if (mode == Mode.JUMP) View.VISIBLE else View.GONE

        when (mode) {
            Mode.EPISODES -> {
                bindEpisodes(model)
                // 设置行的标签随每次绑定刷新：改完倍速不必等下一次 tick 才看到新值
                bindSettings(model)
            }
            Mode.JUMP -> bindJump()
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
        // 范围行与第一集按钮左对齐：分集格被左侧长条让开了一段，范围行也要让开同样的宽。
        // 单页时整对长条收起，分集格从行首开始，范围行不再缩进。
        val stripInset = if (pages > 1) ui.dp(tokens.metrics.sideStripWidth + tokens.space.s) else 0
        if (rangeView.paddingLeft != stripInset) {
            rangeView.setPadding(stripInset, 0, 0, ui.dp(tokens.space.xs))
        }
        rangeView.text = PlayerText.range(range, model.total)
        // 单页时整对长条收起，分集格占满（与详情页同一条规则）
        strips.show(visible = pages > 1, hasPrev = page > 0, hasNext = page < pages - 1)
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
                    variant = ButtonVariant.GRID_CELL,
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

    /** 设置行只改标签与值，**不重建**：重建会把光标拉回第一行。 */
    private fun bindSettings(model: Model) {
        model.settings.forEachIndexed { index, line ->
            settingRows.getOrNull(index)?.apply {
                label(line.label)
                value(line.value)
            }
        }
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
        callbacks.pageChanged()
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
        val slots: List<FocusKey?>
        var columns = JUMP_COLUMNS

        if (mode == Mode.EPISODES) {
            columns = pagination.columns + 1
            // 长条不仅要在焦点段里，还要有 View 才能被按键选中（2026-10-24 验收的 bug 根因）
            strips.bindInto(views)
            val episodeKeys = cellIndexes.mapIndexed { position, cellIndex ->
                episodeKey(cellIndex).also { views[it] = cells[position] }
            }
            val settingKeys = settingRows.mapIndexed { index, row ->
                settingKey(index).also { views[it] = row }
            }
            // 每行 10 个集数格 + 行末设置项拼成 11 列；末页不足 50 集时补空槽，
            // 设置项才留在第 11 列（视觉上右侧那一条），程序性落焦与方向键都不会左移
            slots = DrawerFocus.slots(episodeKeys, settingKeys, pagination.columns)
        } else {
            slots = keypad.mapIndexed { index, key ->
                FocusKey("$PREFIX:key:$index").also { views[it] = key }
            }
        }

        val flanked = mode == Mode.EPISODES
        val plan = FocusSections.plan(
            listOf(FocusSection(slots, columns, flanked = flanked)),
            leftFlank = if (flanked) strips.leftSection() else null,
            rightFlank = if (flanked) strips.rightSection() else null,
        ) ?: return null

        val fallback = when (mode) {
            Mode.EPISODES -> episodeKey(model.current).takeIf { views.containsKey(it) } ?: slots.firstNotNullOfOrNull { it }
            Mode.JUMP -> slots.firstNotNullOfOrNull { it }
        } ?: return null
        return FocusBinding(plan, views, preferred ?: fallback)
    }

    companion object {
        private const val PREFIX = "sb"
        private const val MAX_DIGITS = 6
        private const val JUMP_COLUMNS = 3

        val KEY_PREV = FocusKey("$PREFIX:prev")
        val KEY_NEXT = FocusKey("$PREFIX:next")

        fun episodeKey(index: Int): FocusKey = FocusKey("$PREFIX:ep:$index")

        fun settingKey(index: Int): FocusKey = FocusKey("$PREFIX:set:$index")
    }
}
