package com.hongguotv.lite.data.content

import com.hongguotv.core.model.ContentFilter
import com.hongguotv.core.port.UpstreamError
import com.hongguotv.core.protocol.HttpMethod
import com.hongguotv.core.protocol.SignedRequest
import com.hongguotv.lite.data.http.HttpTransport
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.test.assertFailsWith

/**
 * ContentPort 的行为接线测试：解析规则在 ParsersTest 里已覆盖，这里只验证
 * "端点选得对、映射串得对、失败分类落得对"。样本与旧仓库 ContentRepositoryTest 同源。
 */
class ContentRepositoryTest {

    private class FakeTransport(val respond: (SignedRequest) -> String) : HttpTransport {
        val urls = mutableListOf<String>()
        override suspend fun text(request: SignedRequest): String {
            urls += request.url
            return respond(request)
        }
    }

    private fun repository(respond: (SignedRequest) -> String): ContentRepository {
        val transport = FakeTransport(respond)
        return ContentRepository(transport).also { repo -> this.transport = transport }
    }

    private var transport: FakeTransport? = null

    // ---------- 样本 ----------

    private fun seriesJson(id: String, title: String, celebs: Boolean) = JSONObject()
        .put("series_id", id).put("series_name", title)
        .put("celebrities", if (celebs) JSONArray().put(JSONObject().put("nickname", "某演员")) else JSONArray())

    private fun searchPage(items: List<JSONObject>): String =
        """<script>_ROUTER_DATA=""" + JSONObject()
            .put("loaderData", JSONObject().put("search_(keyword)/page", JSONObject().put("searchList", JSONArray(items))))
            .toString() + """;</script>"""

    private fun planCard(id: String, title: String, count: Int, tag: String?) = JSONObject()
        .put("video_data", JSONObject().put("series_id", id).put("title", title).put("episode_cnt", count).put("cover", "https://img.example/c.jpg"))
        .put("tag_info", if (tag == null) JSONObject() else JSONObject().put("text", tag))

    private fun planResponse(cards: List<JSONObject>): String = JSONObject()
        .put("code", 0)
        .put("data", JSONArray().put(JSONObject().put("show_type", 547).put("video_data", JSONArray(cards))))
        .toString()

    private fun videoDetailResponse(code: Int = 0): String = JSONObject()
        .put("code", code)
        .put("data", JSONObject().put("video_data", JSONObject()
            .put("series_id", "7").put("series_title", "凡人测试书第三季").put("series_cover", "https://img.example/d.jpg")
            .put("series_intro", "简介").put("episode_cnt", 3)
            .put("video_list", JSONArray(listOf(
                JSONObject().put("vid", "100"),
                JSONObject().put("vid", "500"),
                JSONObject().put("vid", "900"),
                JSONObject().put("vid", "bad"),
            ))))).toString()

    private fun preloadResponse(): String =
        """{"Code":0,"data":{"123":{"video_model":{"fallback_api":"https://example.com/stream"}}}}"""

    private fun route(request: SignedRequest, search: String, plan: String = planResponse(emptyList()), detail: String = "", preload: String = preloadResponse()): String = when {
        request.url.contains("/search/") -> search
        request.url.contains("/reading/bookapi/plan/") -> plan
        request.url.contains("/reading/bookapi/search/suggest/") -> search
        request.url.contains("/novel/player/video_detail/") -> detail
        request.url.contains("/novel/player/multi_video_model/preload/") -> preload
        request.url.startsWith("https://example.com/") -> JSONObject()
            .put("video_info", JSONObject().put("data", JSONObject().put("video_list", JSONObject()
                .put("v0", JSONObject().put("vheight", 1080).put("codec_type", "h264").put("main_url", "https://example.com/a.mp4")))))
            .toString()
        else -> throw IOException("未预期的请求：${request.url}")
    }

    // ---------- 搜索 ----------

    @Test
    fun exactBaseSearchUsesServerSeasonLine() = runTest {
        val repo = repository { request ->
            route(
                request,
                search = searchPage(listOf(
                    seriesJson("9", "完全无关的剧", celebs = false),
                    seriesJson("1", "凡人测试书第二季", celebs = false),
                )),
                plan = planResponse(listOf(
                    planCard("3", "凡人测试书第八季", 80, "第8季"),
                    planCard("4", "凡人测试书第三季", 60, "第3季"),
                )),
            )
        }
        val out = repo.search("凡人测试书", ContentFilter.ALL)
        // 展示序：季线新→旧置顶（S8、S3），锚点 S2 随后；无关条目被滤
        assertEquals(listOf("3", "4", "1"), out.items.map { it.id })
        // seasonLine 未过滤、旧→新，锚点按标题季号并入
        assertEquals(listOf("1", "4", "3"), out.seasonLine.map { it.id })
        assertEquals("全 80 集", out.items.first().badge)
        assertEquals(8, out.items.first().season)
        assertEquals(listOf(2, 3, 8), out.seasonLine.map { it.season })
    }

    @Test
    fun seasonLineCardsInheritTheAnchorCategories() = runTest {
        // plan 卡片没有分类字段；官网搜索命中的锚点有。同一系列的各季共用分类，
        // 不传播的话搜索结果里只有锚点那张卡有分类（维护者实测"时有时无"）。
        val anchor = seriesJson("1", "凡人测试书第二季", celebs = false)
            .put("category_list", JSONArray().put(JSONObject().put("name", "古装")))
        val repo = repository { request ->
            route(
                request,
                search = searchPage(listOf(anchor)),
                plan = planResponse(listOf(
                    planCard("3", "凡人测试书第八季", 80, "第8季"),
                    planCard("4", "凡人测试书第三季", 60, "第3季"),
                )),
            )
        }
        val out = repo.search("凡人测试书", ContentFilter.ALL)
        assertEquals(listOf("3", "4", "1"), out.items.map { it.id })
        assertTrue(out.items.all { it.tags == "古装" })
    }

    @Test
    fun seasonLineCardsReadTheirOwnCategorySchema() = runTest {
        // 第三个形态：category_schema 是字符串形式的 JSON 数组（plan / 相关作品用它）。
        // 旧实现只读 tags / category_list，季线卡片因此永远没有分类。
        val withSchema = planCard("3", "凡人测试书第八季", 80, "第8季").apply {
            getJSONObject("video_data")
                .put("category_schema", """[{"category_id":316,"name":"古装"},{"category_id":317,"name":"逆袭"}]""")
        }
        val repo = repository { request ->
            route(
                request,
                search = searchPage(listOf(seriesJson("1", "凡人测试书第二季", celebs = false))),
                plan = planResponse(listOf(withSchema, planCard("4", "凡人测试书第三季", 60, "第3季"))),
            )
        }
        val out = repo.search("凡人测试书", ContentFilter.ALL)
        val season8 = out.items.first { it.id == "3" }
        assertEquals("古装 · 逆袭", season8.tags)
    }

    @Test
    fun searchRetriesWithTheNextAnchorWhenTheFirstHasNoRelatedCell() = runTest {
        // 正传常常不在「相关作品」里：第一条锚点拿不到季线时，换同系列的下一条候选再试。
        // 不重试的话，搜完整剧名只会得到官网那 10 条乱序结果（维护器实测"搜不到季"）。
        val repo = repository { request ->
            val firstAnchor = request.url.contains("book_id=1")
            route(
                request,
                search = searchPage(listOf(
                    seriesJson("1", "凡人测试书", celebs = false),
                    seriesJson("2", "凡人测试书分宗篇", celebs = false),
                )),
                plan = if (firstAnchor) planResponse(emptyList()) else planResponse(listOf(
                    planCard("3", "凡人测试书第八季", 80, "第8季"),
                )),
            )
        }
        val out = repo.search("凡人测试书", ContentFilter.ALL)
        assertEquals(2, out.seasonLine.size)
        // 季线（第 8 季 + 第 1 季，新→旧）在前，其余官网条目在后
        assertEquals(listOf("3", "2", "1"), out.items.map { it.id })
    }

    @Test
    fun planFailureFallsBackToWebsiteOrder() = runTest {
        val repo = repository { request ->
            route(request, search = searchPage(listOf(
                seriesJson("9", "完全无关的剧", celebs = false),
                seriesJson("1", "凡人测试书第二季", celebs = false),
            )), plan = "")
        }
        val out = repo.search("凡人测试书", ContentFilter.ALL)
        assertEquals(listOf("9", "1"), out.items.map { it.id })
        assertTrue(out.seasonLine.isEmpty())
    }

    @Test
    fun nonBaseSearchKeepsOfficialOrderAndSkipsPlan() = runTest {
        val repo = repository { request ->
            route(request, search = searchPage(listOf(
                seriesJson("9", "完全无关的剧", celebs = false),
                seriesJson("8", "凡人测试书第八季", celebs = false),
            )))
        }
        val out = repo.search("测试书", ContentFilter.ALL)
        assertEquals(listOf("9", "8"), out.items.map { it.id })
        assertTrue(out.seasonLine.isEmpty())
        // 搜索词不是任何 base：不应请求 plan
        assertTrue(transport!!.urls.none { it.contains("/reading/bookapi/plan/") })
    }

    @Test
    fun filterAppliesToLineAndRest() = runTest {
        val repo = repository { request ->
            route(request, search = searchPage(listOf(
                seriesJson("1", "漫剧甲", celebs = false),
                seriesJson("2", "真人乙", celebs = true),
            )))
        }
        assertEquals(listOf("1"), repo.search("漫剧甲", ContentFilter.COMIC).items.map { it.id })
        assertEquals(listOf("2"), repo.search("漫剧甲", ContentFilter.SHORT).items.map { it.id })
        assertEquals(2, repo.search("漫剧甲", ContentFilter.ALL).items.size)
    }

    @Test
    fun siteSearchUrlIsEncodedAndThrottledByTheTable() = runTest {
        val repo = repository { request ->
            route(request, search = searchPage(listOf(seriesJson("1", "凡人测试书", celebs = false))))
        }
        repo.search("凡人", ContentFilter.ALL)
        val first = transport!!.urls.first()
        // URL 形状由端点表决定：path 占位符 + page=1，keyword 已编码
        assertTrue(first.startsWith("https://hongguoduanju.com/search/%E5%87%A1%E4%BA%BA?page=1"))
    }

    // ---------- 联想 ----------

    @Test
    fun suggestParsesVideoCardsAndSkipsPlainWords() = runTest {
        val repo = repository { request ->
            when {
                request.url.contains("/reading/bookapi/search/suggest/") -> JSONObject()
                    .put("code", 0)
                    .put("data", JSONObject().put("query_result_v2", JSONArray(listOf(
                        JSONObject().put("name", "凡人测试书").put("sug_abstract", "剧情·100万热度")
                            .put("video_data", JSONObject().put("series_id", "1").put("cover", "https://img.example/a.jpg")),
                        JSONObject().put("name", "凡人测试书第二季"),
                        JSONObject().put("name", "杂项").put("video_data", JSONObject().put("series_id", "abc")),
                    )))).toString()
                else -> throw IOException("不应请求其它端点")
            }
        }
        val list = repo.suggest("凡人")
        assertEquals(1, list.size)
        assertEquals("1", list[0].target?.id)
        assertEquals("剧情·100万热度", list[0].target?.badge)
    }

    @Test
    fun suggestFailureDegradesToEmptyList() = runTest {
        val repo = repository { throw IOException("offline") }
        assertTrue(repo.suggest("凡人").isEmpty())
    }

    // ---------- 详情 ----------

    @Test
    fun detailParsesEpisodesInServerOrder() = runTest {
        val repo = repository { request ->
            route(request, search = "", detail = videoDetailResponse())
        }
        val d = repo.detail("7")
        assertEquals("凡人测试书第三季", d.series.title)
        assertEquals(listOf("100", "500", "900"), d.episodes.map { it.vid })
        assertEquals(listOf(0, 1, 2), d.episodes.map { it.index })
        assertEquals("全 3 集", d.series.badge)
        assertEquals(3, d.series.season)
    }

    @Test
    fun detailApiErrorBecomesRiskControl() = runTest {
        val repo = repository { request ->
            route(request, search = "", detail = videoDetailResponse(code = 110001))
        }
        val e = assertFailsWith<UpstreamError.RiskControl> { repo.detail("7") }
        assertEquals(110001, e.code)
    }

    @Test
    fun detailRejectsNonNumericId() = runTest {
        val repo = repository { "" }
        assertFailsWith<IllegalArgumentException> { repo.detail("abc") }
    }

    // ---------- 取流 ----------

    @Test
    fun streamSelectsCompatibleQualityAndReportsFallback() = runTest {
        val repo = repository { request ->
            when {
                request.url.contains("/novel/player/multi_video_model/preload/") ->
                    """{"Code":0,"data":{"123":{"video_model":{"fallback_api":"https://example.com/stream"}}}}"""
                request.url.startsWith("https://example.com/") -> JSONObject()
                    .put("video_info", JSONObject().put("data", JSONObject().put("video_list", JSONObject()
                        .put("v0", JSONObject().put("vheight", 720).put("codec_type", "bytevc2").put("main_url", "https://example.com/bad"))
                        .put("v1", JSONObject().put("vheight", 1080).put("codec_type", "bytevc1").put("main_url", "https://example.com/hevc")))))
                    .toString()
                else -> throw IOException("未预期的请求：${request.url}")
            }
        }
        val info = repo.stream("123", 720)
        // 唯一兼容档 1080 超出请求上限：仍选它，但 fellBack 必须如实报告
        assertEquals("https://example.com/hevc", info.url)
        assertEquals(1080, info.height)
        assertTrue(info.fellBack)
    }

    @Test
    fun streamPrefersCompatibleWithinLimit() = runTest {
        val repo = repository { request ->
            when {
                request.url.contains("/novel/player/multi_video_model/preload/") ->
                    """{"Code":0,"data":{"123":{"video_model":{"fallback_api":"https://example.com/stream"}}}}"""
                request.url.startsWith("https://example.com/") -> JSONObject()
                    .put("video_info", JSONObject().put("data", JSONObject().put("video_list", JSONObject()
                        .put("v0", JSONObject().put("vheight", 720).put("codec_type", "bytevc2").put("main_url", "https://example.com/bad"))
                        .put("v1", JSONObject().put("vheight", 480).put("codec_type", "h264").put("main_url", "https://example.com/avc"))
                        .put("v2", JSONObject().put("vheight", 1080).put("codec_type", "bytevc1").put("main_url", "https://example.com/hevc")))))
                    .toString()
                else -> throw IOException("未预期的请求：${request.url}")
            }
        }
        val info = repo.stream("123", 720)
        assertEquals("https://example.com/avc", info.url)
        assertEquals(480, info.height)
        assertFalse(info.fellBack)
    }

    @Test
    fun entirelyIncompatibleSourceFailsWithShape() = runTest {
        val repo = repository { request ->
            when {
                request.url.contains("/novel/player/multi_video_model/preload/") ->
                    """{"Code":0,"data":{"123":{"video_model":{"fallback_api":"https://example.com/stream"}}}}"""
                request.url.startsWith("https://example.com/") -> JSONObject()
                    .put("video_info", JSONObject().put("data", JSONObject().put("video_list", JSONObject()
                        .put("v0", JSONObject().put("vheight", 720).put("codec_type", "ByteVC2").put("main_url", "https://example.com/bad")))))
                    .toString()
                else -> throw IOException("未预期的请求：${request.url}")
            }
        }
        assertFailsWith<UpstreamError.Shape> { repo.stream("123", 720) }
    }

    @Test
    fun streamGoesThroughPreloadEndpointWithRandomStreamIdentity(): Unit = runTest {
        val repo = repository { request ->
            route(request, search = "")
        }
        repo.stream("123", 1080)
        val first = transport!!.urls.first()
        assertTrue(first.startsWith("https://api5-normal-sinfonlineb.fqnovel.com/novel/player/multi_video_model/preload/v1/"))
        // 取流身份必须是随机十九位，不能混入内容端点的 71332 常量
        val deviceId = Regex("device_id=(\\d{19})").find(first)?.groupValues?.get(1)
        assertTrue("device_id 应是随机十九位：$first", deviceId != null && deviceId != "1905892595378490")
    }

    @Test
    fun streamRiskControlCodeBecomesRiskControl() = runTest {
        val repo = repository { """{"Code":9,"data":{}}""" }
        val e = assertFailsWith<UpstreamError.RiskControl> { repo.stream("123", 1080) }
        assertEquals(9, e.code)
    }
}
