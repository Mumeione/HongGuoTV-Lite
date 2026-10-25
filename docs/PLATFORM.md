# 平台与 API 红线

> **基线是硬要求，不是建议。** 本项目从第一天就按 Android 6.0（API 23）写。
> 市面上相当一部分 TV 仍停在 6.0——包括 2020 年前后的「新品」，厂商换系统版本的意愿极低。
> 这不是一批「该淘汰的老设备」，而是一个长期存在的装机面。

## 最低版本策略

| 项 | 值 | 说明 |
| --- | --- | --- |
| `minSdk` | **23（Android 6.0）** | 硬基线；所有模块一致，新模块默认继承 |
| `targetSdk` | 36 | 跟随最新，行为变更按需适配 |
| `compileSdk` | 36 | 只用 36 的 API 编译，运行期受 minSdk 约束 |
| JVM target | 17 | D8 负责脱糖语言特性（lambda、默认方法、try-with-resources） |

**迁移状态**：A0 已完成。所有模块 `minSdk = 23`；编解码换成自带实现（`Base64Codec`）；
两层守卫就位（Android 侧 Lint `NewApi`，core 侧 `verifyJvmApiLevel` 字节码扫描，挂在 `test` 前面）。
之后的任何新代码都由守卫在构建期拦截，不再靠自觉。

## 为什么不用 core library desugaring 兜底

`desugar_jdk_libs` 能补一部分高版本 JDK API，但有两个问题：

1. **它只作用于 Android 模块。** `core` 是 `kotlin("jvm")` 模块，它的字节码不经过 Android 的
   脱糖流程；`java.util.Base64` 写在 core 里，desugar 帮不上。
2. **覆盖范围不确定。** 它保证 `java.time`、`java.util.stream`、`java.util.function`、
   `java.util.Optional` 等，但是否包含 `java.util.Base64` 需要逐版本确认。
   为了几个工具函数去赌一个版本特性，不划算。

结论：**不依赖脱糖，遇到高版本 API 就自己实现或换写法。**

## API 红线

用到下表里的东西，在 `minSdk 23` 下会 `NoClassDefFoundError` 或 `NoSuchMethodError`——
**编译期不报错，只在老设备上崩。** 这张表由构建期检查强制，不靠自觉。
强制范围：**类级条目**（如 `java.util.Base64`、`java.util.Optional`）在 core 里由字节码扫描拦截；
**方法级条目**（`String.join`、`Map.computeIfAbsent` 等）在 Android 模块由 Lint `NewApi` 拦截，
在 core 里只能靠 review 自觉——core 现有代码未使用任何方法级禁项。

| 禁止 | 引入版本 | 替代 |
| --- | --- | --- |
| `java.util.Base64` | 26 | 自带编解码实现 |
| `java.time.*` | 26 | 用 `Long` 存时间戳，格式化自己写 |
| `java.nio.file.*` | 26 | `java.io.File` |
| `java.util.stream.*` | 24 | 普通循环 / Kotlin 集合操作 |
| `java.util.function.*` | 24 | Kotlin 函数类型 `(T) -> R` |
| `java.util.Optional` | 24 | Kotlin 可空类型 |
| `String.join` | 26 | `joinToString` |
| `Map.computeIfAbsent` / `merge` / `replaceAll` | 24 | 显式 `get` / `put` |
| `Collection.removeIf` / `Iterable.forEach` | 24 | Kotlin 的 `filter` / `forEach`（stdlib，不同符号） |
| `java.util.concurrent.CompletableFuture` | 24 | 线程池 + 回调 |
| `Math.floorDiv` / `floorMod` | 24 | 手写取模 |
| `List.of` / `Set.of` / `Map.of` | Android 无 | `listOf` / `setOf` / `mapOf` |
| `ConnectivityManager.registerDefaultNetworkCallback` | 24 | `registerNetworkCallback(NetworkRequest, cb)` |
| `Context.startForegroundService` / `NotificationChannel` | 26 | 本项目不需要前台服务 |

**安全区**：Kotlin 标准库的同名函数（`listOf`、`forEach`、`joinToString`、`Map.getOrPut`）
编译到 Kotlin 自己的实现，不受上表限制。区分办法：看它是 `kotlin.collections` 还是 `java.util`。

## 怎么让这条红线不靠自觉

三层，从便宜到贵：

1. **Android Lint `NewApi`**：所有 Android 模块（app + 5 个 library）在各自
   `build.gradle.kts` 显式声明 `lint { abortOnError = true; error += "NewApi" }`，
   `minSdk` 23 下调用超版本 API 直接构建失败。
2. **`core` 的字节码扫描**：`core` 是纯 JVM 模块，Lint 看不到它。`verifyJvmApiLevel`
   扫 `core/build/classes` 里是否出现上表的包名（常量池里是明文字符串）。
3. **真机**：只有真机能验证解码器、TLS、内存这些运行期风险。

验证入口是 `./gradlew verify`，当前测试数与结果见 [STATUS.md](STATUS.md)。

## Manifest 收尾

| 项 | 状态 |
| --- | --- |
| `allowBackup="false"` + `fullBackupContent="false"` + `dataExtractionRules` | 已完成 |
| `networkSecurityConfig`：base 禁止明文，仅对上游媒体/CDN 域开口 | 已完成（API 24+ 生效） |
| `usesCleartextTraffic="true"` | 仅为 API 23 保留旧行为（该版本不认 networkSecurityConfig） |
| `enableOnBackInvokedCallback` 的 `UnusedAttribute` | `tools:ignore` 只压这一条 |
| `screenOrientation="landscape"` 的 `DiscouragedApi` | TV 应用就是横屏，`tools:ignore` + 注释 |

**应用权限只在 `app` 的清单里声明。** `feature-player` 需要读网络状态来决定「断网不排自动重试」，
但 `ACCESS_NETWORK_STATE` 仍只写在 `app/src/main/AndroidManifest.xml`。新增权限一律先改 app 清单。

**图标与 banner**：`@mipmap/ic_launcher` 已有自适应图标（含 `monochrome` 层）；
`@drawable/tv_banner` 是 640×360（16:9），符合 Leanback 建议比例。两项都不再是遗留问题。

## 老设备的三类运行期风险

| 风险 | 表现 | 应对 |
| --- | --- | --- |
| **TLS 栈过旧** | 连不上、握手失败；Android 6 支持 TLS 1.2 但部分设备的 cipher 套件受限 | 不做全局放宽；失败归到 `UpstreamError.Network` 并在诊断里区分握手错误；必要时为特定设备配置 `ConnectionSpec` |
| **缺解码器** | 有画面无声音、或整段黑屏；老芯片普遍没有 HEVC/AV1 | 清晰度选择按设备能力降级到 H.264；换集失败时提示切低清晰度 |
| **内存极小** | 1GB 甚至 512MB 的盒子，OOM 或频繁 GC | 封面缓存与播放器缓冲按 `isLowRamDevice` 分档；预取预热字节下调；避免整页重建 |

## Doze 与后台

Android 6 引入 Doze。本应用只在**前台**做网络与预取，`onPause` 会取消预取与恢复倒计时，
因此不涉及后台唤醒。改动时不要引入后台轮询。
