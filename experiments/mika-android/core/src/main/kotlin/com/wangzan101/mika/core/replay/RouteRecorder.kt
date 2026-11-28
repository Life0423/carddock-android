package com.wangzan101.mika.core.replay

import com.wangzan101.mika.core.agent.LearnGate
import com.wangzan101.mika.core.record.Redactor
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.tool.ToolIndex
import com.wangzan101.mika.core.verify.PriceKey
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.uistate.IdentityCodeScreen
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.UUID

/**
 * 把一次办成的运行记成路线。只看 [RunEvent.ActionTarget] 与接管、追问、确认、障碍、结局一类事件;
 * 有任何一项说明这次不该记(要用户出手、付款、密码框、防截屏、用了浏览器工具或沙箱工具),返回 null。
 */
object RouteRecorder {
    /** 用过就说明这次做法离不开用户或别的通道,照着回放不安全。 */
    private val NO_ROUTE_VERBS = setOf(Verb.AskUser, Verb.RequestConfirmation, Verb.HandToUser)
    private val WAITING_STATES = setOf("waiting_input", "waiting_takeover", "no_answer")
    private val PROTECTED = Regex("\"protected\"\\s*:\\s*true|\"state\"\\s*:\\s*\"protected\"")
    private val NOT_SECRET = Regex("\"secret\"\\s*:\\s*false")

    private val NAVIGATION_DESCRIPTION = Regex("图标|按钮|页签|入口|icon|button|tab", RegexOption.IGNORE_CASE)

    /** 槽值至少这么长:太短的字在原话里容易碰巧出现。 */
    private const val MIN_SLOT = 2

    /**
     * 输入的字只能是本次原话里恰好出现一次的片段(记成槽);不是的(固定词、追问来的、从别处抄来的)整条不记,
     * 路线里永远不存用户没在原话里说过的字。目标元素的名字在原话里出现时也必须绑成槽,回放时按新原话认。
     * 办没办成由调用方按 [com.wangzan101.mika.core.agent.LearnGate] 判过才来记,这里只看路线本身记不记得下。
     */
    fun record(goal: String, events: List<RunEvent>, nowMs: Long, tools: ToolIndex, trimBrowseDetours: Boolean = false, onSlots: (List<String>) -> Unit = {}): Route? {
        val recording = extract(goal, events, nowMs, tools, trimBrowseDetours)
        recording.route?.let { onSlots(recording.slots) }
        return recording.route
    }

    /** [reason] 只包含受控的失败类别和步骤号，避免把当次页面、输入和答复带入诊断。 */
    data class Recording(val route: Route? = null, val slots: List<String> = emptyList(), val reason: String = "")

    /** 自由操作与后台试查使用同一提取入口；固定模板需要可独立打开的应用入口。 */
    fun recordBrowse(goal: String, events: List<RunEvent>, nowMs: Long, tools: ToolIndex, verifiedProduct: PriceKey? = null): Recording {
        val result = extract(goal, events, nowMs, tools, trimBrowseDetours = true, verifiedProduct = verifiedProduct)
        val route = result.route ?: return result
        return if (tools.verb(route.steps.first().tool) == Verb.LaunchApp) result
            else Recording(reason = "首个有效动作没有从应用入口启动。")
    }

    private fun extract(goal: String, events: List<RunEvent>, nowMs: Long, tools: ToolIndex, trimBrowseDetours: Boolean, verifiedProduct: PriceKey? = null): Recording {
        fun rejected(reason: String) = Recording(reason = reason)
        // 保留槽语法不能与用户原话中的字面占位符混用，此类需求仍可一次性执行。
        if (Regex("""\{\d+\}""").containsMatchIn(goal)) return rejected("需求中含有保留的参数标记。")
        if (events.lastOrNull { it is RunEvent.TaskEnded }?.let { (it as RunEvent.TaskEnded).outcome } != "succeeded") return rejected("任务未以核实成功的答复结束。")
        events.firstOrNull { spoils(it, tools) }?.let { event ->
            val reason = when (event) {
                is RunEvent.Takeover -> "本次需要用户接管。"
                is RunEvent.Confirmation -> "本次涉及用户确认。"
                is RunEvent.State -> "本次因等待输入或接管而中断。"
                is RunEvent.Barrier -> "本次触及支付步骤。"
                is RunEvent.ToolResult -> if (PROTECTED.containsMatchIn(event.resultJson)) "第 ${event.step} 步页面受保护。"
                    else "第 ${event.step} 步用了路线以外的工具。"
                is RunEvent.ModelReply -> "第 ${event.step} 步请求了路线以外的工具。"
                is RunEvent.ActionTarget -> "第 ${event.step} 步不能从手机界面回放。"
                else -> "本次记录包含无法独立回放的步骤。"
            }
            return rejected(reason)
        }
        events.filterIsInstance<RunEvent.ActionTarget>().firstOrNull { it.incomplete }?.let { return rejected("第 ${it.step} 步缺少动作回执。") }

        var acts = events.filterIsInstance<RunEvent.ActionTarget>().filter { it.effective && tools[it.tool]?.routable == true }
        if (acts.isEmpty()) return rejected("没有记录到有效的手机操作。")
        if (acts.size > MAX_ROUTE_STEPS) return rejected("有效步骤超过路线长度上限。")
        if (acts.any { it.protected }) return rejected("动作发生在受保护的页面。")
        if (LearnGate.hasPickupCode(goal)) return rejected("需求含有当次取件凭据，无法写入模板。")
        val results = events.filterIsInstance<RunEvent.ToolResult>()

        // 只读试查可能打开详情再返回。保留到同一完整页面的首次路径；独立复验仍须走完整个候选。
        if (trimBrowseDetours) {
            val retained = mutableListOf<RunEvent.ActionTarget>()
            val checkpoints = mutableMapOf<Pair<String, JsonArray>, Int>()
            val byCall = results.associateBy { it.callId }
            for (act in acts) {
                val verb = tools.verb(act.tool)
                if (verb !in setOf(Verb.Tap, Verb.Back, Verb.Scroll, Verb.Swipe)) checkpoints.clear()
                retained += act
                val response = act.callId?.let(byCall::get)?.resultJson?.let(::parse)
                val page = response?.get("screen") as? JsonObject ?: continue
                val pkg = (page["currentApp"] as? JsonPrimitive)?.contentOrNull ?: continue
                if (page["full"] != JsonPrimitive(true) || page["screenChangedDuringRead"] == JsonPrimitive(true)) continue
                val elements = (page["elements"] as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()
                if (elements.count { (it["name"] as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true } < 2) continue
                val signature = pkg to JsonArray(elements.map { JsonObject(it - "ref") })
                val revisit = checkpoints[signature]
                if (revisit == null) checkpoints[signature] = retained.size
                else {
                    retained.subList(revisit, retained.size).clear()
                    checkpoints.entries.removeAll { it.value > revisit }
                }
            }
            acts = retained
        }

        // 设备可能在首次启动时让用户选择应用分身。独立复验从所请求的应用包启动；
        // 不把点系统选择器录入业务步骤，启动仍停在系统窗口时由回放拒绝。
        if (trimBrowseDetours && acts.size > 2 && tools.verb(acts[0].tool) == Verb.LaunchApp) {
            val launch = acts[0]
            val pkg = (parse(launch.argsJson)?.get("package") as? JsonPrimitive)?.contentOrNull
            val chooser = acts[1]
            if (pkg != null && launch.after.packageName in setOf("com.vivo.appfilter", "android", "com.android.intentresolver") &&
                chooser.before.packageName == launch.after.packageName && tools.verb(chooser.tool) == Verb.Tap &&
                chooser.after.packageName == pkg && acts[2].before.packageName == pkg) {
                acts = listOf(launch.copy(before = Anchors(null, emptyList()), after = chooser.after)) + acts.drop(2)
            }
        }

        // 首次同意只解释原记录怎样进入普通业务页；用同一次回执证明下一业务入口后删去这一动作。
        if (trimBrowseDetours && acts.size >= 3 && tools.verb(acts[0].tool) == Verb.LaunchApp) {
            val gate = acts[1]
            val next = acts[2]
            val sel = next.target
            val pkg = (parse(acts[0].argsJson)?.get("package") as? JsonPrimitive)?.contentOrNull
            if (gate.target?.name?.let(RouteAnchors::requiresUserConsent) == true && sel != null &&
                pkg != null && gate.after.packageName == pkg && next.before.packageName == pkg &&
                tools.verb(next.tool) == Verb.Tap && RouteAnchors.stable(sel.name)) {
                val result = results.singleOrNull { gate.callId != null && it.callId == gate.callId && it.tool == gate.tool }
                val page = result?.let { parse(it.resultJson)?.get("screen") as? JsonObject }
                val nodes = (page?.get("elements") as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()
                val matched = nodes.singleOrNull { it["role"] == JsonPrimitive(sel.role) && it["name"] == JsonPrimitive(sel.name) &&
                    (it["resourceId"] as? JsonPrimitive)?.contentOrNull == sel.resourceId }
                if (page != null && page["full"] == JsonPrimitive(true) && page["currentApp"] == JsonPrimitive(pkg) &&
                    listOf("screenChangedDuringRead", "truncated", "protected").none { page[it] == JsonPrimitive(true) } &&
                    matched != null && matched["bounds"] == JsonArray(sel.bounds.map(::JsonPrimitive))) {
                    acts = listOf(acts.first().copy(after = gate.after)) + acts.drop(2)
                }
            }
        }
        acts.firstOrNull { it.target?.name?.let(RouteAnchors::requiresUserConsent) == true }?.let {
            return rejected("第 ${it.step} 步需要用户同意，后续业务入口缺少可独立核实的回执。")
        }

        // 槽值按第一次用到的先后编号
        val slots = mutableListOf<String>()
        fun bind(v: String): Boolean {
            if (v in slots) return true
            if (v.trim().length < MIN_SLOT || occurrences(goal, v) != 1) return false
            slots += v
            return true
        }
        // 本机核实过的身份码任务只向记录器暴露固定导航词。可编辑的搜索输入仍按实际输入单独绑定。
        val navigationPackage = if (trimBrowseDetours)
            events.filterIsInstance<RunEvent.State>().lastOrNull {
                it.name == IdentityCodeScreen.ARRIVAL_EVENT || it.name == IdentityCodeScreen.TERMINAL_EVENT || it.name == IdentityCodeScreen.PICKUP_EVENT
            }?.detail else null
        val args = mutableListOf<JsonObject>()
        val targets = mutableListOf<Selector?>()
        for (a in acts) {
            fun failStep(reason: String) = rejected("第 ${a.step} 步$reason")
            val o = parse(a.argsJson) ?: return failStep("缺少有效的操作参数。")
            val verb = tools.verb(a.tool)
            // 没有树节点的历史点击也可还原：用同一个 @v 编号、快照和点击回执关联已复核的像素框。
            var selector = a.target
            if (verifiedProduct != null && verb == Verb.Tap && a == acts.last() && selector != null &&
                a.before.packageName == verifiedProduct.pkg && a.after.packageName == verifiedProduct.pkg) {
                val box = JsonArray(selector.bounds.map(::JsonPrimitive))
                val page = events.subList(0, events.indexOf(a)).asReversed().filterIsInstance<RunEvent.ToolResult>()
                    .mapNotNull { result -> parse(result.resultJson)?.let { it["screen"] as? JsonObject ?: it } }
                    .firstOrNull { it["currentApp"] == JsonPrimitive(verifiedProduct.pkg) && it["snapshotId"] == o["snapshotId"] && it["elements"] is JsonArray }
                val row = (page?.get("elements") as? JsonArray)?.filterIsInstance<JsonObject>()?.singleOrNull {
                    it["bounds"] == box && (o["ref"] == null || it["ref"] == o["ref"])
                }
                val raw = (row?.get("name") as? JsonPrimitive)?.contentOrNull.orEmpty()
                val label = raw.split(Regex("""限时立减|倒计时|券后价|到手价|[¥￥]|\d+(?:\.\d+)?元|已售"""), limit = 2)
                    .first().trim(' ', ',', '，')
                val productWords = Regex("""[A-Za-z]{3,}|[\u4e00-\u9fff]{2,6}""").findAll(verifiedProduct.product)
                if (label.length in 4..140 && productWords.any { label.contains(it.value, ignoreCase = true) })
                    selector = selector.copy(name = "", productLabel = label)
            }
            // “按名称找 → 看截图 → 点树节点”只证明历史位置，不证明图标形状。
            // 记下定位要求供下次重新看图；中间有其他动作、图框或名称不一致时不关联。
            if (trimBrowseDetours && verb == Verb.Tap && selector != null && a.before.packageName != null &&
                a.before.packageName == a.after.packageName && a.after.texts.filter(RouteAnchors::stable).distinct().size >= 2) {
                val prior = events.subList(0, events.indexOf(a)).asReversed()
                    .takeWhile { it !is RunEvent.ActionTarget }
                val reads = prior.filterIsInstance<RunEvent.ToolResult>().filterNot { it.tool == a.tool && it.callId == a.callId }
                val shot = reads.firstOrNull()?.let { it.tool == "phone_screenshot" &&
                    parse(it.resultJson)?.let { r -> r["ok"] == JsonPrimitive(true) && r["screenshotId"] is JsonPrimitive } == true }
                val find = reads.getOrNull(if (shot == true) 1 else 0)?.takeIf { it.tool == "phone_find_visually" }
                val hit = find?.let { parse(it.resultJson) }
                val query = find?.let { f -> prior.filterIsInstance<RunEvent.ModelReply>()
                    .singleOrNull { it.acted && it.step == f.step && it.tool == f.tool }?.argsJson?.let(::parse) }
                val description = (query?.get("target") as? JsonPrimitive)?.contentOrNull
                val sameControl = hit?.get("source") == JsonPrimitive("elements") && selector.name.isNotBlank() &&
                    query?.get("text") == JsonPrimitive(selector.name) && hit["box"] == JsonArray(selector.bounds.map(::JsonPrimitive))
                val samePixelRef = hit?.get("source") == JsonPrimitive("vision") && hit["unverified"] != JsonPrimitive(true) &&
                    o["ref"] != null && hit["ref"] == o["ref"] && o["snapshotId"] != null && hit["snapshotId"] == o["snapshotId"]
                if ((shot == true || query?.get("requirePixels") == JsonPrimitive(true)) &&
                    !description.isNullOrBlank() && description.length <= 120 && hit?.get("found") == JsonPrimitive(true) &&
                    (sameControl || samePixelRef)) {
                    selector = selector.copy(visualDescription = description, requireVisualCheck = true)
                }
            }
            if (verb == Verb.Tap && selector == null) {
                val hit = if (a.before.packageName != null && a.before.packageName == a.after.packageName &&
                    a.after.texts.filter(RouteAnchors::stable).distinct().size >= 2 && o["ref"] != null && o["snapshotId"] != null)
                    results.asSequence().filter { it.atMs <= a.atMs && tools.verb(it.tool) == Verb.FindVisually }
                        .mapNotNull { parse(it.resultJson) }.singleOrNull {
                            it["ref"] == o["ref"] && it["snapshotId"] == o["snapshotId"] && it["found"] == JsonPrimitive(true) &&
                                it["source"] == JsonPrimitive("vision") && it["unverified"] != JsonPrimitive(true)
                        } else null
                val box = (hit?.get("box") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.toIntOrNull() }
                val reply = results.singleOrNull { a.callId != null && it.callId == a.callId && it.tool == a.tool }?.let { parse(it.resultJson) }
                val description = (reply?.get("located") as? JsonPrimitive)?.contentOrNull
                if (description.isNullOrBlank() || box == null || box.size != 4 || box.any { it !in 0..999 } ||
                    box[0] >= box[2] || box[1] >= box[3]) return failStep("点击缺少可重新定位的目标。")
                selector = Selector("visual", "", null, box, 0, visualDescription = description)
            }
            // 理由和视觉描述可能包含结果值。保存实际动作所需的参数，定位时使用新快照。
            val discarded = setOf("ref", "snapshotId", "target", "reason") +
                if (verb == Verb.Tap) setOf("x", "y", "role", "element", "seen", "text") else emptySet()
            var cleaned = o - discarded
            if (tools.verb(a.tool) == Verb.TypeText) {
                val text = (o["text"] as? JsonPrimitive)?.contentOrNull ?: return failStep("缺少输入的参数。")
                // 端口没明确说不是密码框的输入一律不记
                if (text == Redactor.MASK) return failStep("输入内容已脱敏。")
                val r = a.callId?.let { id -> results.lastOrNull { it.callId == id } } ?: return failStep("输入没有配对的回执。")
                if (!NOT_SECRET.containsMatchIn(r.resultJson)) return failStep("输入框未确认可以记录。")
                if (bind(text)) {
                    cleaned = cleaned + ("text" to JsonPrimitive("{${slots.indexOf(text) + 1}}"))
                } else {
                    // 已核实商品的搜索词可有空格、大小写或代际写法差异。固定文本限于同应用的公开商品词。
                    val product = verifiedProduct?.takeIf { it.pkg == a.before.packageName }
                        ?: return failStep("输入值无法唯一绑定到需求参数。")
                    fun normalize(value: String) = Regex("第([一二三四五六七八九])代").replace(value.lowercase()) { match ->
                        ("一二三四五六七八九".indexOf(match.groupValues[1]) + 1).toString()
                    }
                    val words = normalize(text).split(Regex("""[\s/()（）-]+""")).filter(String::isNotBlank)
                    val catalog = normalize(product.product + " " + product.variant)
                    if (words.isEmpty() || words.any { it !in catalog } || words.none { it.length >= MIN_SLOT && it in normalize(product.product) })
                        return failStep("输入值与本次核实的商品不一致。")
                }
            }
            args += JsonObject(cleaned)
            targets += selector?.let { t ->
                val n = t.name
                when {
                    n.isEmpty() -> t
                    trimBrowseDetours && verb == Verb.Tap && n !in slots &&
                        (navigationPackage != null && a.before.packageName == navigationPackage ||
                            t.uniqueAtRecord && n in RouteAnchors.navigationLabels) -> t
                    // 带当前视觉定位证据的固定导航词可在说明中反复出现；用户输入仍必须唯一绑定。
                    occurrences(goal, n) > 1 && RouteAnchors.stable(n) && n !in slots &&
                        (t.uniqueAtRecord && n in RouteAnchors.navigationLabels || t.visualDescription?.let { d ->
                            n in d && NAVIGATION_DESCRIPTION.containsMatchIn(d)
                        } == true) -> t
                    trimBrowseDetours && verb == Verb.Tap && n !in slots && t.uniqueAtRecord &&
                        (o["ref"] as? JsonPrimitive)?.contentOrNull?.startsWith("@e") == true && RouteAnchors.stable(n) &&
                        (occurrences(goal, n) != 1 || n.length < MIN_SLOT) -> t
                    n in goal -> if (bind(n)) t.copy(name = "{${slots.indexOf(n) + 1}}") else return failStep("目标名称无法唯一绑定到需求参数。")
                    // 名字里夹着本次的参数(如「发给张三」):不按名字认
                    slots.any { it in n } -> t.copy(name = "", uniqueAtRecord = false)
                    else -> t
                }
            }
        }
        // 槽在原话里的位置不能互相重叠(短的被长的吞掉就没法单独换)
        val ranges = slots.map { v -> goal.indexOf(v).let { it until it + v.length } }
        if (ranges.indices.any { i -> ranges.indices.any { j -> i < j && ranges[i].first <= ranges[j].last && ranges[j].first <= ranges[i].last } }) return rejected("需求中的参数范围重叠。")
        var template = goal
        ranges.withIndex().sortedByDescending { it.value.first }.forEach { (i, r) -> template = template.replaceRange(r, "{${i + 1}}") }

        fun anchors(a: Anchors, target: Selector?) = Anchors(a.packageName, a.texts.map(String::trim).filter { s ->
            RouteAnchors.stable(s) && s != target?.name?.trim() && slots.none { it.trim() in s }
        }.distinct().take(RouteAnchors.MAX))

        val steps = acts.mapIndexed { i, a ->
            val target = targets[i]?.let { t -> t.copy(visualDescription = t.visualDescription?.let { description ->
                slots.withIndex().fold(description) { value, (index, slot) -> value.replace(slot, "{${index + 1}}") }
            }) }
            RouteStep(a.tool, args[i].toString(), target, anchors(a.before, a.target), anchors(a.after, null))
        }
        val packages = (acts.mapNotNull { a -> if (tools.verb(a.tool) == Verb.LaunchApp) parse(a.argsJson)?.get("package")?.let { (it as? JsonPrimitive)?.contentOrNull } else null } +
            acts.flatMap { listOfNotNull(it.before.packageName, it.after.packageName) }).distinct()
        val draft = Route(UUID.randomUUID().toString(), template, slots.size, packages, emptyMap(), steps, lastUsedMs = nowMs)
        val route = withReadCheckpoints(draft, if (trimBrowseDetours) events.filter { it !is RunEvent.ActionTarget || it in acts } else events, slots)
        val encoded = RouteCodec.encode(route)
        if (LearnGate.hasPickupCode(encoded) || LearnGate.privateCodeQuery(goal) && (
                slots.any { value -> value.any(Char::isDigit) } ||
                steps.any { step -> step.target?.visualDescription?.any(Char::isDigit) == true })) return rejected("路线包含当次取件凭据。")
        return if (encoded.toByteArray().size <= MAX_ROUTE_BYTES) Recording(route, slots.toList())
            else rejected("路线超出存储大小上限。")
    }

    /** 从原回执补齐入口或终点证据；原槽值只用于核对选择器，不写入检查点。 */
    fun withReadCheckpoints(route: Route, events: List<RunEvent>, slots: List<String> = emptyList()): Route {
        if (route.packages.size != 1 || route.steps.firstOrNull()?.tool != "phone_launch_app") return route
        val actions = events.filterIsInstance<RunEvent.ActionTarget>().filter { it.effective }
        if (actions.map { it.tool } != route.steps.map { it.tool }) return route
        val results = events.filterIsInstance<RunEvent.ToolResult>()
        val next = 1 + route.steps.drop(1).takeWhile { it.tool == "phone_back" }.size
        if (next > 1 && route.steps.getOrNull(next)?.tool == "phone_tap") {
            val target = route.steps[next].target ?: return route
            val name = Replayer.fill(target.name, slots) ?: return route
            val pkg = route.packages.single()
            if (name.isBlank() || !target.uniqueAtRecord || actions[next].target != target.copy(name = name) ||
                route.steps[next].before.packageName != pkg) return route
            val back = actions[next - 1]
            val result = results.singleOrNull { it.tool == back.tool && it.callId != null && it.callId == back.callId } ?: return route
            val page = parse(result.resultJson)?.get("screen") as? JsonObject ?: return route
            if (page["full"] != JsonPrimitive(true) || page["truncated"] == JsonPrimitive(true) || page["screenChangedDuringRead"] == JsonPrimitive(true) ||
                page["protected"] == JsonPrimitive(true) || page["currentApp"] != JsonPrimitive(pkg)) return route
            val nodes = (page["elements"] as? JsonArray)?.filterIsInstance<JsonObject>() ?: return route
            // 同一份完整读屏须同时包含导航行和原点击唯一入口；页面在返回与点击之间可能已切换。
            val entrance = nodes.singleOrNull {
                it["role"] == JsonPrimitive(target.role) && it["name"] == JsonPrimitive(name) &&
                    (it["resourceId"] as? JsonPrimitive)?.contentOrNull == target.resourceId
            } ?: return route
            if (entrance["bounds"] != JsonArray(target.bounds.map(::JsonPrimitive))) return route
            val rows = RouteAnchors.buttonRows(nodes.asSequence().mapNotNull { item ->
                val label = (item["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                if (label == name || slots.any { it in label }) return@mapNotNull null
                val role = (item["role"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                val bounds = (item["bounds"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.toIntOrNull() } ?: return@mapNotNull null
                Triple(role, label, bounds)
            })
            // 整页滚动容器会遮去全部普通锚点。原回执里并排的三个按钮可与下一步唯一入口交叉核对。
            val row = rows.maxByOrNull { it.size } ?: return route
            return route.copy(entryButtonRow = row.take(3))
        }
        // 参数入口仍须实际执行。仅在无名入口的下一步识别已出现的固定页签。
        // 已存模板的独立复验可以补齐旧卡证据，必须由同一路线每一步的成功回执证明。
        val tabIndex = route.steps.indexOfLast { it.tool == "phone_tap" }
        if (tabIndex < 2 || route.steps.subList(1, tabIndex + 1).any { it.tool != "phone_tap" } ||
            !route.hasNavigationTail(tabIndex)) return route
        val replay = events.filterIsInstance<RunEvent.Replay>()
        if (replay.isNotEmpty()) {
            if (replay.size != route.steps.size || replay.withIndex().any { (i, event) ->
                    event.routeId != route.id || event.step != i + 1 || event.total != route.steps.size || !event.matched
                } || events.filterIsInstance<RunEvent.OutcomeJudged>().lastOrNull()?.outcome != "done") return route
        } else if (actions.indices.any { i ->
            val saved = route.steps[i].target
            actions[i].target != saved?.copy(name = Replayer.fill(saved.name, slots) ?: return route)
        }) return route
        val last = actions[tabIndex]
        val target = last.target ?: return route
        val savedTab = route.steps[tabIndex].target ?: return route
        if (savedTab.name != target.name || savedTab.role != target.role || savedTab.resourceId != target.resourceId) return route
        val pkg = route.packages.single()
        val answer = events.filterIsInstance<RunEvent.TaskEnded>().lastOrNull { it.outcome == "succeeded" }?.answerOrReason ?: return route
        // 用动作自己的回执关联完整画面，忽略两次点击之间插入的读屏和过渡帧。
        fun screenAfter(action: RunEvent.ActionTarget): List<JsonObject>? {
            val id = action.callId ?: return null
            val result = results.singleOrNull { it.callId == id && it.tool == action.tool } ?: return null
            val page = parse(result.resultJson)?.get("screen") as? JsonObject ?: return null
            if (page["currentApp"] != JsonPrimitive(pkg) || page["full"] != JsonPrimitive(true) ||
                listOf("truncated", "protected", "screenChangedDuringRead").any { page[it] == JsonPrimitive(true) }) return null
            return (page["elements"] as? JsonArray)?.filterIsInstance<JsonObject>()
        }
        fun name(e: JsonObject) = (e["name"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        fun atSelector(e: JsonObject, sel: Selector) = e["role"] == JsonPrimitive(sel.role) &&
            (e["resourceId"] as? JsonPrimitive)?.contentOrNull == sel.resourceId &&
            e["bounds"] == JsonArray(sel.bounds.map(::JsonPrimitive))
        val after = screenAfter(last) ?: return route
        if (target.bounds.size != 4 || !RouteAnchors.stable(target.name) ||
            after.count { name(it) == target.name && atSelector(it, target) } != 1) return route
        val peers = after.filter { item ->
            val box = item["bounds"] as? JsonArray
            val top = (box?.getOrNull(1) as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
            val bottom = (box?.getOrNull(3) as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
            item["role"] == JsonPrimitive(target.role) && top != null && bottom != null &&
                (top + bottom) / 2 in target.bounds[1]..target.bounds[3]
        }.map(::name).filter { it != target.name && RouteAnchors.stable(it) }.toSet()
        val entrance = actions[tabIndex - 1].target
        val landing = screenAfter(actions[tabIndex - 2])
        val original = entrance?.let { sel -> landing?.singleOrNull { atSelector(it, sel) } }
        val anonymous = original != null && entrance.name.isEmpty() && entrance.resourceId == null &&
            entrance.visualDescription == null && (entrance.role.endsWith("Layout") || entrance.role == "ViewGroup") &&
            slots.none { it.isNotEmpty() && name(original).contains(it, ignoreCase = true) }
        if (route.slots != 0 && !anonymous) return route
        // 横向滚动会让上层栏目标题离开屏幕。终点只从目标页签下方的内容取证。
        val afterLabels = after.filter { item ->
            val box = item["bounds"] as? JsonArray
            val top = (box?.getOrNull(1) as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
            top != null && top >= target.bounds[3]
        }.map(::name).toSet()
        val before = screenAfter(actions[tabIndex - 1])?.map(::name)?.toSet()
        // 独立复验中页签前帧可能正过渡。此时用已核实答复与稳定的点击后画面交叉取证。
        if (before == null && replay.isEmpty()) return route
        val quotes = Regex("[「“]([^」”\\n]{2,12})[」”]").findAll(answer).map { it.groupValues[1].trim() }
        val terminal = quotes.filter { RouteAnchors.stable(it) && it != target.name && it !in peers &&
            it in afterLabels && it !in before.orEmpty() && it !in slots }.toSet()
        if (peers.size < 2 || terminal.size < 2) return route
        return route.copy(terminalLabels = terminal, navigationPeers = peers, navigationTabIndex = tabIndex,
            verifiedAnonymousEntrance = anonymous, requireLiveTabEvidence = before == null)
    }

    private fun occurrences(s: String, v: String): Int {
        var n = 0; var i = s.indexOf(v)
        while (i >= 0) { n++; i = s.indexOf(v, i + 1) }
        return n
    }

    private fun spoils(e: RunEvent, tools: ToolIndex): Boolean = when (e) {
        is RunEvent.Takeover, is RunEvent.Confirmation -> true
        is RunEvent.State -> e.name in WAITING_STATES
        is RunEvent.Barrier -> e.kind == "payment"
        is RunEvent.ModelReply -> e.tool?.let { barred(it, tools) } == true
        is RunEvent.ToolResult -> barred(e.tool, tools) || PROTECTED.containsMatchIn(e.resultJson)
        is RunEvent.ActionTarget -> barred(e.tool, tools)
        else -> false
    }

    /** 路线只记能回放的那种界面上的做法:用过别的能力(网页、沙箱)的工具,照着回放不完整。 */
    private fun barred(tool: String, tools: ToolIndex): Boolean {
        val t = tools[tool] ?: return false
        if (t.verb in NO_ROUTE_VERBS) return true
        val routed = tools.names.mapNotNull { tools[it]?.takeIf { i -> i.routable }?.capability }.toSet()
        return t.capability != null && t.capability !in routed
    }

    private fun parse(s: String): JsonObject? = try { Json.parseToJsonElement(s) as? JsonObject } catch (e: Exception) { null }
}
