package com.hongguotv.lite.data.content

import com.hongguotv.core.model.ContentFilter
import com.hongguotv.core.model.Series
import com.hongguotv.core.port.UpstreamError
import com.hongguotv.core.protocol.DeviceIdentity
import com.hongguotv.core.protocol.DeviceProfile
import com.hongguotv.core.protocol.Endpoints
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
 * "端点选得对、映射串得对、失败分类落得对"。
 *
 * 2026-10-24 起上游换了两条路：搜索走官网 `/api/search/result`（服务端分页，一页 10 条），
 * 取流走 `/novel/player/video_model/v1/`（`video_id` 单值）。后者要求设备身份不在黑名单里，
 * 所以这里的每个请求都带同一对注入的匿名身份。
 */
class ContentRepositoryTest {

    private class FakeTransport(val respond: (SignedRequest) -> String) : HttpTransport {
        val urls = mutableListOf<String>()
        val methods = mutableListOf<HttpMethod>()
        override suspend fun text(request: SignedRequest): String {
            urls += request.url
            methods += request.method
            return respond(request)
        }
    }

    private val identity = DeviceIdentity("7123456789012345678", "8123456789012345678")

    private fun repository(respond: (SignedRequest) -> String): ContentRepository {
        val transport = FakeTransport(respond)
        return ContentRepository(
            http = transport,
            devices = FixedDeviceIdentity(DeviceSnapshot(identity, DeviceProfile.DEFAULT)),
        ).also { repo -> this.transport = transport }
    }

    private var transport: FakeTransport? = null

    // ---------- 样本 ----------

    private fun seriesJson(id: String, title: String, celebs: Boolean) = JSONObject()
        .put("series_id", id).put("series_name", title)
        .put("celebrities", if (celebs) JSONArray().put(JSONObject().put("nickname", "某演员")) else JSONArray())

    private fun searchBody(
        items: List<JSONObject>,
        page: Int = 1,
        pages: Int = 1,
        total: Int = items.size,
        hasMore: Boolean = false,
    ): String = JSONObject()
        .put("searchList", JSONArray(items))
        .put("pageNum", page)
        .put("totalPages", pages)
        .put("totalCount", total.toString())
        .put("hasMore", hasMore)
        .toString()

    private fun suggestBody(rows: List<JSONObject>): String = JSONObject()
        .put("suggest_list", JSONArray(rows))
        .toString()

    private fun planCard(id: String, title: String, count: Int, tag: String?, seasonIndex: Int = 0) = JSONObject()
        .put(
            "video_data",
            JSONObject().put("series_id", id).put("title", title).put("episode_cnt", count)
                .put("cover", "https://img.example/c.jpg").put("season_index", seasonIndex),
        )
        .put("tag_info", if (tag == null) JSONObject() else JSONObject().put("text", tag))

    private fun planResponse(cards: List<JSONObject>): String = JSONObject()
        .put("code", 0)
        .put("data", JSONArray().put(JSONObject().put("show_type", 547).put("video_data", JSONArray(cards))))
        .toString()

    private fun videoDetailResponse(
        code: Int = 0,
        seasonIndex: Int = 3,
        albumId: String = "7655951352136207384",
        id: String = "7",
    ): String = JSONObject()
        .put("code", code)
        .put("data", JSONObject().put("video_data", JSONObject()
            .put("series_id", id).put("series_title", "凡人测试书第三季").put("series_cover", "https://img.example/d.jpg")
            .put("series_intro", "简介").put("episode_cnt", 3)
            .put("season_index", seasonIndex).put("related_album_id", albumId)
            .put("video_list", JSONArray(listOf(
                JSONObject().put("vid", "100"),
                JSONObject().put("vid", "500"),
                JSONObject().put("vid", "900"),
                JSONObject().put("vid", "bad"),
            ))))).toString()

    private fun videoModelResponse(fallback: String = "https://example.com/stream"): String = JSONObject()
        .put("code", 0)
        .put("data", JSONObject().put("video_model", JSONObject()
            .put("status", 10)
            .put("fallback_api", fallback).toString()))
        .toString()

    private fun streamInfo(videoList: JSONObject): String = JSONObject()
        .put("video_info", JSONObject().put("data", JSONObject().put("video_list", videoList)))
        .toString()

    private fun route(
        request: SignedRequest,
        search: String,
        plan: String = planResponse(emptyList()),
        detail: String = "",
        suggest: String = suggestBody(emptyList()),
        model: String = videoModelResponse(),
    ): String = when {
        request.url.contains("/api/search/result") -> search
        request.url.contains("/incent_resource/suggestion") -> suggest
        request.url.contains("/reading/bookapi/plan/") -> plan
        request.url.contains("/novel/player/video_detail/") -> detail
        request.url.contains("/novel/player/video_model/") -> model
        request.url.startsWith("https://example.com/") -> streamInfo(JSONObject()
            .put("v0", JSONObject().put("vheight", 1080).put("codec_type", "h264").put("main_url", "https://example.com/a.mp4")))
        else -> throw IOException("未预期的请求：${request.url}")
    }

    // ---------- 搜索 ----------

    @Test
    fun searchReturnsOnePageWithUpstreamPaging() = runTest {
        val repo = repository { request ->
            route(request, search = searchBody(
                items = listOf(
                    seriesJson("1", "万妖图录传第一季", celebs = false),
                    seriesJson("2", "万妖图录传第二季", celebs = false),
                ),
                page = 1,
                pages = 21,
                total = 204,
                hasMore = true,
            ))
        }
        val out = repo.search("万妖图录传", ContentFilter.ALL)
        assertEquals(listOf("1", "2"), out.items.map { it.id })
        assertEquals(1, out.page)
        assertEquals(21, out.totalPages)
        assertEquals(204, out.totalCount)
        assertTrue(out.hasMore)
    }

    @Test
    fun searchAsksForTheRequestedPage() = runTest {
        val repo = repository { request ->
            route(request, search = searchBody(listOf(seriesJson("1", "某剧第二季", celebs = false)), page = 3))
        }
        val out = repo.search("某剧", ContentFilter.ALL, page = 3)
        assertEquals(3, out.page)
        val url = transport!!.urls.first()
        assertTrue("第 3 页要带 page=3：$url", url.contains("page=3"))
        // keyword 由 PlainSigner 统一编码，调用方不预编码
        assertTrue("关键词必须编码进 query：$url", url.contains("query=%E6%9F%90%E5%89%A7"))
    }

    @Test
    fun searchFiltersByMedia() = runTest {
        val repo = repository { request ->
            route(request, search = searchBody(listOf(
                seriesJson("1", "漫剧甲", celebs = false),
                seriesJson("2", "真人乙", celebs = true),
            )))
        }
        assertEquals(listOf("1"), repo.search("漫剧甲", ContentFilter.COMIC).items.map { it.id })
        assertEquals(listOf("2"), repo.search("漫剧甲", ContentFilter.SHORT).items.map { it.id })
        assertEquals(2, repo.search("漫剧甲", ContentFilter.ALL).items.size)
    }

    @Test
    fun emptySearchListIsAnEmptyResultNotAnError() = runTest {
        val repo = repository { request -> route(request, search = searchBody(emptyList())) }
        val out = repo.search("不存在的剧", ContentFilter.ALL)
        assertTrue(out.items.isEmpty())
        assertEquals(1, out.totalPages)
    }

    @Test
    fun searchUrlShapeIsOwnedByTheEndpointTable() = runTest {
        val repo = repository { request ->
            route(request, search = searchBody(listOf(seriesJson("1", "凡人测试书", celebs = false))))
        }
        repo.search("凡人", ContentFilter.ALL)
        val first = transport!!.urls.first()
        assertTrue("官网搜索接口：$first", first.startsWith("https://hongguoduanju.com/api/search/result?"))
        assertTrue(first.contains("aid=8662"))
        assertTrue(first.contains("page=1"))
    }

    @Test
    fun searchRejectsAnInvalidPage() = runTest {
        val repo = repository { request -> route(request, search = searchBody(emptyList())) }
        assertFailsWith<IllegalArgumentException> { repo.search("凡人", ContentFilter.ALL, page = 0) }
    }

    // ---------- 联想 ----------

    @Test
    fun suggestReadsTheWebsiteSuggestList() = runTest {
        val repo = repository { request ->
            when {
                request.url.contains("/incent_resource/suggestion") -> suggestBody(listOf(
                    JSONObject().put("name", "凡人测试书").put("keyword", "凡人测试书")
                        .put("video_data", JSONObject().put("series_id", "1").put("series_cover", "https://img.example/a.jpg")),
                    JSONObject().put("name", "凡人修仙传").put("keyword", "凡人修仙传").put("video_data", JSONObject()),
                ))
                else -> throw IOException("不应请求其它端点")
            }
        }
        val list = repo.suggest("凡人")
        assertEquals(listOf("凡人测试书", "凡人修仙传"), list.map { it.keyword })
        assertEquals("1", list[0].target?.id)
        assertTrue("空 video_data 只能当查询词", list[1].target == null)
        assertTrue(transport!!.urls.first().contains("app_id=8662"))
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

    /**
     * 详情集数要落到 [Series.episodeCount]。
     *
     * 收藏详情的「更新」行靠它；为 0 时会退回**当前季**的集数，写成
     * 「更新到 第九季第90集」这种季号对、集数错的文案。
     */
    @Test
    fun detailCarriesEpisodeCountForFavoriteUpdates() = runTest {
        val repo = repository { request ->
            route(request, search = "", detail = videoDetailResponse())
        }
        val d = repo.detail("7")
        assertEquals("详情集数必须带出来，不能只写在 badge 里", 3, d.series.episodeCount)
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

    // ---------- 季线 ----------

    @Test
    fun seasonsMergesTheAnchorAndSortsOldToNew() = runTest {
        val repo = repository { request ->
            route(request, search = "", plan = planResponse(listOf(
                planCard("3", "凡人测试书第八季", 80, "第8季"),
                planCard("4", "凡人测试书第三季", 60, "第3季"),
            )))
        }
        val anchor = Series(id = "1", title = "凡人测试书第二季", season = 2, albumId = "album-1")
        val line = repo.seasons(anchor.id, anchor)
        assertEquals(listOf("1", "4", "3"), line.seasons.map { it.id })
        assertEquals(listOf(2, 3, 8), line.seasons.map { it.season })
    }

    /**
     * 搜索聚合把**搜索卡**当锚点：卡上没有 `albumId`，季号只从标题来。
     * plan 的相关作品又不含锚点本尊——不补一次详情，聚合结果就会丢掉锚点那一季
     * （实测魏家缺 S9、万妖缺 S1、聚宝缺 S14）。
     */
    @Test
    fun seasonsResolvesASearchCardAnchorThroughDetail() = runTest {
        val repo = repository { request ->
            when {
                request.url.contains("/reading/bookapi/plan/") -> planResponse(listOf(
                    planCard("2", "魏家测试书折桂篇", 64, "第2季", seasonIndex = 2),
                    planCard("3", "魏家测试书第三季", 65, "第3季", seasonIndex = 3),
                ))
                request.url.contains("/novel/player/video_detail/") ->
                    videoDetailResponse(id = "9", seasonIndex = 9, albumId = "album-1")
                else -> ""
            }
        }
        val searchCard = Series(id = "9", title = "魏家测试书第九季", season = 9, albumId = "")
        val line = repo.seasons(searchCard.id, searchCard)
        assertEquals(listOf("2", "3", "9"), line.seasons.map { it.id })
        assertEquals(listOf(2, 3, 9), line.seasons.map { it.season })
        assertTrue(
            "锚点不在 plan 里时必须问一次详情：${transport!!.urls}",
            transport!!.urls.any { it.contains("/novel/player/video_detail/") },
        )
    }

    /** 篇名季（分宗篇）在搜索卡上季号是 0：同样要靠详情补出 season_index 与 album。 */
    @Test
    fun seasonsResolvesANoSeasonSearchCardThroughDetail() = runTest {
        val repo = repository { request ->
            when {
                request.url.contains("/reading/bookapi/plan/") -> planResponse(listOf(
                    planCard("2", "魏家测试书折桂篇", 64, "第2季", seasonIndex = 2),
                ))
                request.url.contains("/novel/player/video_detail/") ->
                    videoDetailResponse(id = "1", seasonIndex = 1, albumId = "album-1")
                else -> ""
            }
        }
        val searchCard = Series(id = "1", title = "魏家测试书分宗篇", season = 0, albumId = "")
        val line = repo.seasons(searchCard.id, searchCard)
        assertEquals(listOf("1", "2"), line.seasons.map { it.id })
        assertEquals(listOf(1, 2), line.seasons.map { it.season })
    }

    @Test
    fun seasonsRequiresANumericId() = runTest {
        val repo = repository { "" }
        assertFailsWith<IllegalArgumentException> { repo.seasons("abc", null) }
    }

    /**
     * 2026-10-24 验收（第二次）：锚点本尊不在相关作品里时，靠**详情**给的季号并入。
     *
     * 分宗篇的详情响应有 `season_index=1` 与 `related_album_id`，它就是第 1 季；
     * 只按标题解析会得到 0（标题「魏家逆子成首辅分宗篇」没有「第N季」）。
     */
    @Test
    fun seasonsMergesTheAnchorUsingTheSeasonIndexFromDetail() = runTest {
        val repo = repository { request ->
            route(request, search = "", plan = planResponse(listOf(
                planCard("2", "魏家测试书折桂篇", 64, "第2季", seasonIndex = 2),
                planCard("3", "魏家测试书第三季", 65, "第3季", seasonIndex = 3),
            )))
        }
        val anchor = Series(id = "1", title = "魏家测试书分宗篇", season = 1, albumId = "7655951352136207384")
        val line = repo.seasons(anchor.id, anchor)
        assertEquals(listOf("1", "2", "3"), line.seasons.map { it.id })
        assertEquals(listOf(1, 2, 3), line.seasons.map { it.season })
    }

    /**
     * 同 IP 正传（详情里 `related_album_id` 为空、季号为 0）**不是**季线的第 1 季。
     * 把它并进来会顶掉真正的第 1 季（分宗篇）——维护者点名的就是这个坑。
     */
    @Test
    fun seasonsLeavesASameIpAnchorOutOfTheLine() = runTest {
        val repo = repository { request ->
            route(request, search = "", plan = planResponse(listOf(
                planCard("2", "魏家测试书折桂篇", 64, "第2季", seasonIndex = 2),
                planCard("3", "魏家测试书第三季", 65, "第3季", seasonIndex = 3),
            )))
        }
        val anchor = Series(id = "9", title = "魏家测试书", season = 0, albumId = "")
        val line = repo.seasons(anchor.id, anchor)
        assertEquals(listOf("2", "3"), line.seasons.map { it.id })
        assertEquals(listOf(2, 3), line.seasons.map { it.season })
    }

    /** 详情响应的 `season_index` / `related_album_id` 要落到模型上：季线并入靠它们判断。 */
    @Test
    fun detailReadsSeasonIndexAndAlbumId() = runTest {
        val repo = repository { request ->
            route(request, search = "", detail = videoDetailResponse(seasonIndex = 5, albumId = "album-42"))
        }
        val detail = repo.detail("7")
        assertEquals("详情给了季号时以它为准（标题只是第二来源）", 5, detail.series.season)
        assertEquals("album-42", detail.series.albumId)
    }

    /** 同 IP 正传的详情没有 `related_album_id`：模型据此知道自己不在任何季线里。 */
    @Test
    fun detailKeepsAnEmptyAlbumForSameIpWorks() = runTest {
        val repo = repository { request ->
            route(request, search = "", detail = videoDetailResponse(seasonIndex = 0, albumId = ""))
        }
        val detail = repo.detail("7")
        assertEquals("", detail.series.albumId)
        assertEquals("季号 0 时退回标题里的季号", 3, detail.series.season)
    }

    /**
     * 从同 IP 正传进系列页时，相关作品里只有第 2 季起——分宗篇（真正的第 1 季）不在卡片里。
     * 兜底：按基名搜一次，取"标题只比基名长几个字"的同名篇，用详情确认它是第 1 季。
     */
    @Test
    fun seasonsBackfillsTheFirstSeasonWhenTheAnchorIsSameIp() = runTest {
        val repo = repository { request ->
            when {
                request.url.contains("/reading/bookapi/plan/") -> planResponse(listOf(
                    planCard("2", "魏家测试书折桂篇", 64, "第2季", seasonIndex = 2),
                ))
                request.url.contains("/api/search/result") -> searchBody(listOf(
                    JSONObject().put("series_id", "9").put("series_name", "魏家测试书")
                        .put("episode_cnt", 56).put("celebrities", JSONArray()),
                    JSONObject().put("series_id", "1").put("series_name", "魏家测试书分宗篇")
                        .put("episode_cnt", 90).put("celebrities", JSONArray()),
                ))
                request.url.contains("/novel/player/video_detail/") -> videoDetailResponse(id = "1", seasonIndex = 1)
                else -> ""
            }
        }
        val anchor = Series(id = "9", title = "魏家测试书", season = 0, albumId = "")
        val line = repo.seasons(anchor.id, anchor)
        assertEquals(listOf("1", "2"), line.seasons.map { it.id })
        assertEquals(listOf(1, 2), line.seasons.map { it.season })
    }

    /** 候选的详情不是第 1 季（或不属于专辑）时不补：宁可少一季，也不能把别的剧塞进来。 */
    @Test
    fun seasonsDoesNotBackfillAFirstSeasonThatIsNotOne() = runTest {
        val repo = repository { request ->
            when {
                request.url.contains("/reading/bookapi/plan/") -> planResponse(listOf(
                    planCard("2", "魏家测试书折桂篇", 64, "第2季", seasonIndex = 2),
                ))
                request.url.contains("/api/search/result") -> searchBody(listOf(
                    JSONObject().put("series_id", "1").put("series_name", "魏家测试书分宗篇")
                        .put("episode_cnt", 90).put("celebrities", JSONArray()),
                ))
                request.url.contains("/novel/player/video_detail/") -> videoDetailResponse(id = "1", seasonIndex = 3)
                else -> ""
            }
        }
        val anchor = Series(id = "9", title = "魏家测试书", season = 0, albumId = "")
        val line = repo.seasons(anchor.id, anchor)
        assertEquals(listOf("2"), line.seasons.map { it.id })
    }

    // ---------- 取流 ----------

    @Test
    fun streamSelectsCompatibleQualityAndReportsFallback() = runTest {
        val repo = repository { request ->
            when {
                request.url.contains("/novel/player/video_model/") -> videoModelResponse()
                request.url.startsWith("https://example.com/") -> streamInfo(JSONObject()
                    .put("v0", JSONObject().put("vheight", 720).put("codec_type", "bytevc2").put("main_url", "https://example.com/bad"))
                    .put("v1", JSONObject().put("vheight", 1080).put("codec_type", "bytevc1").put("main_url", "https://example.com/hevc")))
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
                request.url.contains("/novel/player/video_model/") -> videoModelResponse()
                request.url.startsWith("https://example.com/") -> streamInfo(JSONObject()
                    .put("v0", JSONObject().put("vheight", 720).put("codec_type", "bytevc2").put("main_url", "https://example.com/bad"))
                    .put("v1", JSONObject().put("vheight", 480).put("codec_type", "h264").put("main_url", "https://example.com/avc"))
                    .put("v2", JSONObject().put("vheight", 1080).put("codec_type", "bytevc1").put("main_url", "https://example.com/hevc")))
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
                request.url.contains("/novel/player/video_model/") -> videoModelResponse()
                request.url.startsWith("https://example.com/") -> streamInfo(JSONObject()
                    .put("v0", JSONObject().put("vheight", 720).put("codec_type", "ByteVC2").put("main_url", "https://example.com/bad")))
                else -> throw IOException("未预期的请求：${request.url}")
            }
        }
        assertFailsWith<UpstreamError.Shape> { repo.stream("123", 720) }
    }

    @Test
    fun streamUsesTheSingleVideoIdRouteWithTheInjectedIdentity() = runTest {
        val repo = repository { request -> route(request, search = "") }
        repo.stream("123", 1080)
        val first = transport!!.urls.first()
        assertTrue(first.startsWith("https://api5-normal-sinfonlineb.fqnovel.com/novel/player/video_model/v1/"))
        // 身份是注入的那一对；内置的固定值已被平台拉黑
        assertTrue("必须带注入的 device_id：$first", first.contains("device_id=${identity.deviceId}"))
        assertFalse("不能带内置身份：$first", first.contains(DeviceIdentity.LEGACY.deviceId))
    }

    @Test
    fun streamRequestCarriesTheSingleVideoIdBody() {
        // FakeTransport 不暴露 body；这里用端点表直接验形状（body 只认 video_id 单值）
        val body = String(Endpoints["videoModel"].resolvedBody(mapOf("vid" to "123"))!!)
        assertTrue(body.contains(""""video_id":"123""""))
        assertFalse(body.contains("mixed_video_id_map"))
    }

    @Test
    fun streamRiskControlCodeBecomesRiskControl() = runTest {
        val repo = repository { """{"Code":9,"data":{}}""" }
        val e = assertFailsWith<UpstreamError.RiskControl> { repo.stream("123", 1080) }
        assertEquals(9, e.code)
    }

    /**
     * 风控特征（200 空体）→ 换一台设备重试一次。
     *
     * 这是"一台设备只注册一次，封了才换"的落地点：不换的话，被拉黑的设备会一直空体，
     * 换得太勤又会把设备数刷起来——所以只重试一次，冷却在 DeviceSession 里。
     */
    @Test
    fun riskControlRenewsTheDeviceAndRetriesOnce() = runTest {
        var detailCalls = 0
        val renewals = mutableListOf<String>()
        val transport = FakeTransport { request ->
            if (request.url.contains("/novel/player/video_detail/")) {
                detailCalls++
                if (detailCalls == 1) throw UpstreamError.EmptyBody()
            }
            route(request, search = "", detail = videoDetailResponse())
        }
        var current = DeviceSnapshot(identity, DeviceProfile.DEFAULT)
        val devices = object : DeviceIdentitySource {
            override fun snapshot(): DeviceSnapshot = current
            override suspend fun ensure(): DeviceSnapshot = current
            override suspend fun renew(reason: String): DeviceSnapshot {
                renewals += reason
                current = DeviceSnapshot(
                    identity = DeviceIdentity("7111111111111111111", "8111111111111111111"),
                    profile = DeviceProfile.of(1),
                    registeredAt = current.registeredAt,
                    renewedAt = 1L,
                )
                return current
            }
        }
        val repo = ContentRepository(transport, devices)
        val detail = repo.detail("7")

        assertEquals("重试后要正常返回", 3, detail.episodes.size)
        assertEquals("只重试一次", 2, detailCalls)
        assertEquals(listOf("EmptyBody"), renewals)
        assertTrue(
            "重试必须用新身份签名：${transport.urls.last()}",
            transport.urls.last().contains("device_id=7111111111111111111"),
        )
    }

    @Test
    fun nonRiskFailuresDoNotReRegister() = runTest {
        var renewals = 0
        val devices = object : DeviceIdentitySource {
            override fun snapshot(): DeviceSnapshot = DeviceSnapshot(identity, DeviceProfile.DEFAULT)
            override suspend fun ensure(): DeviceSnapshot = snapshot()
            override suspend fun renew(reason: String): DeviceSnapshot? {
                renewals++
                return null
            }
        }
        val repo = ContentRepository(FakeTransport { """{"code":0,"data":{}}""" }, devices)
        assertFailsWith<UpstreamError.Shape> { repo.detail("7") }
        assertEquals("结构错误不是风控，不要换设备", 0, renewals)
    }
}
