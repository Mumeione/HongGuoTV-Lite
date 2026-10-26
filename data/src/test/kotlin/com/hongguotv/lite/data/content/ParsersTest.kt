package com.hongguotv.lite.data.content

import com.hongguotv.core.model.ContentFilter
import com.hongguotv.core.model.Series
import com.hongguotv.core.port.UpstreamError
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 上游 JSON → 模型的映射规则，样本结构与旧仓库测试一致（行为基准）。 */
class ParsersTest {

    // ---------- 搜索卡片 ----------

    private fun cardJson(id: String, title: String, celebs: Boolean) = JSONObject()
        .put("series_id", id).put("series_name", title)
        .put("celebrities", if (celebs) JSONArray().put(JSONObject().put("nickname", "某演员")) else JSONArray())

    @Test
    fun celebritiesEmptyMeansComic() {
        assertTrue(Parsers.card(cardJson("1", "漫剧甲", celebs = false)).isComic)
        assertFalse(Parsers.card(cardJson("2", "真人乙", celebs = true)).isComic)
    }

    @Test
    fun validDropsDirtyRowsAndDedupes() {
        val rows = listOf(
            Card(Series("1", "剧一"), true),
            Card(Series("abc", "非数字 id"), true),
            Card(Series("2", ""), true),
            Card(Series("1", "剧一重复"), true),
            Card(Series("3", "剧三"), true),
        )
        assertEquals(listOf("1", "3"), Parsers.valid(rows).map { it.series.id })
    }

    @Test
    fun filterTypeSplitsByMedia() {
        val rows = listOf(
            Card(Series("1", "漫剧甲"), true),
            Card(Series("2", "真人乙"), false),
            Card(Series("3", "漫剧丙"), true),
        )
        assertEquals(listOf("1", "3"), Parsers.filterType(rows, ContentFilter.COMIC).map { it.series.id })
        assertEquals(listOf("2"), Parsers.filterType(rows, ContentFilter.SHORT).map { it.series.id })
        assertEquals(3, Parsers.filterType(rows, ContentFilter.ALL).size)
    }

    // ---------- 官网搜索接口 ----------

    private fun searchBody(items: List<JSONObject>, page: Int = 1, pages: Int = 1, total: Int = items.size, hasMore: Boolean = false): String =
        JSONObject()
            .put("searchList", JSONArray(items))
            .put("pageNum", page)
            .put("totalPages", pages)
            .put("totalCount", total.toString())
            .put("hasMore", hasMore)
            .toString()

    @Test
    fun siteSearchPageReadsCardsAndPaging() {
        val page = Parsers.siteSearchPage(searchBody(
            items = listOf(cardJson("1", "万妖图录传第一季", celebs = false), cardJson("2", "万妖图录传第二季", celebs = false)),
            page = 2,
            pages = 21,
            total = 204,
            hasMore = true,
        ))
        assertEquals(listOf("1", "2"), page.cards.map { it.series.id })
        assertEquals(2, page.page)
        assertEquals(21, page.totalPages)
        assertEquals(204, page.totalCount)
        assertTrue(page.hasMore)
    }

    @Test
    fun siteSearchPageAcceptsNumericCountersAsWellAsStrings() {
        val body = JSONObject()
            .put("searchList", JSONArray())
            .put("pageNum", 3)
            .put("totalPages", 5)
            .put("totalCount", 42)
            .toString()
        val page = Parsers.siteSearchPage(body)
        assertEquals(3, page.page)
        assertEquals(5, page.totalPages)
        assertEquals(42, page.totalCount)
    }

    @Test
    fun siteSearchPageRequiresTheSearchList() {
        assertThrows(UpstreamError.Shape::class.java) { Parsers.siteSearchPage("""{"totalPages":1}""") }
    }

    @Test
    fun siteSearchPageRejectsANonJsonBody() {
        // 上游把接口换回 HTML 是"结构变了"，不是"没有结果"
        assertThrows(UpstreamError.Shape::class.java) { Parsers.siteSearchPage("<!doctype html><html></html>") }
    }

    // ---------- 官网联想词 ----------

    @Test
    fun suggestListKeepsSeriesCardsAndPlainWords() {
        val body = JSONObject().put("suggest_list", JSONArray(listOf(
            JSONObject().put("name", "万妖图录传第一季").put("keyword", "7621200213801176126")
                .put("video_data", JSONObject().put("series_id", "7621200213801176126")
                    .put("series_cover", "https://img.example/a.jpg").put("episode_right_text", "全81集")),
            JSONObject().put("name", "万妖图录").put("keyword", "万妖图录")
                .put("video_data", JSONObject()),
        ))).toString()
        val list = Parsers.suggestList(body)
        assertEquals(listOf("万妖图录传第一季", "万妖图录"), list.map { it.keyword })
        assertEquals("7621200213801176126", list[0].target?.id)
        assertEquals("全81集", list[0].target?.badge)
        assertTrue("空 video_data 只能当查询词", list[1].target == null)
    }

    // ---------- plan 季线 ----------

    private fun planCard(id: String, title: String, count: Int, tag: String?, seasonIndex: Int = 0) = JSONObject()
        .put(
            "video_data",
            JSONObject().put("series_id", id).put("title", title).put("episode_cnt", count)
                .put("cover", "https://img.example/c.jpg").put("season_index", seasonIndex),
        )
        .put("tag_info", if (tag == null) JSONObject() else JSONObject().put("text", tag))

    private fun planResponse(cards: List<JSONObject>): JSONArray = JSONArray()
        .put(JSONObject().put("show_type", 547).put("cell_name", "相关作品").put("video_data", JSONArray(cards)))

    @Test
    fun planCellCollectsTaggedSeasonsAndDropsSameIp() {
        val seasons = Parsers.planCell(planResponse(listOf(
            planCard("3", "测试书第八季", 80, "第8季"),
            planCard("4", "测试书第五季", 50, "第五季"),
            planCard("9", "同 IP 无季标签", 30, null),
            planCard("bad", "脏 id", 10, "第1季"),
        )))
        // 同 IP 与脏 id 都不进季线；季号取 tag_info 的权威值
        assertEquals(listOf(8, 5), seasons.map { it.first })
        assertEquals(listOf("3", "4"), seasons.map { it.second.id })
        assertEquals("全 80 集", seasons[0].second.badge)
        assertTrue(seasons[0].second.released)
    }

    @Test
    fun planCellMarksUnreleasedSeasons() {
        val seasons = Parsers.planCell(planResponse(listOf(planCard("5", "测试书第九季", 0, "第9季"))))
        assertFalse(seasons[0].second.released)
        assertEquals("未上线", seasons[0].second.badge)
    }

    /**
     * 2026-10-24 验收（第二次）：**同 IP 卡片一律不进季线**，哪怕它标题里带「第一季」。
     *
     * 实测相关作品里混着同 IP 作品的「3D 版第一季」「月落镇魔第二季」——它们各有各的季号，
     * 按标题兜底会把 37 张卡全塞进同一条季线（维护者截图：一屏全是「第一季」）。
     * 判定只看**服务端给的季号**：`tag_info.text` 能解析出「第N季」或带 `season_index` 才是本系列，
     * 写「同IP」的一律不是。
     */
    @Test
    fun planCellDropsSameIpCardsEvenWhenTheTitleCarriesASeasonTag() {
        val seasons = Parsers.planCell(planResponse(listOf(
            planCard("2", "测试书第二季", 64, "第2季", seasonIndex = 2),
            planCard("90", "测试书3D版第一季", 69, "同IP", seasonIndex = 1),
            planCard("91", "测试书：月落篇第二季", 58, "同IP", seasonIndex = 2),
        )))
        assertEquals(listOf("2"), seasons.map { it.second.id })
    }

    /** 卡片自带 `season_index` 时以它为准（标签是展示文案，可能写成「第九季」而序号是 9）。 */
    @Test
    fun planCellPrefersSeasonIndexOverTheTagText() {
        val seasons = Parsers.planCell(planResponse(listOf(planCard("3", "测试书", 80, "第2季", seasonIndex = 3))))
        assertEquals(listOf(3), seasons.map { it.first })
        assertEquals(3, seasons[0].second.season)
    }

    @Test
    fun planCellWithoutTheRelatedWorksCellIsEmpty() {
        val empty = JSONArray().put(JSONObject().put("show_type", 999).put("video_data", JSONArray()))
        assertEquals(0, Parsers.planCell(empty).size)
    }

    // ---------- 取流 ----------

    private fun videoList(vararg rows: Triple<Int, String, String>): JSONObject {
        val list = JSONObject()
        rows.forEachIndexed { index, (height, codec, url) ->
            list.put("video_$index", JSONObject().put("vheight", height).put("codec_type", codec).put("main_url", url))
        }
        return list
    }

    @Test
    fun qualityRowsSortDescendingAndDropIncompatibleCodecs() {
        val rows = Parsers.qualityRows(videoList(
            Triple(720, "bytevc2", "https://a/1"),
            Triple(1080, "ByteVC2", "https://a/2"),
            Triple(480, "h264", "https://a/3"),
            Triple(360, "bvc2", "https://a/4"),
        ))
        assertEquals(listOf(480), rows.map { it.height })
    }

    @Test
    fun qualityNumberReadsTheLegacyLabels() {
        assertEquals(1080, Parsers.qualityNumber("1080P"))
        // 1920/1024 是宽度标签，旧实现按表映射到高度口径
        assertEquals(1080, Parsers.qualityNumber("1920"))
        assertEquals(576, Parsers.qualityNumber("1024"))
        assertEquals(0, Parsers.qualityNumber("v9"))
    }

    @Test
    fun fallbackUrlAcceptsAllThreeHistoricShapes() {
        assertEquals(
            "https://cdn.example/v.mp4",
            Parsers.fallbackUrl(JSONObject().put("video_model", JSONObject().put("fallback_api", "https://cdn.example/v.mp4"))),
        )
        assertEquals(
            "https://cdn.example/v.mp4",
            Parsers.fallbackUrl(JSONObject().put("video_model", JSONObject().put("fallback_api", JSONArray().put("https://cdn.example/v.mp4")))),
        )
        assertEquals(
            "https://cdn.example/v.mp4",
            Parsers.fallbackUrl(JSONObject().put("video_model", """{"fallback_api":"https://cdn.example/v.mp4"}""")),
        )
    }

    @Test
    fun fallbackUrlRejectsNonHttps() {
        assertThrows(UpstreamError.Shape::class.java) {
            Parsers.fallbackUrl(JSONObject().put("video_model", JSONObject().put("fallback_api", "http://cdn.example/v.mp4")))
        }
    }
}
