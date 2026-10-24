package com.hongguotv.core.protocol

/**
 * 上游响应字段名。全部集中在这一处。
 *
 * 这些字符串是全项目最容易被上游改掉的东西：改一个名字，旧实现要动 5 个文件 13 处
 * （解析、测试、Node 参考、诊断脚本、生成物）。在这里它是一个常量，改一行。
 *
 * 约定：只放**字段名与协议常量**，不放业务判定。像"celebrities 为空即漫剧"这种规则，
 * 属于 data 层的映射，写在这里会把协议形状和业务语义重新粘回去。
 */
object Fields {

    /** 响应信封。上游两种大小写混用，两套都留着，解析时按序取。 */
    object Envelope {
        const val CODE = "code"
        const val CODE_CAP = "Code"
        const val MESSAGE = "message"
        const val MESSAGE_CAP = "Message"
        const val DATA = "data"
    }

    /** 卡片：搜索结果、相关作品 cell、联想里都复用这套字段（部分缺失属正常）。 */
    object Card {
        const val SERIES_ID = "series_id"
        const val SERIES_NAME = "series_name"
        const val SERIES_TITLE = "series_title"
        const val SERIES_COVER = "series_cover"
        const val SERIES_INTRO = "series_intro"
        const val EPISODE_CNT = "episode_cnt"
        const val EPISODE_RIGHT_TEXT = "episode_right_text"
        const val SERIES_EPISODE_INFO = "series_episode_info"
        const val CELEBRITIES = "celebrities"
        const val TAGS = "tags"
        const val CATEGORY_LIST = "category_list"

        /**
         * 分类的第三种形态：**字符串形式的 JSON 数组**（plan / 相关作品卡片用它）。
         * 只读前两者会让"分类时有时无"——同一个系列，官网搜索那条有、季线卡片没有。
         */
        const val CATEGORY_SCHEMA = "category_schema"
        const val CREATE_TIME = "create_time"
        const val VIDEO_DATA = "video_data"
        const val NAME = "name"
        const val COVER = "cover"
        const val TITLE = "title"
        const val VIDEO_DESC = "video_desc"
    }

    /**
     * 「相关作品」cell：季线的唯一事实来源。
     * 卡片本体的字段（series_id / cover / title / video_desc / episode_cnt）与 [Card] 共用，
     * 这里只列 plan 特有的。
     */
    object Plan {
        const val SHOW_TYPE = "show_type"

        /** show_type 里代表「相关作品」的那个值。改它等于换数据源。 */
        const val SHOW_TYPE_RELATED = 547

        const val VIDEO_DATA = "video_data"
        const val TAG_INFO = "tag_info"
        const val TAG_TEXT = "text"

        /** 卡片在系列里的季序号（服务端给，比 tag 文案可靠）。 */
        const val SEASON_INDEX = "season_index"
    }

    /** 详情：全集清单与分类。 */
    object Detail {
        const val VIDEO_DATA = "video_data"
        const val VIDEO_LIST = "video_list"
        const val VID = "vid"
        const val VID_INDEX = "vid_index"
        const val SERIES_ID = "series_id"
        const val SERIES_TITLE = "series_title"
        const val SERIES_NAME = "series_name"
        const val SERIES_COVER = "series_cover"
        const val SERIES_INTRO = "series_intro"
        const val EPISODE_CNT = "episode_cnt"

        /** 分类在 category_schema（JSON 字符串）里，详情响应没有 tags。 */
        const val CATEGORY_SCHEMA = "category_schema"
        const val SECONDARY_INFOS = "secondary_infos"
        const val DATA_TYPE = "data_type"
        const val DATA_TYPE_CATEGORY = 3
        const val CONTENT = "content"

        /** 这一季在系列里的序号；0/缺失 = 同 IP 作品（不是任何季线的一季）。 */
        const val SEASON_INDEX = "season_index"

        /** 所属专辑 id：同一系列的各季共用。同 IP 作品的这个字段是空的。 */
        const val RELATED_ALBUM_ID = "related_album_id"
    }

    /** 取流：video_model → fallback_api → video_list。 */
    object Stream {
        const val VIDEO_MODEL = "video_model"
        const val FALLBACK_API = "fallback_api"
        const val VIDEO_INFO = "video_info"
        const val VIDEO_LIST = "video_list"
        const val MAIN_URL = "main_url"
        const val CODEC_TYPE = "codec_type"
        const val QUALITY_DESC = "quality_desc"
        const val HEIGHT = "height"
        const val VHEIGHT = "vheight"
        const val QUALITY = "quality"
        const val KEY_SEED = "key_seed"
        const val SPADE_A = "spade_a"

        /** 这些编码在 Android 的 MP4 提取器里可能只有音轨没有视频轨，必须排除。 */
        val INCOMPATIBLE_CODECS = setOf("bytevc2", "bvc2")
    }

    /**
     * 官网联想词（`/incent_resource/suggestion`）：免签名、免设备注册，输入辅助的唯一来源。
     * `video_data` 与卡片同构；为空对象时这一条只能当查询词用。
     */
    object Suggest {
        const val SUGGEST_LIST = "suggest_list"
        const val DISPLAY_WORDS = "display_words"
        const val NAME = "name"
        const val KEYWORD = "keyword"
        const val VIDEO_DATA = "video_data"
        const val SERIES_ID = "series_id"
    }

    /**
     * 官网内容接口（2026-10-23 起官网换成 React SPA，`_ROUTER_DATA` 不再下发）。
     * 搜索走页面自己调的 `/api/search/result`：JSON、服务端分页、一页 10 条。
     */
    object Site {
        const val SEARCH_LIST = "searchList"
        const val PAGE_NUM = "pageNum"
        const val TOTAL_PAGES = "totalPages"
        const val TOTAL_COUNT = "totalCount"
        const val HAS_MORE = "hasMore"
    }
}
