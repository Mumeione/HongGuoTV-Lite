package com.hongguotv.core.protocol

import org.json.JSONArray
import org.json.JSONObject

/** 端点用哪套签名。两套的 query 组成与头集合完全不同，混用会被风控判为异常并返回 200 空体。 */
enum class SignerKind(val id: String) {
    /** 完整套件：x-gorgon + x-helios + x-medusa + x-ladon + x-argus，query 含 ts。 */
    FULL("full"),

    /** preload 简化套件：只有 x-gorgon（0x40 0x1c 变体），query 只有 _rticket。 */
    PRELOAD("preload");

    companion object {
        fun of(id: String): SignerKind =
            entries.firstOrNull { it.id == id } ?: throw IllegalArgumentException("未知签名策略：$id")
    }
}

/** 端点自带 query 之外，是否还要并入一套公共设备常量。 */
enum class QueryProfile(val id: String) {
    /** 并入 [VendorConstants.device]（版本 71332）。内容端点用。 */
    DEVICE("device"),

    /** 不并入任何公共常量：端点自带全部参数。preload 用，它要的是 73532 的精简集。 */
    NONE("none");

    companion object {
        fun of(id: String): QueryProfile =
            entries.firstOrNull { it.id == id } ?: throw IllegalArgumentException("未知 query 配置：$id")
    }
}

enum class HttpMethod {
    GET, POST;

    companion object {
        fun of(value: String): HttpMethod = when (value.uppercase()) {
            "GET" -> GET
            "POST" -> POST
            else -> throw IllegalArgumentException("只支持 GET/POST：$value")
        }
    }
}

/**
 * 一个上游端点。字段名照着 endpoints.json，不谈业务语义——语义在 data 层的映射里。
 *
 * 占位符写作 `$name`，由调用方按需传入；缺参数直接抛错，不静默拼空串。
 * 静默空串是这类代码最常见的事故形态：请求发出去了，上游返 200 空体，最后报"没有剧集"。
 */
data class Endpoint(
    val id: String,
    /** 绝对 URL 前缀（host + path），解析时拼好。调用方不必再持有 hosts 表。 */
    val base: String,
    val method: HttpMethod,
    val signer: SignerKind,
    val queryProfile: QueryProfile,
    /** 顺序即协议：签名覆盖 query 的字节顺序。 */
    val query: List<Pair<String, String>>,
    val bodyTemplate: String?,
) {
    fun resolvedQuery(values: Map<String, String>): List<Pair<String, String>> =
        query.map { (key, value) -> key to fill(value, values) }

    fun resolvedBody(values: Map<String, String>): ByteArray? =
        bodyTemplate?.let { fill(it, values).toByteArray() }

    private fun fill(template: String, values: Map<String, String>): String =
        PLACEHOLDER.replace(template) { match ->
            val name = match.groupValues[1]
            values[name] ?: throw IllegalArgumentException("端点 $id 缺少参数：$name")
        }

    private companion object {
        val PLACEHOLDER = Regex("\\$([A-Za-z_][A-Za-z0-9_]*)")
    }
}

/** 端点表：hosts + endpoints。构造失败即视为表坏了，不做兜底。 */
data class EndpointTable(
    val hosts: Map<String, String>,
    val endpoints: Map<String, Endpoint>,
)

object Endpoints {

    private const val RESOURCE = "/endpoints.json"

    private val table: EndpointTable by lazy { load(resourceText()) }

    val hosts: Map<String, String> get() = table.hosts

    val all: Map<String, Endpoint> get() = table.endpoints

    operator fun get(id: String): Endpoint =
        table.endpoints[id] ?: throw IllegalArgumentException("端点表里没有 $id（可用：${table.endpoints.keys}）")

    /** 纯解析入口，供测试直接喂 JSON。 */
    fun load(json: String): EndpointTable {
        val root = JSONObject(json)
        val hostsJson = root.optJSONObject("hosts") ?: throw IllegalArgumentException("端点表缺少 hosts")
        val hosts = LinkedHashMap<String, String>()
        hostsJson.keys().forEach { hosts[it] = hostsJson.getString(it) }

        val endpointsJson = root.optJSONObject("endpoints") ?: throw IllegalArgumentException("端点表缺少 endpoints")
        val endpoints = LinkedHashMap<String, Endpoint>()
        endpointsJson.keys().forEach { id -> endpoints[id] = parseEndpoint(id, endpointsJson.getJSONObject(id), hosts) }
        if (endpoints.isEmpty()) throw IllegalArgumentException("端点表为空")
        return EndpointTable(hosts, endpoints)
    }

    private fun parseEndpoint(id: String, o: JSONObject, hosts: Map<String, String>): Endpoint {
        val hostKey = o.getString("host")
        val host = hosts[hostKey] ?: throw IllegalArgumentException("端点 $id 引用了未知 host：$hostKey")

        val path = o.getString("path")
        require(path.startsWith("/")) { "端点 $id 的 path 必须以 / 开头：$path" }
        // path 暂不支持占位符：需要它的端点（官网搜索）还没有消费者，等 S2 接入时再扩展，
        // 免得出一个"看起来能用其实拼错"的入口。
        require(!path.contains('$')) { "端点 $id 的 path 暂不支持占位符：$path" }

        val method = HttpMethod.of(o.getString("method"))
        val body = if (o.has("body")) o.getString("body") else null
        if (method == HttpMethod.POST) require(body != null) { "端点 $id 是 POST，必须有 body 模板" }
        if (method == HttpMethod.GET) require(body == null) { "端点 $id 是 GET，不该有 body 模板" }

        val queryJson = o.optJSONArray("query") ?: JSONArray()
        val query = (0 until queryJson.length()).map { i ->
            val pair = queryJson.getJSONArray(i)
            require(pair.length() == 2) { "端点 $id 的 query 第 $i 项必须是两个元素的数组" }
            pair.getString(0) to pair.getString(1)
        }

        return Endpoint(
            id = id,
            base = host + path,
            method = method,
            signer = SignerKind.of(o.getString("signer")),
            queryProfile = QueryProfile.of(o.getString("queryProfile")),
            query = query,
            bodyTemplate = body,
        )
    }

    private fun resourceText(): String =
        Endpoints::class.java.getResourceAsStream(RESOURCE)?.bufferedReader()?.use { it.readText() }
            ?: throw IllegalStateException("缺少资源 $RESOURCE；它必须随 core 一起打包")
}
