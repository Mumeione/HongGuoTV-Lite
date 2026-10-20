package com.hongguotv.core.protocol

/** 签名后的完整请求。带 method，调用方不必再回表里查一次。 */
data class SignedRequest(
    val method: HttpMethod,
    val url: String,
    val headers: Map<String, String>,
    val body: ByteArray?,
)

/**
 * 端点 → 请求。两套实现对应 [SignerKind]，由端点表的 `signer` 字段选择。
 *
 * 为什么要有这个接口：完整套件与 preload 简化套件在 query 组成、头集合、gorgon 变体上
 * 完全不同，而"哪个端点用哪套"是上游的事实，不该分散在调用点的分支里。
 * 换策略时改表一行，调用方不动。
 */
interface RequestSigner {
    val kind: SignerKind

    fun sign(endpoint: Endpoint, values: Map<String, String>): SignedRequest
}

/** 按端点的 queryProfile 组装参数表；顺序 = 公共常量 → 端点参数，签名覆盖这个顺序。 */
private fun Endpoint.mergedQuery(values: Map<String, String>): LinkedHashMap<String, String> {
    val merged = LinkedHashMap<String, String>()
    if (queryProfile == QueryProfile.DEVICE) merged.putAll(VendorConstants.device)
    resolvedQuery(values).forEach { (key, value) -> merged[key] = value }
    return merged
}

/** 内容端点用：并入公共设备常量，query 含 ts，带五件套头。 */
class FullSuiteSigner internal constructor(private val crypto: Signer) : RequestSigner {

    constructor() : this(Signer())

    override val kind = SignerKind.FULL

    override fun sign(endpoint: Endpoint, values: Map<String, String>): SignedRequest {
        require(endpoint.signer == kind) {
            "端点 ${endpoint.id} 声明用 ${endpoint.signer.id}，不该走 ${kind.id}"
        }
        val request = crypto.sign(endpoint.url(values), endpoint.mergedQuery(values), endpoint.resolvedBody(values))
        return SignedRequest(endpoint.method, request.url, request.headers, request.body)
    }
}

/**
 * 取流用：端点自带全部 query，query 不含 ts，只带简化头。
 *
 * 传进来的 `deviceId` / `installId` **必须**是进程级复用的随机十九位（旧实现口径 [1e18, 9e18)），
 * 不能拿 [VendorConstants.device] 里那两个固定值顶替——那组值是内容端点的身份
 * （版本 71332），混进取流请求会被判成另一个客户端，返回 200 空体。
 */
class PreloadSigner internal constructor(private val crypto: Signer) : RequestSigner {

    constructor() : this(Signer())

    override val kind = SignerKind.PRELOAD

    override fun sign(endpoint: Endpoint, values: Map<String, String>): SignedRequest {
        require(endpoint.signer == kind) {
            "端点 ${endpoint.id} 声明用 ${endpoint.signer.id}，不该走 ${kind.id}"
        }
        // 这条不是洁癖：一旦把 71332 的设备常量并进 73532 的 preload 请求，
        // 上游会把它当成另一个客户端，返回 200 空体。
        require(endpoint.queryProfile == QueryProfile.NONE) {
            "preload 端点不得并入公共设备常量：${endpoint.id}"
        }
        val body = endpoint.resolvedBody(values)
            ?: throw IllegalArgumentException("端点 ${endpoint.id} 是 POST，必须有 body")

        val request = crypto.signSimple(endpoint.url(values), endpoint.mergedQuery(values), body)
        return SignedRequest(endpoint.method, request.url, request.headers, request.body)
    }
}

/**
 * 官网 SSR 用：无签名、无公共常量，query 原样拼接（值经 [urlEncode]）。
 * 风控只看 UA 与请求频率——节流是调用方（data 层）的职责，签名层不管。
 */
class PlainSigner : RequestSigner {

    override val kind = SignerKind.NONE

    override fun sign(endpoint: Endpoint, values: Map<String, String>): SignedRequest {
        require(endpoint.signer == kind) {
            "端点 ${endpoint.id} 声明用 ${endpoint.signer.id}，不该走 ${kind.id}"
        }
        val query = endpoint.mergedQuery(values).entries.joinToString("&") { (key, value) ->
            "${urlEncode(key)}=${urlEncode(value)}"
        }
        val url = endpoint.url(values) + if (query.isEmpty()) "" else "?$query"
        // 头集合为空是刻意的：签名层不替无签名通道做主，UA 等默认头由 HTTP 层统一补。
        return SignedRequest(endpoint.method, url, emptyMap(), null)
    }
}

/**
 * HTTP 公共头默认值。签名请求的头集合（含 UA）由各 Signer 组装，不走这里；
 * 这里只服务无签名通道（官网 SSR、fallback 取流），保证 UA 这种风控身份字段只有一处事实来源。
 */
object RequestDefaults {
    const val USER_AGENT = VendorConstants.VIDEO_UA
    const val ACCEPT_LANGUAGE = "zh-CN,zh;q=0.9"
}

/**
 * 签名实现的注册表：按端点声明的 [SignerKind] 派发。
 * data 层的 ContentPort 实现从这里取，不再自己判断"这个端点该用哪套"。
 */
class SignerRegistry(signers: List<RequestSigner> = listOf(FullSuiteSigner(), PreloadSigner(), PlainSigner())) {

    private val byKind: Map<SignerKind, RequestSigner> = signers.associateBy { it.kind }

    fun forEndpoint(id: String): RequestSigner = forKind(Endpoints[id].signer)

    fun forKind(kind: SignerKind): RequestSigner =
        byKind[kind] ?: throw IllegalStateException("没有注册 ${kind.id} 的签名实现")

    fun sign(endpointId: String, values: Map<String, String>): SignedRequest =
        forEndpoint(endpointId).sign(Endpoints[endpointId], values)
}
