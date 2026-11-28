package com.wangzan101.mika.settings

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.wangzan101.mika.R
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelListResult
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.vision.VisionService
import com.wangzan101.mika.vision.BitmapImageOps
import java.io.ByteArrayOutputStream
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 以服务商和模型名固定本次测试对象。 */
data class VisionSelection(val providerId: String, val model: String)

/** 把仅含随机六位数的本机测试图发给候选；核对成功才设置看图用途。返回 0 或可显示的本地错误资源。 */
suspend fun ProviderStore.verifyAndActivateVision(selection: VisionSelection): Int = withContext(Dispatchers.IO) {
    val (providerId, model) = selection
    val p = provider(providerId) ?: return@withContext R.string.vision_probe_unavailable
    val listed = p.models.orEmpty().firstOrNull { it.id == model }
        ?: (listModels(p) as? ModelListResult.Ok)?.models?.firstOrNull { it.id == model }
    val cfg = list().firstOrNull { it.providerId == p.id && it.model == model } ?: ProviderConfig(
        "", p.kind, p.baseUrl, model, listed?.let { ModelOptions.merge(listOf(it), ModelOptions.baseOf(p)).firstOrNull()?.capabilities }
            ?: ModelOptions.baseOf(p), p.name, p.id,
    )
    val effort = if (roles.value.vision == RoleChoice.Model(cfg.id)) roles.value.reasoningEffort(ModelRole.Vision) else null
    val expected = Random.nextInt(100000, 1000000).toString()
    val bitmap = Bitmap.createBitmap(520, 180, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.WHITE)
    val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        typeface = Typeface.MONOSPACE
        textSize = 108f
        textAlign = Paint.Align.CENTER
    }
    canvas.drawText(expected, 260f, 126f, ink)
    val image = ByteArrayOutputStream().apply { bitmap.compress(Bitmap.CompressFormat.PNG, 100, this) }.toByteArray()
    bitmap.recycle()

    val answer = try {
        VisionService(visionCandidate(cfg, effort, listed), BitmapImageOps).ask("Transcribe the image exactly.",
            "Reply with only the six digits printed in the image.", listOf(image))
    } catch (e: ModelError) {
        return@withContext when (e) {
            is ModelError.Auth, is ModelError.SignInExpired -> R.string.vision_probe_auth
            is ModelError.Network, is ModelError.Proxy -> R.string.vision_probe_network
            is ModelError.QuotaExceeded, is ModelError.RateLimited -> R.string.vision_probe_quota
            else -> R.string.vision_probe_unavailable
        }
    }
    if (answer.trim() != expected) return@withContext R.string.vision_probe_mismatch
    // 识字只验证图片输入，不代表屏幕坐标定位精度。
    if (activateVerifiedVision(cfg, listed)) 0 else R.string.vision_probe_unavailable
}
