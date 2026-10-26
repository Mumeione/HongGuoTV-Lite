# 架构

## 一句话

把变化挡在接缝后面：上游协议会变、存储介质会变、播放实现会变、遥控器布局会变。
每个「会变」的东西都有一个专门的接缝，接缝之外的人不需要知道它变了。

## 模块与依赖

```
app               壳：Activity、导航、依赖组装
├── feature-catalog   搜索 / 收藏 / 历史 / 设置
├── feature-detail    详情 / 收藏系列
├── feature-player    播放页 / 侧栏 / 播放会话
├── uikit             设计令牌 / 控件 / 焦点图 / 页面骨架
└── data              端口实现：上游 HTTP / 本地存储 / 取流 / 封面 / 诊断
    └── core          纯 JVM：protocol / model / port / usecase
```

| 模块 | 类型 | 不得依赖 |
| --- | --- | --- |
| `core` | `kotlin("jvm")`，无 Android SDK | 任何本工程模块 |
| `data` | android library | `uikit`、`feature-*` |
| `uikit` | android library | `data`、`feature-*` |
| `feature-*` | android library | 互相之间、`data` |
| `app` | android application | —（顶层） |

依赖方向由根 `build.gradle.kts` 在**配置期**校验，反向依赖会让任何 Gradle 任务直接失败。
`-Plite.skipRules=true` 只在排查 Gradle 自身问题时用。

三条由此获得的性质：

1. **`core` 可脱机单测。** 没有 Android SDK，`import android.*` 编译不过，业务逻辑必须纯。
2. **页面互不感知。** 目录页要跳详情，调 `Navigator.toDetail(id)`，不知道详情页在哪个模块。
3. **换实现只改一处。** feature 只依赖 `core.port` 的接口，实现由 `app` 注入；
   换存储介质不需要动任何页面。

## 端口

六个业务端口 + 三个支撑端口。业务端口回答「要做什么」，支撑端口回答「怎么做才不散」：

| 端口 | 接口 | 背后藏着 |
| --- | --- | --- |
| `ContentPort` | `search / suggest / detail / seasons / stream` | 端点表、签名、HTTP、字段解析、季线排序、锚点补全、介质过滤、清晰度选择 |
| `ProgressPort` | 进度 / 历史 / 已看 / 写入 | prefs 键、编解码、写队列、节流 |
| `FavoritesPort` | 收藏 / 季键 / 季线缓存 / 确认更新 / 当前季 | 系列语义、LRU、检查结果 |
| `SettingsPort` | 读 / 原子更新 | 七项偏好的读写路径 |
| `MediaPort` | `open(vid, maxHeight) → MediaStream` | Range 分段、CENC 索引、逐样本解密 |
| `Navigator` | `toCatalog / toDetail / toSeries / toPlayer / back` | 页面栈与返回语义 |
| `ArtworkPort` | `load / prefetch / cancelPage / trim` | 优先级队列、LRU、磁盘缓存、低内存分档 |
| `DiagnosticsPort` | `stage / event` | 五段探针与日志节点 |
| `DecoderCapabilities` | `supports(codec)` | 平台 `MediaCodecList` 查询；取流选档先按它过滤，避开本机解不了的编码 |

`ContentPort` 只有五个方法，这是刻意的：把「上游长什么样」全部挡在背后，
调用方只谈业务（搜索、详情、季线、取流）。协议细节见 [PROTOCOL.md](PROTOCOL.md)。

`ArtworkPort`、`DiagnosticsPort` 与 `DecoderCapabilities` 是三个支撑端口：它们不属于业务能力，
但同样需要接缝，否则封面策略、日志字段与解码能力查询会重新散回每个页面。

## 状态归属

每个可变状态只有一个所有者，其他模块只能读或发事件：

| 状态 | 所有者 | 形态 |
| --- | --- | --- |
| 页面栈与来源 | `app` | 密封类 `Route` + `RouteStack`（规则是纯逻辑，可脱机单测；渲染在 `LiteActivity`） |
| 目录页 | `feature-catalog` | feature 字段持有（`CatalogFeature.currentTab`）；**当前 tab 也归它**，宿主不复制 |
| 播放 | `feature-player` | 密封类 `PlaybackState`（Idle / Preparing / Playing(pausedBy) / Ended / Failed） |
| 在途请求 | 每个 feature 自己的页面作用域 | 取消域局部化——打字不会取消搜索 |

两条硬规则：

- **路由不带 tab。** `Route.Catalog` 是标记；「用户在目录的哪一页」只有 `CatalogFeature` 知道。
  两边各存一份，切完 tab 再按返回就会拿到过期值。
- **宿主持有唯一的主线程作用域**（`AppGraph` 建、`onDestroy` 取消）。feature 收到的 `scope`
  都是它；写进度、写设置这类「必须活过页面」的动作挂它上面。

## 焦点状态归属

- **光标位置归 feature，不归页面**：目录页按 tab、详情页按页面标识存在 feature 层
  （`CatalogFeature.lastFocus` / `DetailFeature.lastFocus`），页面销毁重建后按它落点；
  页面自己那份 `FocusSession` 记忆只在本页存活期间有效。
- **「进页落在哪」是页面声明的阶梯，不是焦点图的第一个 key。** 回退链固定为
  「本次显式目标 → 本页记忆 → 页面阶梯 → 焦点图首项」，顶栏只能是最后兜底。
- 焦点机制、选中语义与落点规则见 [UI/UI-RULES.md](UI/UI-RULES.md)。

## 失败分类

```kotlin
UpstreamError: Network / HttpStatus(code) / EmptyBody / Shape(path) / RiskControl(code) / Decrypt(step) / Device(step)
LocalError:    Storage / Corrupt(partition)
```

- **运行期失败**一律走这两类，UI 只按类型映射文案，不匹配中文字符串。
- **页面加载兜底不崩进程**（V1.0.0 审计 P1-3 起）：页面作用域里的加载调用一律走
  `core.usecase.loadCatching`——取消原样抛出，其余 `Exception`（含脏数据从仓库 `require`
  抛出的 `IllegalArgumentException`）归入失败态或按语义静默，不让它冒出 `launch`。
- **静态配置错误**（端点表坏了、缺参数）直接抛参数异常，在启动时就炸——包成 `UpstreamError`
  会让人误以为上游变了。
- **用 `runCatching` 包 suspend 调用会吞掉 `CancellationException`**（取消不是失败）。
  `data` 层的降级一律走 `runCatchingIo`（取消原样抛出，目前是 `ContentRepository` 内的私有工具）；
  第二个地方需要它时提到共用位置，不要各写一份。

## 本地数据的不变量

- **prefs 文件名 `native-library-v1` 不许改。** 升级安装读旧数据的唯一桥梁，
  改了等于让老用户的收藏与进度归零。
- **三个端口读的是同一份事实**（进度 / 收藏 / 设置分区），不许为了「干净」另开一份存储。
- 旧版遗留分区 `later` / `hidden` / `favorites` / `searches` **故意不读、不写、不删**；
  唯一例外是旧 `favorites`：迁移时只读一次（`PrefsLibrary` 的旧版迁移），之后同样不动。
  将来哪个端口要接管，先去旧仓库 `LibraryIndex` 查键名结构，并写迁移。
- 坏分区加载时不炸启动，但要在 `PrefsLibrary.corruptPartitions` 留痕，供诊断端口消费；
  分区内部的**单条坏行**（如收藏 id 非法）整条丢弃、不留痕——行级脏数据不值得让整页变错误态。

## 边界与体积纪律

- 明确不做：Compose、Leanback、重写媒体解密层、SQLite、多端同步、下载、榜单、推荐流。
- 不引入大型 UI 库；新增依赖必须同时更新 `NOTICE.md`。
- **阶段内不构建 release 包**；验证只用 `:app:assembleDebug`。
  release 由维护者在发布前构建，体积验收口径与检查表见 [STATUS.md](STATUS.md)。

## 阶段记录（历史）

> 计数是**当时**的数字，保留为历史；当前测试数只在 [STATUS.md](STATUS.md) 维护。

| 阶段 | 内容 | 状态 |
| --- | --- | --- |
| S0 | 模块骨架、依赖规则、端口接口 | 完成 |
| S1 | 协议层：签名、解密、CENC、端点表、字段表 | 完成 |
| A0 | 平台基线：API 23、编解码替换、两层守卫 | 完成 |
| S2 | `data`：ContentPort / 本地存储实现 | 完成 |
| S3 | `uikit`：令牌、控件、焦点图、页面骨架 | 完成 |
| S4 | `feature-catalog`：搜索 / 收藏 / 历史 / 设置 | 完成 |
| S5 | `feature-detail`：详情 / 收藏系列（季线）/ 跨季入口 | 完成 |
| S6 | `feature-player`：播放会话、按键表、侧栏；`MediaPort` 实现 | 完成 |
| S7 | `app` 收口：导航栈、依赖组装、生命周期；`ArtworkPort` 实现 | 完成 |
| S8 | 探针、CI、manifest 收尾 | 完成 |
| S9 | 界面与交互收尾：焦点/选中语义、落焦阶梯、空态与首屏 | 完成 |
| S10 | 搜索与播放接口重建（官网 React SPA 改版后的新入口） | 完成 |
| S11 | 收藏页收紧、视觉方向焦点、抽屉设置分层、设备一次性注册 | 完成 |
| S12 | 季线只认服务端季号、历史元数据、45° 视锥、hero 与信息栏 | 完成 |
| S13 | 搜索聚合锚点季、收藏归组按专辑证据、详情集数 | 完成 |
| S14 | 片尾预取：最后 10 秒预取下一集并无缝换流 | 完成 |
| S15 | 审计修复：MP4 box 链、历史坏行、锚点证据复用、翻页封面预热 | 完成 |
