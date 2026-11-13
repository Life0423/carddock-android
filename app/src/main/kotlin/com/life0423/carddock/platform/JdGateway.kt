package com.life0423.carddock.platform

import android.content.ClipboardManager
import android.content.Context
import com.life0423.carddock.core.model.AppPrice
import com.life0423.carddock.core.port.DeviceResponse
import com.life0423.carddock.core.port.DeviceSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

internal enum class JdProduct(val sku: String, val variant: String, val titlePrefix: String) {
    A("100305104289", "GX 27款/27/黑曜屏/2304分区", "海信GX 27款"),
    B("100312915069", "爆27\" 4K 240Hz双模DP2.1/OLED", "华硕ROG 超神27 二代PG27UCWM");

    val title get() = "京东商品 $name"
    val publicUri get() = URI("https://item.m.jd.com/product/$sku.html")
}

internal data class ShareClip(val text: String, val copiedAt: Long)
private data class CapturedPrice(val price: AppPrice, val share: String)

/** 分享弹层打开和退出都必须发生在同一份详情读取过程中。 */
private data class PriceEvidence(
    val title: String,
    val beforeShare: AppPrice,
    val shareSeenAt: Long? = null,
    val afterShare: AppPrice? = null,
)

/** 公开身份可自动读取；展示价另需同次前台详情和新复制的公开链接。 */
internal class JdGateway(
    private val dockPackage: String,
    private val readClip: () -> ShareClip?,
    private val connected: () -> Boolean = { CardDockAccessibilityService.instance != null },
    private val observe: () -> DetailWindow? = { CardDockAccessibilityService.instance?.observeDetail(JD_PACKAGE) },
    private val now: () -> Long = System::currentTimeMillis,
    private val matchesShare: (String, JdProduct) -> Boolean = ::matchesJdShare,
    private val readPage: (URI) -> String = ::readPublicJdPage,
) {
    constructor(context: Context) : this(context.packageName, {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val clip = clipboard.primaryClip
        if (clip == null || clip.itemCount != 1) null
        else clip.getItemAt(0).text?.toString()?.let { ShareClip(it, clip.description.timestamp) }
    })

    suspend fun inspect(target: String, appPrice: Boolean, requestedAt: Long?): DeviceResponse {
        val product = JdProduct.entries.firstOrNull { it.sku == target }
            ?: return DeviceResponse.Failed("京东商品不在本地核验模板中。")
        val captured = if (appPrice) {
            if (!connected()) return DeviceResponse.NeedsUser("请先在“我的”开启无障碍读取。")
            capture(product, checkNotNull(requestedAt))
                ?: return DeviceResponse.NeedsUser("未核验展示价。请打开匹配商品详情、关闭浮窗、复制本次分享链接并返回。")
        } else null
        return withContext(Dispatchers.IO) {
            try {
                if (captured != null && !matchesShare(captured.share, product)) {
                    return@withContext DeviceResponse.Failed("分享链接与指定商品不匹配，未更新展示价。")
                }
                verifyJdPage(readPage(product.publicUri), product, captured?.price)
            } catch (_: IOException) {
                DeviceResponse.Failed("京东公开页连接失败，请稍后重试。")
            }
        }
    }

    private suspend fun capture(product: JdProduct, startedAt: Long): CapturedPrice? = withTimeoutOrNull(60_000) {
        var evidence: PriceEvidence? = null
        while (connected()) {
            val page = observe()
            when (page?.source) {
                JD_PACKAGE -> {
                    if (page.isLogin()) return@withTimeoutOrNull null
                    val sampleTime = now()
                    val detail = page.readJdDetail(sampleTime)
                    val shareVisible = page.nodes.any { it.visible && it.label == "复制链接" }
                    val prior = evidence
                    evidence = when {
                        detail != null && (detail.variant != product.variant || !detail.title.startsWith(product.titlePrefix)) -> null
                        shareVisible && prior != null ->
                            prior.copy(shareSeenAt = prior.shareSeenAt ?: sampleTime, afterShare = null)
                        detail?.price != null && prior?.shareSeenAt != null && detail.title == prior.title &&
                            detail.price.copy(verifiedAt = prior.beforeShare.verifiedAt) == prior.beforeShare ->
                            prior.copy(afterShare = detail.price)
                        detail?.price != null -> PriceEvidence(detail.title, detail.price)
                        else -> null
                    }
                }
                dockPackage -> {
                    val price = evidence?.afterShare
                    val shareSeenAt = evidence?.shareSeenAt
                    if (price != null && shareSeenAt != null) readClip()?.let { clip ->
                        if (clip.copiedAt >= maxOf(startedAt, shareSeenAt) && clip.copiedAt <= price.verifiedAt) {
                            return@withTimeoutOrNull CapturedPrice(price, clip.text)
                        }
                    }
                }
                else -> if (page?.source != null && page.source != "com.android.systemui") {
                    evidence = null
                }
            }
            delay(200)
        }
        null
    }

}

private fun readPublicJdPage(uri: URI): String = requestJd(uri) { response ->
    if (response.responseCode == 200) response.inputStream.bufferedReader().use { it.readBounded(512 * 1024) } else ""
}

private fun matchesJdShare(text: String, product: JdProduct): Boolean {
    val short = Regex("""https://3\.cn/[A-Za-z0-9_-]+(?=$|\s|[?，。！、】)）])""")
        .findAll(text).map { it.value }.distinct().singleOrNull() ?: return false
    val location = requestJd(URI(short)) {
        if (it.responseCode in setOf(301, 302, 303, 307, 308)) it.getHeaderField("Location") else null
    } ?: return false
    val destination = runCatching { URI(short).resolve(location) }.getOrNull() ?: return false
    val expected = product.publicUri
    return destination.scheme == expected.scheme && destination.rawAuthority == expected.rawAuthority &&
        destination.rawPath == expected.rawPath
}

private fun <T> requestJd(uri: URI, read: (HttpURLConnection) -> T): T {
    val connection = uri.toURL().openConnection() as HttpURLConnection
    connection.instanceFollowRedirects = false
    connection.connectTimeout = 5_000
    connection.readTimeout = 5_000
    connection.setRequestProperty("User-Agent", "Mozilla/5.0")
    return try { read(connection) } finally { connection.disconnect() }
}

private val assignment = Regex("""(?s)window\._itemOnly\s*=\s*\(\s*(\{.*?\})\s*\);""")

/** SKU 必须来自商品主体，规格必须来自同 SKU 的映射项。 */
internal fun verifyJdPage(html: String, product: JdProduct, price: AppPrice? = null): DeviceResponse {
    val payload = assignment.find(html)?.groupValues?.get(1)
        ?: return DeviceResponse.NeedsUser("京东公开页未提供身份字段，请稍后重新核验。")
    val item = runCatching { (Json.parseToJsonElement(payload) as? JsonObject)?.get("item") as? JsonObject }.getOrNull()
    val sku = (item?.get("skuId") as? JsonPrimitive)?.content
    val variant = ((item?.get("newColorSize") as? JsonArray)?.filterIsInstance<JsonObject>()?.singleOrNull {
        (it["skuId"] as? JsonPrimitive)?.content == sku
    }?.get("1") as? JsonPrimitive)?.content
    val title = (item?.get("skuName") as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (sku != product.sku || variant != product.variant || title.isNullOrBlank()) {
        return DeviceResponse.Failed("京东公开页的 SKU、规格或商品名称未通过核验。")
    }
    return DeviceResponse.Ready(DeviceSnapshot(
        JD_PACKAGE, sku, mapOf("sku" to sku, "variant" to variant, "title" to title), "京东 · 公开商品页", price,
    ))
}
