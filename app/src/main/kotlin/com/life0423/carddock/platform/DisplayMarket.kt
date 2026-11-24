package com.life0423.carddock.platform

import com.life0423.carddock.core.port.DeviceResponse
import com.life0423.carddock.core.port.DeviceSnapshot
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal const val APPLE_STORE = "apple.cn.display"
internal const val PRICE_RANK = "price-rank"
internal const val PRICE_BASIS = "Apple 中国大陆官网 · 人民币标准零售价"

internal data class DisplayOffer(val part: String, val model: String, val configuration: String, val yuan: Int, val family: String) {
    val url get() = "https://www.apple.com.cn/shop/buy-mac/$family/" + part.lowercase()
}

/** 两个购买页均提供与订货号绑定的价格。任一系列读取失败，整轮不发布价格榜。 */
internal data class DisplayCatalog(val offers: List<DisplayOffer>, val at: Long) {
    fun snapshot(target: String): DeviceResponse {
        val fields = if (target == PRICE_RANK) mapOf(
            "ranking" to offers.take(3).mapIndexed { i, item -> "${i + 1}. ¥${item.yuan} · ${item.model} · ${item.configuration}" }.joinToString("\n"),
            "scope" to "${offers.map { it.model }.distinct().size} 个系列 · ${offers.size} 个配置，按标价升序，同价按订货号排列。",
            "basis" to PRICE_BASIS
        ) else {
            val offer = offers.find { it.part == target } ?: return DeviceResponse.NeedsUser("本次未找到此订货号，保留上次价格。")
            mapOf("price" to "¥${offer.yuan}", "variant" to "${offer.model} · ${offer.configuration}",
                "part" to offer.part, "basis" to PRICE_BASIS)
        }
        return DeviceResponse.Ready(DeviceSnapshot(APPLE_STORE, target, fields, PRICE_BASIS))
    }
}

@Serializable private data class Metrics(val data: StoreData)
@Serializable private data class StoreData(val currency: String, val products: List<StoreProduct>)
@Serializable private data class StoreProduct(val partNumber: String, val name: String, val category: String, val price: RetailPrice)
@Serializable private data class RetailPrice(val fullPrice: Double)

private val storeJson = Json { ignoreUnknownKeys = true }
private val metrics = Regex("""<script\b(?=[^>]*\bid=["']metrics["'])[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)

/** 在公开页边界一次核对币种、系列、规格标识和金额，小数价格不静默取整。 */
internal fun parseDisplayOffers(html: String, family: String): List<DisplayOffer> {
    val payload = metrics.find(html)?.groupValues?.get(1) ?: throw IOException("官网缺少商品价格数据")
    val data = storeJson.decodeFromString<Metrics>(payload).data
    val model = if (family == "studio-display") "Studio Display" else "Studio Display XDR"
    require(data.currency == "CNY" && data.products.isNotEmpty())
    return data.products.map { item ->
        require(item.category == "display" && item.partNumber.matches(Regex("[A-Z0-9]+CH/A")) && item.name.startsWith("$model - "))
        val price = item.price.fullPrice
        require(price > 0 && price == price.toInt().toDouble())
        val variant = item.name.removePrefix("$model - ").replace("Standard glass", "标准玻璃")
            .replace("Nano-texture glass", "纳米纹理玻璃").replace("Tilt- and height-adjustable stand", "可调高度支架")
            .replace("Tilt-adjustable stand", "可调倾斜支架").replace("VESA mount adapter (stand not included)", "VESA 转换器（不含支架）")
            .replace("VESA mount adapter", "VESA 转换器").replace(" - ", " · ")
        DisplayOffer(item.partNumber, model, variant, price.toInt(), family)
    }
}

internal suspend fun loadDisplayCatalog(): DisplayCatalog = withContext(Dispatchers.IO) {
    val rows = listOf("studio-display", "studio-display-xdr").flatMap { family ->
        val connection = URL("https://www.apple.com.cn/shop/buy-mac/$family").openConnection() as HttpURLConnection
        connection.connectTimeout = 8000
        connection.readTimeout = 12000
        connection.instanceFollowRedirects = false
        try {
            if (connection.responseCode != 200) throw IOException("官网暂时不可用")
            val html = connection.inputStream.bufferedReader().use { it.readBounded(1024 * 1024) }
            parseDisplayOffers(html, family)
        } catch (invalid: IllegalArgumentException) {
            throw IOException("官网价格口径或商品字段已变化", invalid)
        } finally { connection.disconnect() }
    }
    if (rows.distinctBy { it.part }.size != rows.size) throw IOException("官网订货号重复")
    DisplayCatalog(rows.sortedWith(compareBy({ it.yuan }, { it.part })), System.currentTimeMillis())
}

