package com.life0423.carddock

import com.life0423.carddock.ai.ModelSettings
import com.life0423.carddock.ai.requestModel
import com.life0423.carddock.core.model.TemplateDefinition
import com.life0423.carddock.core.model.TemplateKind
import com.life0423.carddock.core.port.DiscoveryResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

internal const val TAOBAO = "com.taobao.taobao"
internal val com.life0423.carddock.core.rule.RuleSpec.isBookQuery get() = sourceId == TAOBAO && targetKey.startsWith("books/")
internal const val BOOK_PROMPT = "在淘宝推荐两本悬疑小说，给我推荐理由和商品链接"

/** 查询参数随模板保存；规则中的目标键也用于核对实际提交的关键词。 */
@Serializable
internal data class BookSearch(val keyword: String, val count: Int) {
    val key get() = "books/$count/$keyword"
    val valid get() = keyword.isNotBlank() && keyword.length <= 40 && keyword.none { it.isISOControl() || it == '/' } && count in 1..5
    fun template(prompt: String) = TemplateDefinition("taobao-books", "淘宝找书 · $keyword",
        "查找 $count 本书，回读商品依据与链接", TemplateKind.QUERY, prompt,
        nativeRule("taobao-books", TAOBAO, key, "summary" to "核对进度", *(1..count).flatMap {
            listOf("book$it" to "图书 $it", "url$it" to "商品链接 $it")
        }.toTypedArray()))

    companion object {
        fun fromKey(key: String): BookSearch? {
            val parts = key.split('/', limit = 3)
            if (parts.size != 3 || parts[0] != "books") return null
            return BookSearch(parts[2], parts[1].toIntOrNull() ?: return null).takeIf { it.valid }
        }
    }
}

/** 完整匹配常见找书指令；额外条件留给模型判断，避免静默丢失。 */
internal fun simpleBookSearch(demand: String): BookSearch? {
    val pattern = Regex("""(?:请)?(?:帮我|给我)?(?:打开|在|去|用)?淘宝(?:里面|里|中|上)?(?:帮我|给我)?(?:推荐|搜搜|搜索|找|查找)(?:一些|几本|([一二两三四五1-5])本)?(?:好看的|好看点的)?((?:悬疑|推理|科幻|奇幻|武侠|历史|侦探|言情|文学|悬疑推理)?小说)(?:(?:[，,、 ]|并|和|以及|给我|附上|购买链接|商品链接|链接|推荐理由|相应的|的|理由|与|附|提供)*)[。！!]?""")
    val match = pattern.matchEntire(demand.trim()) ?: return null
    val qty = when (val n = match.groupValues[1]) { "", "两", "二" -> 2; "一" -> 1; "三" -> 3; "四" -> 4; "五" -> 5; else -> n.toInt() }
    return BookSearch(match.groupValues[2], qty)
}

/** 模型只提取找书参数，无法用一个搜索词表达的要求留给用户澄清。 */
internal suspend fun bookProposal(settings: ModelSettings, demand: String, apps: List<LocalApp>): DiscoveryResult? {
    if ("淘宝" !in demand || !Regex("书|小说|读物").containsMatchIn(demand) ||
        !Regex("搜|查|找|推荐|挑|选|看看").containsMatchIn(demand)) return null
    if (Regex("已买|买过|订单|购物车|物流|取件|数量统计|收藏夹").containsMatchIn(demand)) return null
    if (apps.none { it.packageName == TAOBAO }) return DiscoveryResult.Incomplete("应用识别失败：当前手机没有可启动的淘宝。")
    val search = simpleBookSearch(demand) ?: withContext(Dispatchers.IO) {
        val config = settings.load() ?: return@withContext null
        val prompt = """只提取用户明确要求的淘宝图书只读查询。返回 JSON {"keyword":"淘宝搜索关键词","count":2}。
            将题材、作者、语言、书名或版本完整保留在关键词里，不补写题材。宽泛的好看小说用“小说”。未指定数量用2。
            包含购物操作、多个独立筛选、排除项、预算、硬性要求完整剧情简介或评分等无法通过一个关键词和商品详情标题验证的要求时，返回 {"keyword":"","count":0}。"""
        try {
            val input = demand.replace(Regex("""https?://\S+|[\w.+-]+@[\w.-]+|\d{7,}"""), "〔已省略〕")
            Json.decodeFromString<BookSearch>(requestModel(config, prompt, input).trim().removePrefix("```json").removeSuffix("```").trim())
        } catch (_: java.io.IOException) { null } catch (_: SerializationException) { null }
    }
    if (search?.valid != true) return DiscoveryResult.Incomplete("请指定一个图书关键词和 1 至 5 本的数量。可从「淘宝找书」修改参数后试查。")
    return DiscoveryResult.Plans(listOf(search.template(demand)), "后台搜索「${search.keyword}」，逐本核对，最多 ${search.count} 本。")
}

internal val bookItemUrl = Regex("""https://item\.taobao\.com/item\.htm\?id=[1-9][0-9]{5,19}""")
internal fun officialBookUrl(value: String) = bookShareUrl.matches(value) || bookItemUrl.matches(value)
internal val bookShareUrl = Regex("""https://e\.tb\.cn/h\.[A-Za-z0-9_-]{4,80}""")
