package com.wangzan101.mika.core.verify

import com.wangzan101.mika.core.uistate.ScreenSnapshot
import kotlinx.serialization.Serializable
import java.math.BigDecimal

/** 价格绑定到来源、商品、规格及口径；区间和起价有独立表示。 */
@Serializable
data class PriceKey(val pkg: String, val product: String, val variant: String, val label: String)
@Serializable
data class CardPrice(val key: PriceKey, val low: Long, val high: Long? = null, val from: Boolean = false) {
    val display: String get() {
        fun yuan(n: Long) = BigDecimal.valueOf(n, 2).stripTrailingZeros().toPlainString()
        return "¥" + yuan(low) + (high?.let { "–¥" + yuan(it) } ?: if (from) "起" else "")
    }

    fun changeFrom(previous: CardPrice): String? {
        if (key != previous.key) return null
        if (this == previous) return "与上次持平"
        if (high != null || previous.high != null || from || previous.from) return "上次 " + previous.display
        val delta = low - previous.low
        return (if (delta < 0) "比上次降 ¥" else "比上次涨 ¥") +
            BigDecimal.valueOf(kotlin.math.abs(delta), 2).stripTrailingZeros().toPlainString()
    }

    companion object {
        private val money = Regex("""[¥￥]\s*(\d{1,9}(?:\.\d{1,2})?)(?:\s*[-~～至–]\s*[¥￥]?\s*(\d{1,9}(?:\.\d{1,2})?))?\s*(起)?(?![\d.])""")
        /** 模型只提议页面字段；金额取本次标价控件，核对后才可写入卡片。 */
        fun read(page: ScreenSnapshot?, answer: String, known: PriceKey?, missing: (String) -> Unit = {}): CardPrice? {
            fun absent(why: String): CardPrice? { missing(why); return null }
            val pkg = page?.packageName ?: return absent("目标应用窗口不可读")
            if (page.protected || !page.hasWindow || page.changedDuringRead || page.captureError != null) return absent("目标商品页尚未稳定")
            fun clean(text: String) = text.replace(Regex("[\\u200B-\\u200D\\uFEFF]"), "")
                .replace(Regex("""\s+"""), " ").trim(' ', '*', '-', '「', '」', '“', '”')
            val fields = answer.lineSequence().map { clean(it.replace("**", "").replaceFirst("：", ":")) }.associate {
                it.substringBefore(':').trim() to clean(it.substringAfter(':', ""))
            }
            // 价格、商品和已选状态须来自同一个前台详情窗口。列表推荐区不提供目标报价。
            val title = known?.product ?: fields["商品"].orEmpty()
            val available = page.elements.filterNot { it.secret || it.editable }
            var live = if (page.windows.isEmpty()) available else emptyList()
            val covered = mutableListOf<List<Int>>()
            for (window in page.windows.sortedByDescending { it.layer }) {
                if (window.type != "application" || window.packageName != pkg)
                    return absent("商品详情不在当前应用窗口")
                val refs = buildSet<String> {
                    fun visit(n: com.wangzan101.mika.core.uistate.UiTreeNode) { n.ref?.let(::add); n.children.forEach(::visit) }
                    window.nodes.forEach(::visit)
                }
                val visible = available.filter { node ->
                    val b = node.bounds
                    node.ref in refs && b.size == 4 && covered.none { box ->
                        b[0] < box[2] && b[2] > box[0] && b[1] < box[3] && b[3] > box[1]
                    }
                }
                if (title.isNotBlank() && visible.any { clean(it.name).contains(title) }) {
                    live = visible
                    break
                }
                // 同一应用可在角落另开小浮窗。只允许从其可见范围以外的一个详情窗口取齐所有字段。
                covered += window.nodes.map { it.bounds }.filter { it.size == 4 && it[2] > it[0] && it[3] > it[1] }
            }
            val boundary = live.filter { it.name in setOf("猜你喜欢", "为你推荐", "看了又看") && it.bounds.size == 4 }
                .minOfOrNull { it.bounds[1] } ?: Int.MAX_VALUE
            val detail = live.filter { it.bounds.size == 4 && it.bounds[1] < boundary }
            val nodes = detail.map { clean(it.name) }.filter(String::isNotBlank).distinct()
            val variants = nodes.mapNotNull {
                Regex("""^(?:已选|规格)[：: ]+(.+)$""").matchEntire(it)?.groupValues?.get(1)
            }.map { it.replace(Regex("""[，,]\s*\d+件$"""), "").trim() }.distinct()
            val offeredProduct = fields["商品"].orEmpty()
            val actualTitle = nodes.firstOrNull { it == offeredProduct } ?: nodes.filter {
                offeredProduct.length >= 6 && it.contains(offeredProduct) && it.length <= offeredProduct.length + 80
            }.singleOrNull()
            val key = known ?: PriceKey(pkg, actualTitle ?: return absent(when {
                    offeredProduct.isBlank() -> "未提取出可与当前页面核对的商品全名"
                    live.any { clean(it.name) == offeredProduct } -> "商品标题位于推荐区，尚未定位到所选商品"
                    page.elements.any { clean(it.name) == offeredProduct } -> "商品标题位于其他窗口，当前窗口未核实"
                    else -> "当前详情页的商品标题与提议字段不符"
                }),
                variants.singleOrNull() ?: fields["规格"].orEmpty(), fields["价格标签"].orEmpty().replace(money, "").trim())
            if (key.pkg != pkg || listOf(key.product, key.variant, key.label).any(String::isBlank)) return absent("缺少商品规格或价格标签")
            if (key.product !in nodes || key.variant !in variants &&
                detail.none { clean(it.name) == key.variant && it.states.any { state -> state == "selected" || state == "checked" } })
                return absent("本次商品或已选规格与卡片不一致")
            val prices = nodes.distinct().mapNotNull { node ->
                val raw = if (key.label == "页面标价") node else {
                    when {
                        node.startsWith(key.label) -> node.removePrefix(key.label).trimStart(' ', '：', ':')
                        node.endsWith(key.label) -> {
                            val amount = node.removeSuffix(key.label).trimEnd(' ', '，', ',', '：', ':')
                            if (amount.endsWith("元")) "¥" + amount.removeSuffix("元") else amount
                        }
                        else -> return@mapNotNull null
                    }
                }
                val m = money.matchEntire(raw) ?: return@mapNotNull null
                fun cents(n: String) = n.toBigDecimal().movePointRight(2).longValueExact()
                val low = cents(m.groupValues[1])
                val high = m.groupValues[2].takeIf(String::isNotEmpty)?.let(::cents)
                if (high != null && high < low) null else CardPrice(key, low, high, m.groupValues[3].isNotEmpty())
            }.distinct()
            // 无标签金额与促销口径并存时，要用户选定可明确关联的字段。
            if (key.label == "页面标价" && nodes.any { Regex("券后|领后价|会员价|到手价|划线价").containsMatchIn(it) }) return absent("存在多种价格，需要选择价格标签")
            return prices.singleOrNull() ?: absent("当前窗口未找到唯一的所选标价")
        }
    }
}
