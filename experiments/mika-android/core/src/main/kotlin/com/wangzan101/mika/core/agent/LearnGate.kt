package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.userscreen.UserScreenMessage
import com.wangzan101.mika.core.observation.Place
import com.wangzan101.mika.core.record.RunEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 一次运行能不能当成「办成了」来学:个人技能和 App 知识的起草、经验路线的记录与成功计数、
 * 试用的技能和 App 知识转正式,都只问这一处。
 *
 * 看交出回答后判的结局([OutcomeJudge]),不看最终核查:交出了回答([TaskState.Succeeded])、结局是 [RunOutcome.Done]、
 * 没有退回一次后仍没对上的值;核查跑过的([TaskState.Succeeded.reviewed])还要核实通过——跑过却没通过照样挡住,没跑不挡。
 */
object LearnGate {
    fun done(end: TaskState, outcome: RunOutcome): Boolean =
        end is TaskState.Succeeded && outcome == RunOutcome.Done && end.unconfirmed.isEmpty() && !end.doubtful

    /** 核实过且有本轮来源的答复可以交用户确认口径。 */
    fun verifiedRead(end: TaskState.Succeeded) = end.reviewed && end.verified && end.unconfirmed.isEmpty() && end.sources.isNotEmpty()

    /** 标注整个取件工作流；诊断中出现码的任务也可能只说“快递”或“驿站”。 */
    fun pickupQuery(goal: String) = listOf("待取", "取件", "提货码", "取货码", "快递", "包裹", "驿站").any { it in goal }
    /** 身份码页面与取件查询共用诊断脱敏；两者的业务结果分别核验。 */
    fun identityCodeQuery(goal: String) = "身份码" in goal
    const val IDENTITY_ARRIVAL_SCOPE = "只验证打开身份码页，每次说明展示状态是否核实"
    /** 只有明确要求单验导航的原话或确认口径才降低验收目标。 */
    fun identityArrivalOnly(scope: String) = arrivalScope.matches(scope.trim())
    private val arrivalScope = Regex("""(?:请)?(?:只|仅)(?:需要|需|要)?(?:验证|核对|确认)?(?:打开|到达|进入)(?:手机)?(?:淘宝|菜鸟)?(?:内的?|里的?|的)?(?:我的|个人|本人)?身份码(?:页面|页)?(?:即可|就行)?(?:[，,。；;]\s*(?:每次说明展示状态是否核实|不(?:要求|核对|验证|核实)(?:二维码|码)(?:的)?(?:是否)?(?:可用|展示|显示)))?[。！!]?""")
    fun privateCodeQuery(goal: String) = identityCodeQuery(goal) || pickupQuery(goal)

    /** 身份码只认本机末屏核对事件，旧回执里的模型答复或“身份码”入口不构成证明。 */
    fun identitySource(requested: String, events: List<RunEvent>, apps: List<Pair<String, String>>, allowArrival: Boolean = false): String? =
        events.filterIsInstance<RunEvent.State>().lastOrNull { it.name == com.wangzan101.mika.core.uistate.IdentityCodeScreen.TERMINAL_EVENT ||
            allowArrival && it.name == com.wangzan101.mika.core.uistate.IdentityCodeScreen.ARRIVAL_EVENT }
            ?.detail?.takeIf { matchesIdentitySource(requested, it, apps) }

    /** 安装标签可能是“手机淘宝”，用户写“淘宝”时仍须核对其真实包名。 */
    fun matchesIdentitySource(requested: String, actual: String, apps: List<Pair<String, String>>): Boolean =
        apps.any { it.first == actual } && matchesPickupSource(requested, actual, apps +
            listOf("com.taobao.taobao" to "淘宝", "com.cainiao.wireless" to "菜鸟"))
    private val emptyAnswer = Regex("(暂无|没有|无|没有任何|还没有)(可供|可用|需要|待领取的|待领取|待取件的|待取的|待取件|待取|可取的|待提取的)?[的\\s]*(快递|包裹|取件码|提货码|取货码)|待取.{0,5}[为共]?[零0]件")
    private val emptyPage = Regex("(暂无|还没有|没有|无)(待取件的|待领取的|待取的|待取件|待取|可取的)(快递|包裹)|待取(快递|包裹)[：:]?\\s*[0零]件")
    private val unavailablePage = Regex("加载中|正在加载|请先登录|登录后查看|立即登录|网络异常|加载失败")
    private val pickupCredential = Regex("""(?:快递码|取件码|提货码|取货码|领取码|提取码)\s*(?:是|为)?\s*[：:为是「“【(（]*\s*([A-Za-z0-9０-９]+(?:[-－ ][A-Za-z0-9０-９]+)*)""")

    /** 只识别标签旁包含数字的凭据，诊断和模板均不得复述。 */
    fun hasPickupCode(text: String) = pickupCredential.findAll(text).any { it.groupValues[1].any(Char::isDigit) }

    fun pickupCodeQuery(goal: String) = listOf("取件码", "提货码", "取货码", "快递码").any { it in goal }
    fun claimsEmptyPickup(goal: String, answer: String) = pickupQuery(goal) && emptyAnswer.containsMatchIn(answer)

    /** 页面必须来源于本次最终动作之后的手机读屏，且这条读取能与最终回答的来源对上。 */
    private fun pickupPage(end: TaskState.Succeeded, events: List<RunEvent>, expectedPackage: String?): String? {
        if (!end.reviewed || !end.verified || end.unconfirmed.isNotEmpty()) return null
        val sources = end.sources.ifEmpty { events.filterIsInstance<RunEvent.Sources>().lastOrNull()?.items.orEmpty() }
        val lastWrite = events.filterIsInstance<RunEvent.ActionTarget>().lastOrNull { it.effective } ?: return null
        val pkg = expectedPackage ?: lastWrite.after.packageName ?: return null
        val read = events.filterIsInstance<RunEvent.Observed>().lastOrNull { it.kind == "read" && it.inputs != null } ?: return null
        if (read.untrusted || read.source != "phone" || read.place != Place.app(pkg) ||
            sources.none { it.obsId == read.id && it.place == pkg } || unavailablePage.containsMatchIn(read.text)) return null
        if (read.atMs >= lastWrite.atMs) {
            // 输入框里的字可能正是搜索问题或旧码；它们不构成待取结果。
            val inputs = (read.inputs.orEmpty() + listOfNotNull(read.focused)).map { it.filterNot(Char::isWhitespace) }
                .filter(String::isNotEmpty)
            return read.text.lineSequence().filterNot { line ->
                val text = line.filterNot(Char::isWhitespace)
                inputs.any { input -> input in text }
            }.joinToString("\n")
        }
        // 内联读屏先于 ActionTarget 入账；仅允许本动作配对回执中的稳定完整画面再次证明结果。
        val response = events.filterIsInstance<RunEvent.ToolResult>().lastOrNull { lastWrite.callId != null &&
            it.callId == lastWrite.callId && it.tool == lastWrite.tool } ?: return null
        val page = runCatching { (Json.parseToJsonElement(response.resultJson) as? JsonObject)?.get("screen") as? JsonObject }.getOrNull()
            ?: return null
        if (page["currentApp"] != JsonPrimitive(pkg) || page["full"] != JsonPrimitive(true) ||
            listOf("truncated", "protected", "screenChangedDuringRead").any { page[it] == JsonPrimitive(true) }) return null
        val labels = (page["elements"] as? JsonArray)?.filterIsInstance<JsonObject>()?.filter {
            it["editable"] != JsonPrimitive(true) && it["secret"] != JsonPrimitive(true) && it["hint"] != JsonPrimitive(true)
        }?.mapNotNull { (it["name"] as? JsonPrimitive)?.content } ?: return null
        return labels.joinToString("\n").takeUnless { unavailablePage.containsMatchIn(it) }
    }

    /** 只接受独立的完整空态标签；用于比对现场页面，不把用户查询文字当结果。 */
    fun isEmptyPickupLabel(text: String) = emptyPage.matches(text.trim().trimEnd('。', '！', '!'))

    /** 已核对原文与目标应用的一份明确空态可以作为结果；历史样例和模型转述不能证明空态。 */
    fun verifiedEmptyPickup(goal: String, end: TaskState.Succeeded, events: List<RunEvent>, expectedPackage: String? = null): Boolean {
        if (!claimsEmptyPickup(goal, end.answer)) return false
        val page = pickupPage(end, events, expectedPackage) ?: return false
        return page.lineSequence().any(::isEmptyPickupLabel) && !hasPickupCode(page)
    }

    /** 有件时只收本轮终点页面实际标为取件码的值，返回布尔结果，避免凭据进入诊断。 */
    fun verifiedPickupCodes(end: TaskState.Succeeded, events: List<RunEvent>, expectedPackage: String?): Boolean {
        val page = pickupPage(end, events, expectedPackage) ?: return false
        fun codes(text: String) = pickupCredential.findAll(text).map { it.groupValues[1] }.filter { code -> code.any(Char::isDigit) }.toSet()
        val actual = codes(page)
        val answer = codes(end.answer)
        // 无标签回复也只接受完整字母数字串；给出长号的一段、增添或遗漏一项均不算匹配。
        val tokens = Regex("""(?<![A-Za-z0-9０-９-])[A-Za-z0-9０-９]+(?:[-－][A-Za-z0-9０-９]+)*(?![A-Za-z0-9０-９-])""")
            .findAll(end.answer).map { it.value }.filter { token -> token.any(Char::isDigit) }.toSet()
        val reported = answer.ifEmpty { tokens }
        return actual.isNotEmpty() && reported == actual
    }

    /** 历史答复即使标为办成，恢复时仍要用末屏与来源核实；已脱敏的码值无法复核。 */
    fun verifiedPickupResult(goal: String, requested: String, end: TaskState.Succeeded, events: List<RunEvent>,
        apps: List<Pair<String, String>>): Boolean {
        val pkg = events.filterIsInstance<RunEvent.ActionTarget>().lastOrNull { it.effective }?.after?.packageName ?: return false
        if (!matchesPickupSource(requested, pkg, apps)) return false
        return if (claimsEmptyPickup(goal, end.answer)) verifiedEmptyPickup(goal, end, events, pkg)
            else verifiedPickupCodes(end, events, pkg)
    }

    /** 用户确认的应用以展示名匹配，未限定来源时使用本轮实际终点。 */
    fun matchesPickupSource(requested: String, actual: String?, apps: List<Pair<String, String>>): Boolean {
        if (actual == null || apps.isEmpty()) return false
        val named = apps.filter { (_, name) -> name.isNotBlank() && name in requested }
        // 明确指定唯一来源时，句中提及的备用应用或排除项都不能放宽范围。
        val only = named.filter { (_, name) -> Regex("(?:仅|只)(?:能)?(?:从|在|用)(?:手机)?\\s*" + Regex.escape(name)).containsMatchIn(requested) }
        if (only.isNotEmpty()) return only.any { it.first == actual }
        val excluded = named.filter { (_, name) ->
            Regex("(?:不要|别|不)(?:跳转到|跳转至|跳到|跳去|切到|转到|使用|用|打开|进入|去)(?:独立的?|单独的?)?\\s*" +
                Regex.escape(name)).containsMatchIn(requested)
        }
        if (excluded.any { it.first == actual }) return false
        val allowed = named - excluded.toSet()
        return allowed.isEmpty() || allowed.any { it.first == actual }
    }

    /**
     * 这次运行记不记成经验路线:开着路线、办成了([done])、有运行编号(路线从落盘的事件里取)。
     * 目标带着用户屏幕的不记:意思在屏幕上,按字记的路线下次对不上。
     */
    fun shouldSaveRoute(routesOn: Boolean, end: TaskState, outcome: RunOutcome, runId: String?, goal: String): Boolean =
        routesOn && done(end, outcome) && runId != null && !UserScreenMessage.has(goal)
}
