package com.life0423.carddock

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.life0423.carddock.core.model.TemplateDefinition
import com.life0423.carddock.core.model.TemplateKind
import com.life0423.carddock.core.port.DiscoveryResult
import com.life0423.carddock.core.rule.OfficialAction
import java.text.Normalizer
import java.util.Locale

internal const val LAUNCHER_SOURCE = "android.launcher"

/** 每条记录是当前用户可启动的桌面组件。不同别名可以有不同图标或入口。 */
internal data class LocalApp(val label: String, val key: String) {
    val packageName get() = key.substringBefore('/')
    fun template(prompt: String) = TemplateDefinition("launch-$key", "打开$label",
        "使用手机上的「$label」桌面入口。", TemplateKind.QUERY, prompt,
        nativeRule("launch-$key", LAUNCHER_SOURCE, key, "entry" to "应用").copy(officialAction = OfficialAction.OPEN_APP))
}

internal fun Context.launchableApps(pkg: String? = null): List<LocalApp> {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg)
    return packageManager.queryIntentActivities(intent, 0).filter { it.activityInfo.exported }.map {
        val info = it.activityInfo
        LocalApp(it.loadLabel(packageManager).toString(), ComponentName(info.packageName, info.name).flattenToShortString())
    }.distinctBy { it.key }.sortedWith(compareBy({ it.label }, { it.key }))
}

/** 更新后桌面组件可能改名，只有原包仍有唯一入口时才跟随。 */
internal fun List<LocalApp>.resolveSaved(key: String): LocalApp? =
    firstOrNull { it.key == key } ?: singleOrNull { it.packageName == key.substringBefore('/') }

internal fun appNameKey(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC)
    .lowercase(Locale.ROOT).filterNot(Char::isWhitespace)

internal fun List<LocalApp>.byAppName(name: String): List<LocalApp> {
    val key = appNameKey(name)
    if (key.isEmpty()) return emptyList()
    val exact = filter { appNameKey(it.label) == key }
    return exact.ifEmpty { filter { key in appNameKey(it.label) } }
}

internal val openPrefix = Regex("""^(?:请)?(?:帮我|给我|我想要?|麻烦)?(?:直接|立即|一下)?(?:打开|启动|进入)(?:一下)?""")
private val appContext = Regex("""^(?:请)?(?:帮我|给我|我想要?)?(?:在|使用|用|去)""")
private val continuation = Regex("""^(?:[（(「]|里|上|中|内|的|并|然后|再|搜索|查询|查看|选择|推荐|找|买|点|，|,|\s)""")

/** 单纯打开的指令可直接处理，复合需求先尝试已接入的专项能力。 */
internal data class LocalAppProposal(val result: DiscoveryResult, val compound: Boolean = false)

internal fun appProposal(demand: String, apps: List<LocalApp>): LocalAppProposal? {
    val text = demand.trim().trimEnd('。', '！', '!', '？', '?')
    val direct = openPrefix.find(text)
    val prefix = direct ?: appContext.find(text) ?: return null
    val rest = text.substring(prefix.range.last + 1).trim()
    val name = rest.trim('「', '」', '“', '”')
    if (name.isBlank()) return LocalAppProposal(DiscoveryResult.Incomplete("请写出要打开的应用名，或从本机应用中选择。"))
    val matches = if (direct != null) apps.byAppName(name) else emptyList()
    if (matches.isNotEmpty()) {
        val exact = matches.singleOrNull()?.takeIf { appNameKey(it.label) == appNameKey(name) }
        return LocalAppProposal(DiscoveryResult.Plans(matches.map { it.template(demand) },
            exact?.let { "已识别「${it.label}」。" } ?: "请选择要打开的本机应用。"))
    }
    // 原有商品语法允许应用名紧接商品名，此时交给商品规划器。
    if (com.life0423.carddock.ai.explicitMeal(demand) != null) return null
    val normalized = appNameKey(rest)
    val prefixMatches = apps.filter {
        val label = appNameKey(it.label)
        label.isNotEmpty() && normalized.startsWith(label) && continuation.containsMatchIn(normalized.removePrefix(label))
    }
    val longest = prefixMatches.maxOfOrNull { appNameKey(it.label).length }
    val scopedName = if (direct == null) Regex("""^(.+?)(?:里面|里|中|上|内)(.+)$""").matchEntire(rest)?.groupValues?.get(1) else null
    val candidates = if (longest != null) prefixMatches.filter { appNameKey(it.label).length == longest }
        else scopedName?.let { apps.byAppName(it) }.orEmpty()
    if (candidates.isNotEmpty()) {
        val question = candidates.singleOrNull()?.let { "已识别「${it.label}」。可以打开应用；需求中的后续操作尚未接入。" }
            ?: "找到多个可能的本机应用，请选择要打开的入口。"
        return LocalAppProposal(DiscoveryResult.Plans(candidates.map { it.template(demand) }, question), compound = true)
    }
    if (direct == null && scopedName == null) return null
    return LocalAppProposal(DiscoveryResult.Incomplete("应用识别失败：当前手机未找到与「${scopedName ?: name}」匹配的可启动应用。"),
        compound = direct == null)
}

/** 点击时重新查询系统桌面入口，返回可显示的失败原因。成功由目标应用接管前台。 */
internal fun Context.launchSavedApp(key: String): String? {
    val app = launchableApps(key.substringBefore('/')).resolveSaved(key)
        ?: return "应用识别失败：本机已找不到保存的应用入口。"
    val intent = Intent.makeMainActivity(ComponentName.unflattenFromString(app.key)!!).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        startActivity(intent)
        null
    } catch (_: ActivityNotFoundException) {
        "已识别「${app.label}」，启动失败，入口已变化。"
    } catch (_: SecurityException) {
        "已识别「${app.label}」，启动失败，系统限制了此入口。"
    }
}
