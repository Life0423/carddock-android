package com.life0423.carddock.platform

import com.life0423.carddock.MONITOR_PAIR
import com.life0423.carddock.ai.ModelSettings
import com.life0423.carddock.ai.requestModel
import com.life0423.carddock.core.port.DeviceResponse
import com.life0423.carddock.core.port.DeviceSnapshot
import com.life0423.carddock.core.rule.MonitorComparison
import com.life0423.carddock.core.rule.MonitorFocus
import com.life0423.carddock.core.rule.MonitorUse
import java.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** 模型选择哪条已核验的证据最能回应当前用途；结论和缺失项随证据成对展示。 */
private data class AdviceOption(val quote: String, val advice: String, val check: String)

private fun options(source: DeviceSnapshot, plan: MonitorComparison): List<AdviceOption> {
    val fields = listOf(source.fields.getValue("title"), source.fields.getValue("variant"))
    val candidates = when (plan.use) {
        MonitorUse.OFFICE -> listOf(
            AdviceOption("4K", "常看代码或并排文档，可优先试这款的文字缩放。", "用常用字号检查缩放后的可读性。"),
            AdviceOption("27英寸", "桌面空间有限时，可先按这个尺寸核对摆位。", "核对带底座尺寸和桌面进深。"))
        MonitorUse.GAME -> listOf(
            AdviceOption("OLED", "偏重暗场游戏，可用同一场景比较暗部层次。", "确认暗部细节和环境光下的体验。"),
            AdviceOption("240Hz", "偏重竞技游戏，可优先试这款的高刷新模式。", "确认显卡、接口及常玩的游戏能达到目标帧率。"),
            AdviceOption("27英寸", "近距离玩游戏，可按标称尺寸试坐并确认视野范围。", "实际观看距离需在自己的桌面上确认。"),
            AdviceOption("2304分区", "偏重暗场画面，可把这款列入对照，在实机观察亮暗交界。", "暗场光晕和运动拖影仍需实测。"))
        MonitorUse.PHOTO -> listOf(
            AdviceOption("OLED", "可用暗部渐变样张检查这款屏幕的显示效果。", "修图前核对色域覆盖、校准报告和亮度均匀性。"),
            AdviceOption("2304分区", "可用同一张明暗反差大的照片检查局部显示。", "分区数无法确定色准，需核对校准报告。"))
        MonitorUse.GENERAL -> listOf(
            AdviceOption("2304分区", "若在意明暗画面，可安排一次暗场对照。", "实看字幕边缘是否有可见光晕。"),
            AdviceOption("4K", "若重视清晰度，可试读文字并检查缩放。", "确认电脑支持所需输出模式。"),
            AdviceOption("240Hz", "若重视动态画面，可优先检查高刷新模式。", "确认所用接口和线材支持目标模式。"))
    }
    val available = candidates.filter { option -> fields.any { option.quote in it } }
    val relevant = when (plan.focus) {
        MonitorFocus.MOTION -> available.filter { it.quote.endsWith("Hz") }
        MonitorFocus.DARK -> available.filter { it.quote in setOf("OLED", "2304分区") }
        MonitorFocus.TEXT -> available.filter { it.quote == "4K" }
        null -> return available
    }
    // 同一维度缺项时明确补查，不能拿另一款的刷新率或分辨率作这款的依据。
    return relevant.ifEmpty {
        available.take(1).map { option ->
            when (plan.focus) {
                MonitorFocus.MOTION -> option.copy(advice = "这项规格无法判断高刷表现，建议先补查目标分辨率下的刷新率。",
                    check = "向商家确认可用刷新率和对应接口。")
                MonitorFocus.TEXT -> option.copy(advice = "可先按尺寸安排桌面，再确认分辨率和小字号显示。",
                    check = "单凭尺寸无法判断文字清晰度。")
                else -> option
            }
        }
    }
}

@Serializable
internal data class PairAdvice(val a: Int, val b: Int, val first: Int)

/** 对用户选的用途重新读取两个公开页。任一商品未核验时不生成双商品建议。 */
internal suspend fun compareMonitors(jd: JdGateway, settings: ModelSettings, plan: MonitorComparison): DeviceResponse {
    val config = settings.load() ?: return DeviceResponse.NeedsUser("请在「我的」配置生成对比建议的模型。")
    val sources = mutableListOf<DeviceSnapshot>()
    for (product in JdProduct.entries) {
        when (val result = jd.inspect(product.sku, false, null)) {
            is DeviceResponse.Ready -> sources += result.snapshot
            is DeviceResponse.NeedsUser -> return result
            is DeviceResponse.Failed -> return result
        }
    }
    val facts = sources.map { it.fields.getValue("title") + " ｜ " + it.fields.getValue("variant") }
    val choices = sources.map { options(it, plan) }
    if (choices.any { it.isEmpty() }) return DeviceResponse.NeedsUser("公开页缺少与这个用途对应的规格，请稍后重新核验。")
    val prompt = """
        为用户做两款显示器的选购决策。依据用户用途和真实规格，为 A、B 分别选一项最有帮助且能解释取舍的建议。
        决定下一步优先试用哪款：first 为 1 表示 A，2 表示 B，0 表示分别试用。若证据不足以优先试其中一款，选 0。
        输出三个整数字段 a、b、first 的 JSON。a 和 b 是各自建议的编号，从 1 开始。只返回 JSON 对象。
    """.trimIndent()
    return try {
        val input = buildString {
            appendLine("用途：${plan.focus?.label ?: plan.use.label}")
            for ((i, name) in listOf("A 海信 GX", "B 华硕 ROG").withIndex()) {
                appendLine("$name：${facts[i]}")
                choices[i].forEachIndexed { index, option -> appendLine("${index + 1}. ${option.quote}：${option.advice}") }
            }
        }
        val reply = requestModel(config, prompt, input).trim().removePrefix("```json").removeSuffix("```").trim()
        comparisonSnapshot(plan, sources, Json.decodeFromString<PairAdvice>(reply))
    } catch (_: IOException) {
        DeviceResponse.NeedsUser("两款商品已读取，模型服务暂未返回建议，请重试。")
    } catch (_: SerializationException) {
        DeviceResponse.NeedsUser("模型建议格式无效，请重新试运行。")
    }
}

/** 每款摘录按本款公开字段核对，数字与屏幕技术不能从另一款串入。事实、建议及未知项分开保存。 */
internal fun comparisonSnapshot(plan: MonitorComparison, sources: List<DeviceSnapshot>, answer: PairAdvice): DeviceResponse {
    val (a, b) = sources.zip(listOf(answer.a, answer.b)).map { (source, number) ->
        options(source, plan).getOrNull(number - 1)
            ?: return DeviceResponse.NeedsUser("模型选择的规格超出该款商品的已核验依据，请重新试运行。")
    }
    val priority = when (answer.first) {
        0 -> "建议两款分别试用"
        1 -> "建议先试海信 GX"
        2 -> "建议先试华硕 ROG"
        else -> return DeviceResponse.NeedsUser("模型没有返回有效的对比顺序，请重新试运行。")
    }
    val budget = plan.budgetYuan?.let { "预算上限 ${it} 元；两款当前是否在预算内均待核价。" }.orEmpty()
    val fields = mapOf(
        "advice" to "${plan.focus?.label ?: plan.use.label} · $priority\n海信 GX（${a.quote}）· ${a.advice}\n华硕 ROG（${b.quote}）· ${b.advice}",
        "hisense" to "「${a.quote}」 · ${sources[0].fields.getValue("variant")} · SKU ${sources[0].targetKey}",
        "rog" to "「${b.quote}」 · ${sources[1].fields.getValue("variant")} · SKU ${sources[1].targetKey}",
        "check" to (budget + a.check + b.check),
    )
    return DeviceResponse.Ready(DeviceSnapshot(JD_PACKAGE, MONITOR_PAIR, fields, "京东两款公开商品页 · 模型建议"))
}
