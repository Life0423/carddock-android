package com.wangzan101.mika.experiment

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

internal const val JD_A_RULE = "jd-a-share"
internal const val JD_A_SKU = "100305104289"
internal const val JD_A_VARIANT = "GX 27款/27/黑曜屏/2304分区"
internal const val JD_B_RULE = "jd-b-share"

internal enum class JdProduct(val ruleId: String, val sku: String, val variant: String, val cardTitle: String,
                              val appTitlePrefix: String) {
    A(JD_A_RULE, JD_A_SKU, JD_A_VARIANT, "京东商品 A", "海信GX 27款"),
    B(JD_B_RULE, "100312915069", "爆27\" 4K 240Hz双模DP2.1/OLED", "京东商品 B", "华硕ROG 超神27 二代PG27UCWM");

    companion object {
        fun forRule(ruleId: String) = entries.firstOrNull { it.ruleId == ruleId }
    }
}

@Serializable
internal data class JdShareEvidence(val sku: String, val variant: String, val title: String, val appPrice: JdProductFields? = null)

internal class JdShareVerifier {
    private val links = Regex("""https://3\.cn/[A-Za-z0-9_-]+(?=$|\s|[?，。！、】)）])""")
    private val assignment = Regex("""(?s)window\._itemOnly\s*=\s*\(\s*(\{.*?\})\s*\);""")
    private val http = OkHttpClient.Builder().followRedirects(false)
        .callTimeout(5, TimeUnit.SECONDS).build()

    suspend fun verify(input: String?, product: JdProduct): RuleResult = withContext(Dispatchers.IO) {
        val expected = "https://item.m.jd.com/product/${product.sku}.html".toHttpUrl()
        try {
            if (input != null) {
                val link = links.findAll(input).map { it.value }.distinct().singleOrNull()
                    ?: return@withContext RuleResult("NEED_HANDOFF", "请粘贴京东商品详情复制的一条分享链接。")
                val destination = http.newCall(Request.Builder().url(link).header("User-Agent", "Mozilla/5.0").build()).execute().use { response ->
                    if (response.isRedirect) response.header("Location")?.let(response.request.url::resolve) else null
                }
                val publicPage = destination?.newBuilder()?.query(null)?.fragment(null)?.build()
                if (publicPage != expected) {
                    return@withContext RuleResult(
                        if (publicPage?.host == expected.host && publicPage.encodedPath.startsWith("/product/"))
                            "IDENTITY_MISMATCH" else "NEED_HANDOFF",
                        "分享链接与${product.cardTitle}不匹配，请核对商品后重新复制链接。",
                    )
                }
            }
            val html = http.newCall(Request.Builder().url(expected).header("User-Agent", "Mozilla/5.0").build())
                .execute().use { if (it.isSuccessful) it.peekBody(512 * 1024).string() else "" }
            verifyPage(html, product)
        } catch (_: IOException) {
            RuleResult("ERROR", "公开商品页连接失败，保留上次核验结果。")
        }
    }

    internal fun verifyPage(html: String, product: JdProduct = JdProduct.A): RuleResult {
        val payload = assignment.find(html)?.groupValues?.get(1)
            ?: return RuleResult("NEED_HANDOFF", "公开商品页未提供身份字段，请稍后重新核验。")
        val item = try {
            (ruleJson.parseToJsonElement(payload) as? JsonObject)?.get("item") as? JsonObject
        } catch (_: IllegalArgumentException) {
            null
        }
        val sku = (item?.get("skuId") as? JsonPrimitive)?.contentOrNull
        val rows = item?.get("newColorSize") as? JsonArray
        val variant = rows?.filterIsInstance<JsonObject>()?.singleOrNull {
            (it["skuId"] as? JsonPrimitive)?.contentOrNull == product.sku
        }?.get("1") as? JsonPrimitive
        if (sku != product.sku || variant?.contentOrNull != product.variant) {
            return RuleResult("IDENTITY_MISMATCH", "公开商品页的 SKU 或规格不匹配，请核对商品。")
        }
        val title = (item?.get("skuName") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return RuleResult("NEED_HANDOFF", "公开商品页缺少商品名称。")
        return RuleResult("JD_IDENTITY_CONFIRMED", "公开页 SKU 和规格已核验，价格未核验。",
            jdFields = JdShareEvidence(sku, product.variant, title))
    }
}
