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

    // ---------- 官网 SSR ----------

    private fun searchPage(items: List<JSONObject>): String =
        """<script>_ROUTER_DATA=""" + JSONObject()
            .put("loaderData", JSONObject().put("search_(keyword)/page", JSONObject().put("searchList", JSONArray(items))))
            .toString() + """;</script>"""

    @Test
    fun routerExtractsTheJsonBlobFromHtml() {
        val parsed = Parsers.router(searchPage(emptyList()) + "trailing garbage } never reached")
        assertTrue(parsed.has("loaderData"))
    }

    @Test
    fun routerRejectsPageWithoutData() {
        assertThrows(UpstreamError.Shape::class.java) { Parsers.router("<html>没有数据</html>") }
    }

    @Test
    fun searchListFallsBackToAlternatePageKey() {
        val html = """_ROUTER_DATA=""" + JSONObject()
            .put("loaderData", JSONObject().put("search_page", JSONObject().put("searchList", JSONArray()))).toString()
        assertEquals(0, Parsers.searchList(html).length())
    }

    @Test
    fun searchListTreatsTheClientRenderedShellAsNoResults() {
        // 2026-10-23 实测：官网搜索页改成纯前端渲染，body 只有 SSR 占位符与 JS，
        // `_ROUTER_DATA` 不再下发。这不是"结构变了"——客户端拿不到任何内容，
        // 报 Shape 会让用户看到「内容结构发生变化」，而正确的降级是「没找到」
        val shell = """<!doctype html><html><head><script>window.__glue_t=1</script></head>""" +
            """<body><div id="root"><!--<?- html ?>--></div></body></html>"""
        assertTrue(Parsers.clientRendered(shell))
        assertEquals(0, Parsers.searchList(shell).length())
    }

    @Test
    fun searchListStillReportsShapeWhenTheMarkerIsThereButThePathIsGone() {
        // 标记在、路径不在 = 真的结构变了，必须继续报错（不能被上一条兜底吞掉）
        val html = """<script>_ROUTER_DATA={"loaderData":{"something_else":{}}};</script>"""
        assertTrue(!Parsers.clientRendered(html))
        assertThrows(UpstreamError.Shape::class.java) { Parsers.searchList(html) }
    }

    // ---------- plan 季线 ----------

    private fun planCard(id: String, title: String, count: Int, tag: String?) = JSONObject()
        .put("video_data", JSONObject().put("series_id", id).put("title", title).put("episode_cnt", count).put("cover", "https://img.example/c.jpg"))
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
        assertEquals(listOf(480), rows.map { it.first })
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
