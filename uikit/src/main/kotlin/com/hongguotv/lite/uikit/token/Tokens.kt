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
 *
 * S9 定的语义（**焦点唯一**）：
 * - **焦点**（光标所在）= 白底深字（[focus] 填充 + [onFocus] 字色）——全页只有这一处白色填充；
 * - **选中**（当前 tab / 当前筛选 / 当前集 / 当前季）= 白描边 + 白字，**不填充**；
 * - 封面卡的焦点是白描边（卡面是图片，白底会把图盖掉），见 `PosterCard`。
 *
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
 *
 * 2026-10-21 第三轮：整条刻度重排过一次。上一版把**聚焦项片名**（`preview` 28）放在
 * 页面标题（`heading` 26）之上，于是在「观看历史」「我的收藏」这类页面上，
 * 卡片片名比页面标题还大一号——维护者原话"片名太大，标题太小，一点都不和谐"。
 * 现在的次序是：页头（24）> 片名（21）> 分组标题（17）> 正文（16），
 * 一眼能看出"这是哪一页、这是哪一部剧"。
 */
data class Type(
    /** 卡角标、次要说明。 */
    val caption: Float = 13f,
    /** 状态行、小字按钮（正序/倒序这类）。 */
    val label: Float = 14f,
    /** 卡片标题、胶囊筛选。 */
    val compact: Float = 15f,
    /** 常规按钮、面板正文。 */
    val body: Float = 16f,
    /** 输入框、分组标题（继续看 / 所有季）。 */
    val subtitle: Float = 17f,
    /** 顶栏导航。比正文大一档就够：导航靠图标与常驻加粗区分，不靠字号。 */
    val title: Float = 18f,
    /**
     * 预览区标题（目录页聚焦的那部剧）。
     *
     * 它属于**当前光标那一项**，不是页面标题：比 `heading` 小一档，
     * 否则同一屏上会出现两个"主标题"（见本类开头的重排说明）。
     */
    val preview: Float = 21f,
    /** 带返回的页头（我的收藏 / 观看历史 / 剧集详情）。 */
    val heading: Float = 24f,
    /** 详情类页面的片名（主视觉那块）。一页只有一处，比页头大一档。 */
    val display: Float = 26f,
    /** 顶栏品牌字。独立成档免得调用点写 `heading - 2`。 */
    val brand: Float = 22f,
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
    /** 顶栏高度。顶栏下**不画**分隔线（维护者口径）：靠留白与字号分层次，不靠一条线切。 */
    val topBarHeight: Int = 48,
    val navHeight: Int = 40,
    val navGap: Int = 12,
    val dividerHeight: Int = 1,
    val dividerGap: Int = 6,
    /** 内页页头（返回 + 标题）的最小高度。 */
    val headerMinHeight: Int = 40,
    val controlMinHeight: Int = 42,
    val chipMinHeight: Int = 36,
    val linkMinHeight: Int = 32,
    /** 输入框高度（搜索行）。 */
    val inputHeight: Int = 44,
    /** 输入框最大宽度，以及不宽于屏宽的比例：太长显得空，两边留白反而更聚焦。 */
    val inputMaxWidth: Int = 520,
    val inputWidthRatio: Float = 0.52f,
    /** 卡片信息区的左右内边距：封面贴边，文字不能也贴边。 */
    val cardPadding: Int = 8,
    /** 封面下沿与标题之间的间距。 */
    val cardTitlePaddingTop: Int = 6,
    /** 卡片信息区的下内边距。 */
    val cardPaddingBottom: Int = 8,
    /**
     * 预览区（聚焦项的标题/状态/分类三行）的上下留白。
     * 它固定占在网格上方，每多 1dp 就是从首屏卡片那里拿走 1dp——S9 收紧了这两档。
     */
    val previewRailPaddingTop: Int = 4,
    val previewRailPaddingBottom: Int = 6,
    val cardGap: Int = 14,
    val badgeHeight: Int = 19,
    /** 封面宽高比（宽/高）。上游正版封面是 640×914，0.7 时不用留黑边。 */
    val posterAspect: Float = 0.7f,
    /**
     * 视口不足时封面的保底高度：再夹下去就成了一条缝，看不清是什么剧。
     * 宁可让卡片略微超出视口（滚动一点点），也不给一张看不清的图。
     */
    val artworkMinHeight: Int = 96,
    /**
     * 目标卡宽：列数由可用宽度除以它得出。
     *
     * 150dp 在 1080p（960dp 宽）上是 **5 列、每张约 161dp**。
     * 试过 6 列（134dp）：卡上那行「第九季第20集 · 05:00」就差 15dp 放不下，
     * 角标被截成「…05....」——卡片现在只有这一行字，截断等于没写。
     */
    val gridTargetCardWidth: Int = 150,
    val gridMinColumns: Int = 3,
    /** 上限 6：列数是算出来的，但不会越算越多。 */
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
    val heroCoverWidth: Int = 88,
    val heroCoverHeight: Int = 124,
    val heroCoverGap: Int = 20,
    /** 主视觉左栏（信息）与右栏（简介）之间的间距。 */
    val heroIntroGap: Int = 28,
    /** 主视觉简介滚动框的高度：收起约四行正文，展开档给到能一屏读完大多数简介。 */
    val introMaxHeight: Int = 92,
    val introExpandedHeight: Int = 300,
    /**
     * 分集格：格高、左右内边距（20 列时三位数也放得下）、格间距。
     *
     * 高度从 40 收到 34 是为了让「20 列 × 5 行 = 100 集」**连主视觉一屏放下**：
     * 一屏放不下时，光标走到最后一行会把整页顶上去（维护者实测"焦点移动到底部页面会上移"）。
     */
    val episodeCellHeight: Int = 34,
    val episodeCellPaddingH: Int = 2,
    /** 格间距：白底焦点格与邻居之间要留出可见的分隔，太小两块几乎贴在一起。 */
    val episodeCellGap: Int = 6,
    /**
     * 播放侧栏：宽度按屏宽比例算（右侧留白与页边距同一口径，不写像素）。
     * 侧栏是覆盖层，画面必须还能看：62% 时 10 列格子仍有 55dp 宽，
     * 85% 那一版几乎把画面整个盖住（维护者口径"太大了，没有以前简洁"）。
     */
    val sidebarWidthRatio: Float = 0.62f,
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
