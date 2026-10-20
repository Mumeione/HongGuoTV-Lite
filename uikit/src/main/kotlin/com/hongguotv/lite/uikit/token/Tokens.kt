package com.hongguotv.lite.uikit.token

/**
 * 设计令牌：整个应用"长什么样"的唯一事实来源。
 *
 * 三条约定：
 * - **纯数据**：颜色是 ARGB 的 [Int]，尺寸是 dp 的 [Int]，不引用 `R`、不引用 `Context`。
 *   这样令牌本身能在 JVM 单测里直接比对，控件之外没人需要 `Resources`。
 * - **改视觉只改这里**：调用点不允许再出现十六进制颜色与 `dp(3)` 这类字面量。
 * - **分档不改视觉**：低内存档只动动效与单页卡片上限，颜色与字号两档完全一致——
 *   否则同一张截图在两台设备上对不上，问题就说不清了。
 *
 * 取值来源是旧仓库 `TvStyle` 与各页面的事实值（见 docs/specs/2026-10-20-uikit-design-system.md）。
 */

/**
 * 全应用只有一种强调色：白色。
 * 「选中」= 白底深字（[focus] 填充 + [onFocus] 字色），「聚焦」= 白描边。
 * 不要引入第二强调色——旧版曾经有过，结果是同一个页面上两种"重点"互相打架。
 */
data class Colors(
    val background: Int = 0xFF0B0F15.toInt(),
    val surface: Int = 0xFF171E28.toInt(),
    val raised: Int = 0xFF202A36.toInt(),
    val outline: Int = 0xFF2B3746.toInt(),
    val text: Int = 0xFFF3F4F6.toInt(),
    val muted: Int = 0xFF93A0B2.toInt(),
    /** 唯一强调色：描边、白底、需要用户注意的角标。 */
    val focus: Int = 0xFFFFFFFF.toInt(),
    /** 白底上的字色。与 [background] 同值但语义不同，不要互相代替。 */
    val onFocus: Int = 0xFF0B0F15.toInt(),
    /** 已看完/已看过一档的底色，比 [surface] 略亮一层。 */
    val watched: Int = 0xFF283442.toInt(),
)

/**
 * 字号刻度。旧实现从 11 到 27 有十来个互不相关的取值，这里按"用途"定档，
 * 新增文字先找用途，找不到再讨论要不要加档。
 */
data class Type(
    /** 卡角标、次要说明。 */
    val caption: Float = 12f,
    /** 状态行、小字按钮（正序/倒序这类）。 */
    val label: Float = 13f,
    /** 卡片标题、胶囊筛选。 */
    val compact: Float = 14f,
    /** 常规按钮、面板正文。 */
    val body: Float = 15f,
    /** 输入框、分组标题。 */
    val subtitle: Float = 16f,
    /** 顶栏导航、卡片组标题。 */
    val title: Float = 18f,
    /** 带返回的页头。 */
    val heading: Float = 22f,
    /** 页面主标题。 */
    val display: Float = 25f,
    /** 预览区标题（目录页聚焦剧集那块）。 */
    val preview: Float = 27f,
    /** 顶栏品牌字。只此一处用，独立成档免得调用点写 `heading + 1`。 */
    val brand: Float = 23f,
    /** 多行正文的行距倍数。 */
    val lineSpacing: Float = 1.12f,
)

/**
 * 间距刻度。旧实现里 `dp(3)` 与 `dp(4)`、`dp(5)` 与 `dp(6)` 混用，没有刻度；
 * 这里收成一套固定档位（3 / 4 / 6 / 10 / 14 / 18 / 24），新代码只从这里取。
 */
data class Space(
    val hairline: Int = 1,
    val xxs: Int = 3,
    val xs: Int = 4,
    val s: Int = 6,
    val m: Int = 10,
    val l: Int = 14,
    val xl: Int = 18,
    val xxl: Int = 24,
)

/** 圆角与描边。描边宽度固定，靠颜色区分"有焦点"与"没焦点"。 */
data class Shapes(
    val card: Int = 10,
    val panel: Int = 16,
    val chip: Int = 19,
    val small: Int = 8,
    val stroke: Int = 2,
)

/**
 * 动效。低内存档整体关闭——512MB 的盒子上动画带来的收益不如掉帧的代价。
 * 缩放只影响绘制，不参与任何几何计算：焦点图与滚动补偿一律用未缩放的边界。
 */
data class Motion(
    val enabled: Boolean = true,
    val durationMs: Long = 110,
    val focusScale: Float = 1.03f,
)

/** 布局尺寸。dp 数值，由控件换算成像素。 */
data class Metrics(
    /** 页面左右留白：屏幕宽度的比例（旧实现 5%）。上下留白同比例。 */
    val pageInsetRatio: Float = 0.05f,
    val topBarHeight: Int = 50,
    val navHeight: Int = 46,
    val navGap: Int = 12,
    val dividerHeight: Int = 1,
    val dividerGap: Int = 6,
    /** 内页页头（返回 + 标题）的最小高度。 */
    val headerMinHeight: Int = 44,
    val controlMinHeight: Int = 42,
    val chipMinHeight: Int = 36,
    val linkMinHeight: Int = 32,
    /** 输入框高度（搜索行）。 */
    val inputHeight: Int = 44,
    /** 输入框最大宽度，以及不宽于屏宽的比例：太长显得空，两边留白反而更聚焦。 */
    val inputMaxWidth: Int = 520,
    val inputWidthRatio: Float = 0.52f,
    val cardPadding: Int = 4,
    /** 卡片标题与角标之间的间距。 */
    val cardTitlePaddingTop: Int = 7,
    val cardGap: Int = 14,
    /** 卡片内封面左右各让出的宽度（旧实现 `width - dp(8)`）。 */
    val cardArtworkInset: Int = 8,
    val badgeHeight: Int = 19,
    /** 封面宽高比（宽/高）。 */
    val posterAspect: Float = 0.7f,
    /** 目标卡宽：列数由可用宽度除以它得出，1080p/6 列时约等于现值。 */
    val gridTargetCardWidth: Int = 150,
    val gridMinColumns: Int = 3,
    /** 上限 6：保留现有"六列海报"的视觉，列数是算出来的但不会越算越多。 */
    val gridMaxColumns: Int = 6,
    /** 滚动补偿时项与视口边缘之间保留的余量。 */
    val scrollMargin: Int = 8,
    /** 常规按钮内边距。 */
    val controlPaddingH: Int = 14,
    val controlPaddingV: Int = 10,
    /** 胶囊内边距（比按钮宽松一档，胶囊形状需要更多留白）。 */
    val chipPaddingH: Int = 16,
    val chipPaddingV: Int = 7,
    /** 顶栏导航项左右内边距。 */
    val navPaddingH: Int = 20,
    /** 图标尺寸与图文间距：矢量图默认 19dp，配小字号会偏大。 */
    val iconNav: Int = 19,
    val iconChip: Int = 15,
    val iconGap: Int = 5,
    val brandIcon: Int = 30,
    /** 不可点击的卡片（未上线季）整体透明度：暗一档，一看就知道按不进去。 */
    val dimAlpha: Float = 0.55f,
    /** 详情页大封面：宽 / 高 / 与右侧信息的间距。 */
    val heroCoverWidth: Int = 98,
    val heroCoverHeight: Int = 140,
    val heroCoverGap: Int = 22,
    /** 分集格：格高、左右内边距（20 列时三位数也放得下）、格间距。 */
    val episodeCellHeight: Int = 40,
    val episodeCellPaddingH: Int = 2,
    val episodeCellGap: Int = 5,
    /**
     * 播放侧栏：宽度按屏宽比例算（右侧留白与页边距同一口径，不写像素）。
     * 85% 是"20 列格子仍然有 40dp 宽"的下限——再看窄就得减列，两侧算法就分家了。
     */
    val sidebarWidthRatio: Float = 0.85f,
    val sidebarPaddingH: Int = 18,
    val sidebarPaddingV: Int = 14,
    /** 侧栏内块与块之间的间距。 */
    val sidebarGap: Int = 8,
    /** 播放提示卡最大宽度占屏宽比例，以及它离屏幕底部的距离。 */
    val noticeWidthRatio: Float = 0.52f,
    val noticeBottomMargin: Int = 56,
    /** 跳集页那个大号数字的字号倍数（相对 body）。 */
    val jumpDigitsScale: Float = 2.2f,
)

data class Tokens(
    val tier: UiTier = UiTier.NORMAL,
    val colors: Colors = Colors(),
    val type: Type = Type(),
    val space: Space = Space(),
    val shapes: Shapes = Shapes(),
    val motion: Motion = Motion(),
    val metrics: Metrics = Metrics(),
    /**
     * 单页卡片渲染上限。不是数据上限：数据可以有 90 条，一次只建这么多视图。
     * 低内存档下调，是"不卡"与"一屏能看多少"之间的取舍。
     */
    val pageCardLimit: Int = 30,
) {
    companion object {
        /** 低内存档：只关动效、降卡片上限，颜色与字号不动。 */
        fun of(tier: UiTier): Tokens = when (tier) {
            UiTier.NORMAL -> Tokens(tier = tier)
            UiTier.LOW_RAM -> Tokens(
                tier = tier,
                motion = Motion(enabled = false),
                pageCardLimit = 18,
            )
        }

        /** 默认档（不读设备标记时的取值，测试与预览用）。 */
        val Default: Tokens = of(UiTier.NORMAL)
    }
}
