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

六个业务端口 + 五个支撑端口。业务端口回答「要做什么」，支撑端口回答「怎么做才不散」：

| 端口 | 接口 | 背后藏着 |
| --- | --- | --- |
| `ContentPort` | `search / suggest / detail / seasons / stream` | 端点表、签名、HTTP、字段解析、季线排序、锚点补全、介质过滤、清晰度选择 |
| `ProgressPort` | 进度 / 历史 / 写入 | prefs 键、编解码、写队列、节流 |
| `FavoritesPort` | 收藏 / 季键 / 季线缓存 / 确认更新 / 当前季 | 系列语义、LRU、检查结果 |
| `SettingsPort` | 读 / 原子更新 | 七项偏好的读写路径 |
| `MediaPort` | `open(vid, maxHeight) → MediaStream` | Range 分段、CENC 索引、逐样本解密 |
| `Navigator` | `toCatalog / toDetail / toSeries / toPlayer / back` | 页面栈与返回语义 |
| `ArtworkPort` | `load / prefetch / cancelPage / trim` | 优先级队列、LRU、磁盘缓存（200 文件 / 30MB / 落盘压缩 512px JPEG）、低内存分档 |
| `DiagnosticsPort` | `stage / event` | 五段探针与日志节点 |
| `DecoderCapabilities` | `supports(codec)` | 平台 `MediaCodecList` 查询；取流选档先按它过滤，避开本机解不了的编码 |
| `UpdatePort` | `check / download / autoCheck` | CNB+GitHub 双源清单、版本比较、下载状态机（安装 Intent 在 `app` 层） |
| `DevicePort` | `reRegister()` | 强制重新注册一台设备（忽略 12 小时冷却）；自动换新藏在 `ContentPort` 背后 |

`ContentPort` 只有五个方法，这是刻意的：把「上游长什么样」全部挡在背后，
调用方只谈业务（搜索、详情、季线、取流）。协议细节见 [PROTOCOL.md](PROTOCOL.md)。

`ArtworkPort`、`DiagnosticsPort`、`DecoderCapabilities`、`UpdatePort` 与 `DevicePort` 是五个支撑端口：
它们不属于业务能力，但同样需要接缝，否则封面策略、日志字段、解码能力查询、更新流程与
设备身份的应急出口会重新散回每个页面。

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

## 收藏更新检查

- **每部只问一次**（`FavoriteUpdateCheck`）：季线里至少两季才问 `seasons()`，其余问 `detail()`。
  以前是 `detail + seasons` 两次，而 `seasons()` 在锚点没有专辑证据时还会补一次详情，
  100 部收藏的实际请求量是 200~500 次。
- **有意的取舍**：多季剧的「当前季又加了几集」只有在季线带了那一季时才看得到（plan 的相关作品
  通常不含锚点本尊，那一格是收藏时的快照）。**多季剧的日常更新不是重点，新季上线才是**——
  这是产品口径，不是 bug。
- **集数未知（0）是合法值**：季线里没有当前季时只写检查时间（`FavoriteUpdateRow(total = 0)`，
  `added` 恒 0），不拿别的季的数字顶——顶上去角标会写成「更新到第十一季 第60集」而 60 是上一季的。
- **自动检查分批**：12 小时有效期 + 全局 6 小时门槛 + 每轮最多 40 部（按最旧优先），
  100 部分三轮扫完；手动检查全量；离开收藏页即取消。
  6 小时门槛读**整轮时间**（`favoriteCheckRoundAt`），不是 `max(checkedAt)`：单部检查
  （剧集详情 / 选季页）也写 checkedAt，拿最大值当门槛会让"查了一部"停掉整轮 6 小时。
- 「有新集」判的是**本次探针集数 > 已知集数**，不读历史未读标记——那个标记只给卡片角标用。
- **入口按剧型分两处**（2026-10-29）：多季剧在**选季页**（检查结果就是季卡行上的新季），
  单季剧（没有季线）在**剧集详情页**。两处都写回 `checkedAt`，这一部因此退出自动轮转 12 小时。
  详情页键在"这一查查出了第二季"时会收起（入口归选季页），结果改用 toast 说一次。

## 封面

- 缓存键里的尺寸**按 64px 桶取整**：宽高是布局量出来的（hero 封面跟着右列文字高度走），
  不归桶就是「差几像素 = 一次新下载」；同一个 URL 换尺寸也不清屏（旧位图顶到新图贴上）。
- 落盘资格只有收藏 / 历史（`ArtworkSpec.persist`）；「继续看」与历史页是同一批 URL，
  因此也开落盘——不多占一个文件，只是不再每次重下。
- 季线封面**按当前分组**渲染：只建当前 7 张卡，翻组时重建这一行，不预先拉全部季的图。

## 本地数据的不变量

- **prefs 文件名 `native-library-v1` 不许改。** 升级安装读旧数据的唯一桥梁，
  改了等于让老用户的收藏与进度归零。更新检查的节流时间戳单独放 `update-check-v1`（非用户数据）；
  设备注册结果单独放 `device-session-v1`（与收藏/历史的写线程隔离，避免对同一 prefs 文件并发 `commit`；
  换文件意味着升级后重新注册一次设备身份）。
- **三个端口读的是同一份事实**（进度 / 收藏 / 设置分区），不许为了「干净」另开一份存储。
- **容量上限**（V1.0.0 容量治理）：历史 30 行、收藏 100 行（写入端按 `addedAt` 最旧淘汰）、
  季线缓存 20 条、封面磁盘 200 文件 / 30MB（落盘重编码为长边 512px JPEG）。
- 旧版遗留分区 `later` / `hidden` / `favorites` / `searches` **故意不读、不写、不删**；
  唯一例外是旧 `favorites`：迁移时只读一次（`PrefsLibrary` 的旧版迁移），之后同样不动。
  将来哪个端口要接管，先去旧仓库 `LibraryIndex` 查键名结构，并写迁移。
- 坏分区加载时不炸启动，但要在 `PrefsLibrary.corruptPartitions` 留痕，供诊断端口消费；
  分区内部的**单条坏行**（如收藏 id 非法）整条丢弃、不留痕——行级脏数据不值得让整页变错误态。

## 边界与体积纪律

- 明确不做：Compose、Leanback、重写媒体解密层、SQLite、多端同步、下载、榜单、推荐流。
- 不引入大型 UI 库；新增依赖必须同时更新 `NOTICE.md`。
- 日常验证只用 `./gradlew verify`（全模块单测 + 六模块 lint + `:app:assembleDebug`）；
  release 包只在发版时构建，版本号由 `HONGGUOTV_VERSION_NAME` / `HONGGUOTV_VERSION_CODE` 显式传入。
