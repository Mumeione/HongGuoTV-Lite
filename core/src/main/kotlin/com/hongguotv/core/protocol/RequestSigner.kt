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
 * 为什么要有这个接口：带签名的 App 接口与无签名的官网接口在 query 组成、头集合上
 * 完全不同，而"哪个端点用哪套"是上游的事实，不该分散在调用点的分支里。
 * 换策略时改表一行，调用方不动。
 */
interface RequestSigner {
    val kind: SignerKind

    fun sign(endpoint: Endpoint, values: Map<String, String>): SignedRequest
}

/** 按端点的 queryProfile 组装参数表；顺序 = 公共常量 → 端点参数，签名覆盖这个顺序。 */
private fun Endpoint.mergedQuery(
    values: Map<String, String>,
    identity: DeviceIdentity = DeviceIdentity.LEGACY,
): LinkedHashMap<String, String> {
    val merged = LinkedHashMap<String, String>()
    if (queryProfile == QueryProfile.DEVICE) merged.putAll(VendorConstants.device(identity))
    resolvedQuery(values).forEach { (key, value) -> merged[key] = value }
    return merged
}

/** 内容端点用：并入公共设备常量，query 含 ts，带五件套头。 */
class FullSuiteSigner internal constructor(
    private val crypto: Signer,
    private val identity: DeviceIdentity = DeviceIdentity.LEGACY,
) : RequestSigner {

    constructor(identity: DeviceIdentity = DeviceIdentity.LEGACY) : this(Signer(identity = identity), identity)

    override val kind = SignerKind.FULL

    override fun sign(endpoint: Endpoint, values: Map<String, String>): SignedRequest {
        require(endpoint.signer == kind) {
            "端点 ${endpoint.id} 声明用 ${endpoint.signer.id}，不该走 ${kind.id}"
        }
        val request = crypto.sign(endpoint.url(values), endpoint.mergedQuery(values, identity), endpoint.resolvedBody(values))
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
class SignerRegistry(
    signers: List<RequestSigner> = listOf(FullSuiteSigner(), PlainSigner()),
) {

    private val byKind: Map<SignerKind, RequestSigner> = signers.associateBy { it.kind }

    fun forEndpoint(id: String): RequestSigner = forKind(Endpoints[id].signer)

    fun forKind(kind: SignerKind): RequestSigner =
        byKind[kind] ?: throw IllegalStateException("没有注册 ${kind.id} 的签名实现")

    fun sign(endpointId: String, values: Map<String, String>): SignedRequest =
        forEndpoint(endpointId).sign(Endpoints[endpointId], values)
}
