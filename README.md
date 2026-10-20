# 红果 TV Lite

An unofficial, non-commercial Android TV client for 红果短剧, written in Kotlin. GPL-3.0.

安卓 TV 端的红果短剧第三方客户端，Kotlin + 原生 View + Media3，最低 Android 8.0（API 26）。
本项目是一次**搬迁式重构**：把已经验证可用的播放与交互实现，搬进一套依赖方向被强制约束的模块结构里。

> **状态：S0 骨架。尚未可用。** 当前仓库只有模块骨架、端口接口与一个空壳 Activity。
> 功能按 S1–S7 逐步搬入，进度见[路线图](#路线图)。

---

## 免责声明

本项目是**非官方**第三方客户端，与红果短剧及其运营方**没有任何隶属、合作或授权关系**。

- 项目仅包含**客户端实现**，不托管、不转存、不分发任何音视频内容；所有内容由用户设备实时请求获得。
- 不包含账号登录、会员权益、付费内容绕过、去广告等任何相关实现。
- 仅供**个人学习与技术研究**，请勿用于商业用途。
- 使用者应自行确保其使用行为符合所在地法律法规及平台服务条款。
- 作者不对因使用本项目产生的任何后果负责，亦不提供任何形式的担保。

---

## 这是什么，不是什么

**是**：一个为电视遥控器设计的内容浏览器 + 播放器。搜索、收藏（按系列而非单季）、观看历史、断点续播、跨季连播、片尾预取、故障自动恢复。

**不是**：内容平台、下载器、多端同步、排行榜、推荐流、弹幕、账号体系。这些是被明确排除的范围，不是"还没做"。

技术上的两个取舍，值得先说清楚：

1. **不为框架而框架。** 用原生 View 而不是 Compose——目标是低配电视盒上的焦点可控与内存可预期，不是开发体验。
2. **协议层与业务层强制分离。** 上游接口与风控会变，所以"它长什么样"必须集中在能一处修改的地方。这不是洁癖，是这个项目能活下去的前提。

---

## 架构

七个 Gradle 模块，依赖只能向下，**违反会让构建直接失败**（配置期校验，不是警告）。

```
app               壳：Activity、导航、依赖组装（目标 ≤250 行）
├── feature-catalog   搜索 / 收藏 / 历史 / 设置
├── feature-detail    详情 / 收藏系列
├── feature-player    播放页 / 侧栏 / 播放会话
├── uikit             设计令牌 / 六种控件 / 焦点图 / 页面骨架
└── data              端口实现：上游 HTTP / 本地存储 / 取流
    └── core          纯 JVM：协议 / 模型 / 端口 / 用例
```

| 模块 | 类型 | 不得依赖 |
| --- | --- | --- |
| `core` | `kotlin("jvm")`，无 Android SDK | 任何本工程模块 |
| `data` | android library | `uikit`、`feature-*` |
| `uikit` | android library | `data`、`feature-*` |
| `feature-catalog` / `-detail` / `-player` | android library | 互相之间、`data` |
| `app` | android application | — |

三条规则由构建系统兜底，不靠自觉：

- `core` 里 **`import android.*` 直接编译失败**（它没有 Android SDK），因此全部业务逻辑可脱离设备单测。
- 三个 feature **互不依赖**：目录页想跳详情，调 `Navigator.detail(id)`，不知道详情页在哪个模块。
- feature **只能依赖 `core.port` 的接口**，拿不到任何实现类；实现由 `app` 注入。

核心接缝是一个只有五个方法的内容端口：

```kotlin
interface ContentPort {
    suspend fun search(keyword: String, filter: ContentFilter): SearchResult
    suspend fun suggest(keyword: String): List<Suggestion>
    suspend fun detail(seriesId: String): Detail
    suspend fun seasons(seriesId: String, anchor: Series?): SeasonLine
    suspend fun stream(vid: String, maxHeight: Int): StreamInfo
}
```

端点、请求签名、字段映射、季线排序、介质过滤全部在它背后。

协议形状本身是一张数据表，不是代码：`core/src/main/resources/endpoints.json`。端点、query 顺序、
body 模板、用哪套签名，都在这一个文件里。Kotlin 侧由 `protocol/Endpoints.kt` 读取；
S8 的诊断探针会读同一份文件（当前尚未接入）。上游改一个字段名，改动落在 `Fields.kt` 的一行；
改一个端点，落在表里的一个块。

```jsonc
"preload": {
  "host": "stream",
  "path": "/novel/player/multi_video_model/preload/v1/",
  "signer": "preload",          // 取流用简化签名，不能用完整套件
  "queryProfile": "none",       // 自带全部 20 个参数，不并入公共设备常量
  "query": [["aid", "8662"], ["device_id", "$deviceId"], "…"]
}
```

`query` 是数组而不是对象，因为签名覆盖 query 的字节顺序——顺序是协议的一部分。

失败也分类，而不是拼中文字符串给上层猜：

```kotlin
sealed class UpstreamError {
    class Network(cause: Throwable) : UpstreamError()
    class HttpStatus(val code: Int) : UpstreamError()
    class EmptyBody : UpstreamError()      // 风控的强特征，单独成类
    class Shape(val path: String) : UpstreamError()
    class RiskControl(val code: Int) : UpstreamError()
    class Decrypt(val step: String) : UpstreamError()
}
```

---

## 构建

需要 JDK 17、Android SDK 36（含 build-tools 36.0.0）。

```sh
./gradlew :core:test
./gradlew :app:assembleDebug
```

宿主机没有 Android SDK 时，在 WSL（Ubuntu 24.04）里构建，并显式导出环境变量——`bash -c` 不是登录 shell，不会自动加载 profile：

```sh
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=$HOME/.buildozer/android/platform/android-sdk
./gradlew :core:test :app:assembleDebug --console=plain
```

正式签名需要 `keystore.properties`（不入库），或用环境变量 `HONGGUOTV_KEYSTORE` / `HONGGUOTV_STORE_PASSWORD` / `HONGGUOTV_KEY_ALIAS` / `HONGGUOTV_KEY_PASSWORD`。

**验证依赖规则真的生效**：给 `feature-catalog/build.gradle.kts` 加一行 `implementation(project(":feature-player"))`，再跑任意任务——应当直接报错退出。这是本项目最重要的一条不变量，值得亲手试一次。

---

## 路线图

| 阶段 | 内容 | 状态 |
| --- | --- | --- |
| S0 | 七模块骨架、依赖规则、端口接口、空壳 APK | ✅ 完成 |
| S1 | `protocol`：端点表、字段表、请求签名、Spade 解密、MP4 CENC 索引 | 🟡 代码完成；实网探针待 S8 |
| S2 | `data`：内容端口实现、本地存储（进度/收藏/设置）、错误分类 | 待开始 |
| S3 | `uikit`：设计令牌、六种控件、焦点图纯函数、页面骨架 | 待开始 |
| S4 | `feature-catalog`：搜索 / 收藏 / 历史 / 设置 | 待开始 |
| S5 | `feature-detail`：详情、收藏系列页、跨季 | 待开始 |
| S6 | `feature-player`：播放会话状态机、按键表、侧栏菜单 | 待开始 |
| S7 | `app` 收口：导航、依赖组装 | 待开始 |
| S8 | 诊断探针、CI、文档收尾 | 待开始 |

功能在 S4–S6 之后才可用。在那之前，从旧仓库安装包体验更实际。

---

## 目录结构

```
core/        纯 JVM。protocol/ 协议与密码学 · model/ 领域模型 · port/ 接口与错误 · usecase/ 纯逻辑
data/        端口实现。remote/ 上游 HTTP · local/ 存储 · media/ 取流与解密
uikit/       设计系统。token/ · widget/ · focus/ · page/
feature-*/   三个业务模块，互不感知
app/         装配与导航
licenses/    第三方许可证全文
```

---

## 参与

欢迎 issue 与 PR。三条约定：

1. **一次只推进一个 S 阶段**，不要跨阶段混提。
2. **搬迁提交只允许"移动 + 改名"**。任何行为变更必须单独提交，并在描述里指明对应的功能条目。
3. **依赖规则必须过**。为了让构建通过而放宽模块边界，比功能缺失更不可接受。

提交信息用 `类型: 中文摘要`，类型取 `feat` / `fix` / `refactor` / `docs` / `chore` / `test`。

---

## 许可

代码以 **GPL-3.0-only** 发布，全文见 [LICENSE](LICENSE)。

本项目派生自 [N3urda/hongguoTV](https://github.com/N3urda/hongguoTV)（GPL-3.0，`eea73e6a`），并相对其做了大量修改与裁剪。
内容请求签名、Spade 处理与 MP4 CENC 处理移植自 [drpys](https://github.com/woshishiq1/drpys)（GPL-3.0）。
工程骨架思想源自 [@react-native-tvos/template-tv](https://github.com/react-native-tvos/template-tv)（MIT）。

完整的衍生关系、改动说明与依赖清单见 [NOTICE.md](NOTICE.md)。

---

## 致谢

- [N3urda/hongguoTV](https://github.com/N3urda/hongguoTV) — 本项目的起点
- [woshishiq1/drpys](https://github.com/woshishiq1/drpys) — 内容接口与签名算法的参考实现
- [androidx/media3](https://github.com/androidx/media) — 播放与取流的基础设施
