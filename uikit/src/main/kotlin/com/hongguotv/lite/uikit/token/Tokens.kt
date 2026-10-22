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
 * 当前取值来源是 B 方案（`docs/specs/2026-10-21-ui-b-redesign.md` +
 * `docs/UI/mockups/2026-10-21-B方案v4.html`，后者是唯一视觉基准）。
 */

/**
 * 全应用只有一种强调色：白色。
 *
 * B 方案的语义（**焦点唯一 + 聚光**）：
 * - **焦点**（光标所在）= 白底深字（[focus] 填充 + [onFocus] 字色）——全页只有这一处白色填充；
 * - **选中**（当前 tab / 当前筛选 / 当前集 / 当前季）= 白描边 + 白字，**不填充**；
 * - 封面卡的焦点是"放大 + 白框 + 白底信息条"（卡面是图片，白底只露在信息条上），见 `PosterCard`；
 * - **聚光**：聚光网格里非焦点卡压到 [Metrics.focusDimAlpha]，屏幕上"只有那一张卡是亮的"。
 *
 * 不要引入第二强调色——旧版曾经有过，结果是同一个页面上两种"重点"互相打架。
 */
data class Colors(
    val background: Int = 0xFF0B0F15.toInt(),
    /** 侧栏、信息栏、banner 的底：比 [background] 亮半档、比 [surface] 暗一档的"结构色"。 */
    val chrome: Int = 0xFF0F151D.toInt(),
    val surface: Int = 0xFF171E28.toInt(),
    /** 面板里的行（设置行、抽屉设置行）：比 [surface] 亮一档，比 [raised] 暗一档。 */
    val row: Int = 0xFF1C2531.toInt(),
    /** 键帽底、主操作的"亮一档"底。 */
    val raised: Int = 0xFF202A36.toInt(),
    /** 已看完/已看过一档的底色，比 [surface] 略亮一层。 */
    val watched: Int = 0xFF242F3D.toInt(),
    /** 1px 结构线：侧栏右缘、信息栏上缘、抽屉顶行下缘。 */
    val line: Int = 0xFF1F2836.toInt(),
    /** 旧版分隔线。B 方案的页面不再画分隔线，仅为既有调用保留。 */
    val outline: Int = 0xFF2B3746.toInt(),
    val text: Int = 0xFFF3F4F6.toInt(),
    val muted: Int = 0xFF8B97A8.toInt(),
    /** 规格表 value 这类"比 [muted] 亮、比 [text] 暗"的次级正文。 */
    val soft: Int = 0xFFB9C3D0.toInt(),
    /** 占位提示、规格 label、banner 副标："弱到不需要读完"的一档。 */
    val dim: Int = 0xFF5E6A7A.toInt(),
    /** 唯一强调色：描边、白底、需要用户注意的角标。 */
    val focus: Int = 0xFFFFFFFF.toInt(),
    /** 白底上的字色。与 [background] 同值但语义不同，不要互相代替。 */
    val onFocus: Int = 0xFF0B0F15.toInt(),
    /** 播放失败文案专用。B 方案纪律：别处不许用彩色。 */
    val danger: Int = 0xFFE8A0A0.toInt(),
    /** 封面「当前」角标的底：深底压住亮色封面，保证白字与白框读得出。 */
    val cornerScrim: Int = 0xE60B0F15.toInt(),
    /** 播放抽屉打开时的整屏压暗层。 */
    val scrim: Int = 0x94040609.toInt(),
)

/**
 * 字号刻度。按"用途"定档，新增文字先找用途，找不到再讨论要不要加档。
 *
 * B 方案的层级（低 → 高）：角标 → 侧栏项 → 季卡状态 → 说明 → 信息栏段 → 卡片状态条
 * → 胶囊 → 片名副行 → 输入框 → 面板标题 → 分组标题 → 页头 → 详情片名 → 页面主标题。
 * 一眼能看出"这是哪一页、这是哪一部剧"。
 */
data class Type(
    /** 封面左上角的小角标（「当前」）。 */
    val corner: Float = 10.5f,
    /** 侧栏导航项文字、按键帽（信息栏/抽屉）。 */
    val railLabel: Float = 11f,
    /** 季卡状态行（「上次第20集」）。 */
    val micro: Float = 11.5f,
    /** 分集格数字、页面副说明、抽屉的键提示。 */
    val footnote: Float = 12.5f,
    /** 信息栏说明段、规格表 label、历史卡状态、翻组范围、操作提示。 */
    val caption: Float = 13f,
    /** 卡片状态条、小号胶囊、候选词、简介、规格 value、抽屉设置行。 */
    val cardStatus: Float = 13.5f,
    /** 规格表 value、信息栏片名、设置行文字。 */
    val specValue: Float = 14.5f,
    /** 常规胶囊、片库标签。 */
    val compact: Float = 15f,
    /** 片名副行（「共 10 季」）。 */
    val seasonLine: Float = 15.5f,
    /** 输入框、抽屉片名。 */
    val body: Float = 16f,
    /** 设置面板标题（「播放偏好」）。 */
    val group: Float = 17f,
    /** 分组标题（继续看 / 所有季）。 */
    val subtitle: Float = 18f,
    /** 带返回的页头（剧集详情 / 收藏详情）。 */
    val heading: Float = 20f,
    /** 详情类页面的片名（主视觉那块）、顶层页大标题（历史/收藏/设置）。一页只有一处。 */
    val display: Float = 26f,
    /** 页面主标题（搜索页）。全应用最大的字。 */
    val pageTitle: Float = 28f,
    /** 多行正文的行距倍数（B 方案简介 1.6）。 */
    val lineSpacing: Float = 1.6f,
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
    /** 海报卡、侧栏导航项。 */
    val card: Int = 12,
    /** 面板（设置两栏）、播放抽屉。 */
    val panel: Int = 14,
    /** 胶囊：99 = 完全胶囊。 */
    val pill: Int = 99,
    /** 行容器（设置行、输入框）。 */
    val row: Int = 10,
    /** 小块（抽屉设置行、链接）。 */
    val small: Int = 8,
    /** 封面「当前」角标。 */
    val corner: Int = 4,
    /** 键帽。 */
    val keyCap: Int = 5,
    /** 选中描边宽度（2dp 白框）。 */
    val stroke: Int = 2,
    /** 聚焦描边宽度（海报卡 3dp 白框）。 */
    val focusStroke: Int = 3,
)

/**
 * 动效。低内存档整体关闭——512MB 的盒子上动画带来的收益不如掉帧的代价。
 * 缩放只影响绘制，不参与任何几何计算：焦点图与滚动补偿一律用未缩放的边界。
 *
 * B 方案：聚光卡放大 1.06（规格 Out of Scope："放大 1.06 用现有 Motion"）。
 */
data class Motion(
    val enabled: Boolean = true,
    val durationMs: Long = 110,
    val focusScale: Float = 1.06f,
)

/** 布局尺寸。dp 数值，由控件换算成像素。 */
data class Metrics(
    // ---------- 页面骨架 ----------
    /** 页面左右留白（B 方案：固定 40，不再按比例）。 */
    val pagePaddingH: Int = 40,
    /** 页面上留白。 */
    val pagePaddingTop: Int = 24,
    /** 标题行（带返回的页头）高度。 */
    val pageTitleRowHeight: Int = 34,

    // ---------- 左侧栏（RailNav） ----------
    val railWidth: Int = 80,
    /** 品牌图标 52：与 56×54 的导航项视觉等大。 */
    val railBrandIcon: Int = 52,
    val railIcon: Int = 17,
    val railItemWidth: Int = 56,
    val railItemHeight: Int = 54,
    val railItemGap: Int = 8,
    val railPaddingTop: Int = 22,
    val railPaddingBottom: Int = 18,

    // ---------- 胶囊 ----------
    val pillHeight: Int = 40,
    val pillSmallHeight: Int = 32,
    val pillPaddingH: Int = 20,
    val pillSmallPaddingH: Int = 14,

    // ---------- 卡片（目录，宽 149） ----------
    val catalogCardWidth: Int = 149,
    val catalogStripHeight: Int = 34,
    val cardGap: Int = 14,
    /** 卡片信息区的左右内边距：封面贴边，文字不能也贴边。 */
    val cardPadding: Int = 9,
    /** 聚光静默档：同屏其余卡的不透明度。真机偏暗只调这一个数，不改白框。 */
    val focusDimAlpha: Float = 0.62f,

    // ---------- 卡片（历史，宽 120，两行 6 列） ----------
    val historyCardWidth: Int = 120,
    val historyCoverHeight: Int = 152,
    val historyStripHeight: Int = 26,
    val historyColumnGap: Int = 12,
    val historyRowGap: Int = 20,

    // ---------- 季卡（宽 88） ----------
    val seasonCardWidth: Int = 88,
    val seasonCoverHeight: Int = 126,
    val seasonTitleHeight: Int = 24,
    val seasonStatusHeight: Int = 20,

    // ---------- 详情 hero（两行网格） ----------
    val heroCoverWidth: Int = 150,
    val heroColumnGap: Int = 22,
    /** 动作列宽：三个胶囊竖排的固定宽。 */
    val heroActionsWidth: Int = 172,
    /** 规格表 label 列宽。 */
    val specLabelWidth: Int = 56,
    val specRowGap: Int = 9,
    /** 简介两行封顶，超出省略号（不做滚动框）。 */
    val introMaxLines: Int = 2,

    // ---------- 分集格（20 列胶囊） ----------
    val episodeColumns: Int = 20,
    val episodeCellHeight: Int = 26,
    val episodeCellGap: Int = 5,
    /** 详情页分集格是胶囊。 */
    val episodeCellRadius: Int = 13,
    /** 播放抽屉里的分集格是圆角矩形（10 列）。 */
    val drawerCellRadius: Int = 6,
    /** 分集格左右内边距（三位数也放得下）。 */
    val episodeCellPaddingH: Int = 2,

    // ---------- 信息栏（InfoBand） ----------
    val infoBandHeight: Int = 46,
    /** 1px 结构线（侧栏右缘、信息带上缘）。 */
    val dividerHeight: Int = 1,
    val keyCapSize: Int = 22,
    val keyCapRadius: Int = 5,

    // ---------- 播放抽屉（底部） ----------
    val drawerHeight: Int = 286,
    val drawerPaddingH: Int = 18,
    val drawerPaddingTop: Int = 12,
    val drawerPaddingBottom: Int = 14,
    /** 抽屉右列（五项设置）的固定宽。 */
    val drawerSettingsWidth: Int = 236,
    val drawerRowHeight: Int = 34,

    // ---------- 设置面板 ----------
    val setRowHeight: Int = 46,
    val setRowGap: Int = 10,
    val setPanelPaddingH: Int = 18,
    val setPanelPaddingV: Int = 20,

    // ---------- 搜索 ----------
    val inputHeight: Int = 44,
    val inputMaxWidth: Int = 480,

    // ---------- 网格几何 ----------
    /**
     * 封面宽高比（宽/高）。上游正版封面是 640×914，0.7 时不用留黑边。
     * 目录卡 149/213、历史卡 120/152（≈0.79，B 方案把历史卡压扁了一档）、季卡 88/126 都由它派生或单独定高。
     */
    val posterAspect: Float = 0.7f,
    /**
     * 视口不足时封面的保底高度：再夹下去就成了一条缝，看不清是什么剧。
     * 宁可让卡片略微超出视口（滚动一点点），也不给一张看不清的图。
     */
    val artworkMinHeight: Int = 96,
    /**
     * 目标卡宽：列数由可用宽度除以它得出。
     *
     * 149dp 在 B 方案的内容区（960 − 80 侧栏 − 80 页边距 = 800dp）上是 **4 列、每张约 189dp**。
     */
    val gridTargetCardWidth: Int = 149,
    val gridMinColumns: Int = 3,
    /** 上限 6：列数是算出来的，但不会越算越多。 */
    val gridMaxColumns: Int = 6,
    /** 滚动补偿时项与视口边缘之间保留的余量。 */
    val scrollMargin: Int = 8,
    /** 不可点击的卡片（未上线季）整体透明度：暗一档，一看就知道按不进去。 */
    val dimAlpha: Float = 0.55f,

    // ---------- 过渡期字段（对应组件退场时一起删，新代码不要引用） ----------
    val controlPaddingH: Int = 14,
    val linkMinHeight: Int = 32,
    val iconChip: Int = 15,
    val iconGap: Int = 5,
    val heroCoverHeight: Int = 124,
    val heroCoverGap: Int = 20,
    val heroIntroGap: Int = 28,
    val introMaxHeight: Int = 92,
    val introExpandedHeight: Int = 300,
    val sidebarWidthRatio: Float = 0.62f,
    val sidebarPaddingH: Int = 18,
    val sidebarPaddingV: Int = 14,
    val sidebarGap: Int = 8,
    val noticeWidthRatio: Float = 0.52f,
    val noticeBottomMargin: Int = 56,
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
