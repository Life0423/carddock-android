package com.life0423.carddock.data

import android.content.Context
import android.util.AtomicFile
import com.life0423.carddock.core.model.AppPrice
import com.life0423.carddock.core.model.CardSnapshot
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 保存时冻结两款已核验的身份与价格，后续卡片刷新不会修改这个基线。 */
@Serializable
internal data class ShoppingItem(
    val sku: String,
    val identity: CardSnapshot,
    val price: AppPrice?,
    val priceVerificationFailed: Boolean = false,
) {
    val variant get() = identity.fields.first { it.key == "variant" }.value
    val title get() = identity.fields.first { it.key == "title" }.value
}

@Serializable
internal data class ShoppingTask(val a: ShoppingItem, val b: ShoppingItem, val note: String)

internal class ShoppingStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "shopping-v1.json"))

    suspend fun load(): ShoppingTask? = withContext(Dispatchers.IO) {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) null
        else Json.decodeFromString<ShoppingTask>(file.readFully().decodeToString()).also { task ->
            require(listOf(task.a, task.b).all { item ->
                item.identity.fields.any { it.key == "variant" } && item.identity.fields.any { it.key == "title" }
            })
        }
    }

    suspend fun save(task: ShoppingTask) = withContext(Dispatchers.IO) {
        val bytes = Json.encodeToString(ShoppingTask.serializer(), task).encodeToByteArray()
        val output = file.startWrite()
        try {
            output.write(bytes)
            file.finishWrite(output)
        } catch (error: java.io.IOException) {
            file.failWrite(output)
            throw error
        }
        if (!file.readFully().contentEquals(bytes)) throw java.io.IOException("Shopping task write failed")
    }
}

/** 只在金额和可见条件完整、条件一致时比较。 */
internal fun comparablePrices(first: AppPrice?, second: AppPrice?): Boolean =
    first != null && second != null && first.currency == second.currency &&
        first.label == second.label && first.discount != null && first.discount == second.discount

internal fun priceChange(current: ShoppingItem?, saved: ShoppingItem?): String {
    val old = saved?.price
    val fresh = current?.price
    if (current == null || saved == null || old == null || fresh == null ||
        current.sku != saved.sku || current.variant != saved.variant || current.priceVerificationFailed ||
        saved.priceVerificationFailed ||
        fresh.verifiedAt <= old.verifiedAt || !comparablePrices(old, fresh)
    ) return "无法核验"
    val oldAmount = old.amount.toBigDecimalOrNull() ?: return "无法核验"
    val freshAmount = fresh.amount.toBigDecimalOrNull() ?: return "无法核验"
    return when {
        freshAmount > oldAmount -> "有变化 · 上涨"
        freshAmount < oldAmount -> "有变化 · 下跌"
        else -> "未变化"
    }
}
