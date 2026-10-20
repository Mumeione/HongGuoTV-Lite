# tools/diag

开发期诊断工具。不参与 APK 构建，不随应用分发。

## signing-oracle.cjs

从上游参考实现重新生成签名测试向量。

```sh
node tools/diag/signing-oracle.cjs --source /path/to/hongguo.cjs
```

**参考实现不随本仓库分发。** 它是 drpys 的 `spider/js/红果果[短].js`（GPL-3.0），
见根目录 `NOTICE.md`。脚本只读取它、不修改它，通过 `vm` 取出其中的函数。

产出：

| 文件 | 内容 | 谁在用 |
| --- | --- | --- |
| `core/src/test/resources/signing.json` | 完整签名套件 12 组：hash / gorgon / helios / medusa | `SignerTest` |
| `core/src/test/resources/preload-signing.json` | preload 简化签名 10 组，**端到端**：请求参数、query、x-gorgon、x-ss-stub | `PreloadSigningTest` |
| `tools/diag/signing.provenance.json` | 参考实现的 sha256（不含时间戳，重跑不产生 diff） | 人看 |

`preload-signing.json` 之所以存**参数表**而不只存签名结果：测试要拿它去调用
`Signer.signSimple()`，再断言产出的 URL 与头值。这样测的是整条路径，
而不是绕过接口去测内部函数——后者在「接口接错线」时会照样全绿。

### 为什么需要它

`signing.json` 只是某一版算法的快照。没有重算能力时，它只能证明「我的移植没退步」，
不能证明「协议还对」——上游改了签名，Kotlin 仍是旧算法，测试依然全绿。

有了这个脚本，上游一改算法，流程是：拿到新参考实现 → 重跑本脚本 → `:core:test` 变红
→ 按新向量调 Kotlin → 变绿。从「猜哪里坏了」变成「对着向量改」。

### 注意

- 输出是确定性的：固定了 `Date.now` 与 `Math.random`，时间戳采用显式小端写入，
  同一份参考实现每次产出一致。若重跑后 `preload-signing.json` 大面积变化，
  先比对 `signing.provenance.json` 的 sha256，确认参考实现本身是否被改过。
- 只生成 hash branch 0 的向量：`sign()` 会调整 `_rticket` 直到落在 branch 0。
  参考实现若只吐出别的 branch，说明它的输入构造变了，需要同步调整脚本。
- 参数值里刻意保留了一个非 ASCII 值（`app_name=短剧阅读`），用于验证
  `Signer.encode` 与 JS `encodeURIComponent` 的编码口径一致——两者在 `*`、`_`、`-`、`.` 上
  的处理必须相同，否则 query 与签名对不上。
