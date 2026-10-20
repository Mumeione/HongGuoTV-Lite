# 第三方声明

## 源码衍生关系

本仓库是 **N3urda/hongguoTV**（GPL-3.0）的搬迁式重构，源码与其 main 分支 `eea73e6a` 同源：

- 上游仓库：https://github.com/N3urda/hongguoTV
- 原生应用源码与派生实现以 **GPL-3.0-only** 发布，许可证全文见 [LICENSE](LICENSE)。
- 本仓库相对上游的改动：模块结构重划（7 个 Gradle 模块 + 依赖方向强制校验）、协议层与业务层分离、
  移除应用内更新 / 首页推荐 / 榜单 / 局域网与扫码服务、本地数据层与季线逻辑重写。
  逐阶段的搬迁记录见提交历史。
- `core` 中的内容请求签名、Spade URL / key 处理、MP4 CENC 处理是以下 GPL-3.0 源码的 Kotlin 移植：
  - drpys, `spider/js/红果果[短].js`
  - https://github.com/woshishiq1/drpys/tree/22261adfa31435e3b3ef5a730b8a2a75bcaf1715
  - 对照源与许可全文：`licenses/LICENSE.drpys`（取回于 2026-09-21，原对照源为旧仓库 `vendor/hongguo.cjs`）
  - 本仓库改动：完整 Kotlin 实现；签名只选择已验证的 hash branch 0；加密媒体通过 Media3 DataSource 直接读取；增加样本边界检查、子样本处理和 AVC 原始编码恢复。
- 工程骨架思想源自旧仓库；旧仓库骨架派生自 @react-native-tvos/template-tv 0.81.0-0（MIT），许可全文见 `licenses/LICENSE.template-tv`。

APK 不包含 Node、React Native、JavaScript 引擎或该 JavaScript 源文件。本应用不是内容平台官方客户端。

## 依赖

- Kotlin 标准库：https://github.com/JetBrains/kotlin — Apache-2.0
- AndroidX Media3：https://github.com/androidx/media — Apache-2.0
- OkHttp / Okio：https://github.com/square/okhttp / https://github.com/square/okio — Apache-2.0
- AndroidX core：https://developer.android.com/jetpack/androidx — Apache-2.0
- org.json（仅 JVM 工具，APK 使用 Android 内置实现）：https://github.com/stleary/JSON-java — 公有领域声明
- JUnit（仅测试）：https://github.com/junit-team/junit4 — EPL-1.0

> 各阶段搬迁若引入新依赖，必须同步更新本清单。
