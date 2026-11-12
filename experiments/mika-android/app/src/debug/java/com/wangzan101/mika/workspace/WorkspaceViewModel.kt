package com.wangzan101.mika.workspace

import android.app.ActivityManager
import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.room.withTransaction
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.experiment.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

internal data class WorkspaceState(
    val ready: Boolean = false,
    val cards: List<WorkspaceCard> = emptyList(),
    val request: String = MockCompiler.template,
    val candidateJson: String = "",
    val candidateSource: String = CAINIAO_PACKAGE,
    val canConfirm: Boolean = false,
    val busy: Boolean = false,
    val message: String = "先从模板创建；只支持菜鸟明确空状态。",
    val outcome: String = "READY",
    val step: Int = 0,
    val elapsedMs: Long = 0,
    val modelCallsDelta: Long = 0,
    val ignoredStarts: Int = 0,
    val createdId: String = "",
    val selected: String? = null,
    val history: List<WorkspaceRun> = emptyList(),
    val operationId: String = "",
    val lastPackage: String? = null,
    val shoppingTask: ShoppingTask? = null,
    val replacementCardId: String? = null,
)

private data class WorkspaceTrial(val rule: ValidatedRule, val order: MeituanOrderTarget?, val run: WorkspaceRun, val result: RuleResult)

internal class WorkspaceViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MikaApp
    private companion object { var database: WorkspaceDatabase? = null }
    private val db = database ?: Room.databaseBuilder(app, WorkspaceDatabase::class.java, "workspace-v1.db")
        .addMigrations(workspaceMigration1To2, workspaceMigration2To3).build().also { database = it }
    private val dao = db.dao()
    private val mutable = MutableStateFlow(WorkspaceState())
    val state = mutable.asStateFlow()
    private var candidate: ValidatedRule? = null
    private var candidateOrder: MeituanOrderTarget? = null
    private var passed: WorkspaceTrial? = null
    private var job: Job? = null
    private var querying = false
    private var workspaceTaskId: Int? = null
    private val jdVerifier by lazy { JdShareVerifier() }
    private val evidenceDir = File(app.getExternalFilesDir(null), "workspace")

    init {
        viewModelScope.launch {
            dao.recoverInterrupted()
            mutable.value = state.value.copy(ready = true, cards = dao.cards(), shoppingTask = dao.shoppingTask())
            export()
        }
    }

    fun attachWorkspaceTask(taskId: Int) {
        workspaceTaskId = taskId
    }

    fun editRequest(text: String) {
        if (state.value.busy) return
        candidate = null; candidateOrder = null; passed = null
        mutable.value = state.value.copy(request = text, candidateJson = "", canConfirm = false, replacementCardId = null,
            outcome = "DRAFT", message = "需求已变更，请重新匹配并试运行。")
    }

    fun compile() {
        if (!state.value.ready || state.value.busy) return
        passed = null; candidate = null; candidateOrder = null
        mutable.value = state.value.copy(operationId = UUID.randomUUID().toString())
        if (!MockCompiler.supports(state.value.request)) {
            mutable.value = state.value.copy(candidateJson = "", canConfirm = false, outcome = "UNSUPPORTED", message = "当前 Mock 仅匹配菜鸟待取状态模板；暂不支持其他平台、寄件或站点筛选。")
        } else {
            val raw = app.assets.open("rules/cainiao-empty-v1.json").bufferedReader().use { it.readText() }
            candidate = ValidatedRule.parse(raw)
            mutable.value = state.value.copy(candidateJson = candidate!!.encode(), candidateSource = CAINIAO_PACKAGE, canConfirm = false, outcome = "CANDIDATE_READY", message = "模板匹配（Mock）完成。下一步会真实打开菜鸟，核验结果后才能创建卡片。")
        }
        viewModelScope.launch { export() }
    }

    fun compileOrder(oid: String, orderNumber: String) {
        if (!state.value.ready || state.value.busy) return
        candidate = null
        passed = null
        candidateOrder = try {
            MeituanOrderTarget(oid.trim(), orderNumber.filterNot(Char::isWhitespace))
        } catch (e: IllegalArgumentException) {
            mutable.value = state.value.copy(candidateJson = "", canConfirm = false, outcome = "INVALID_RULE",
                message = e.message.orEmpty())
            null
        }
        if (candidateOrder == null) return
        val raw = app.assets.open("rules/meituan-order-v1.json").bufferedReader().use { it.readText() }
        val rule = ValidatedRule.parse(raw)
        candidate = rule
        mutable.value = state.value.copy(candidateJson = rule.encode(), candidateSource = MEITUAN_PACKAGE,
            canConfirm = false, outcome = "CANDIDATE_READY", message = "试运行会重新打开订单并核验身份。")
    }

    fun compileJdShare(product: JdProduct) {
        if (!state.value.ready || state.value.busy) return
        passed = null
        candidateOrder = null
        val raw = app.assets.open("rules/${product.ruleId}-v1.json").bufferedReader().use { it.readText() }
        val rule = ValidatedRule.parse(raw)
        candidate = rule
        mutable.value = state.value.copy(candidateJson = rule.encode(), candidateSource = JD_PACKAGE,
            canConfirm = false, outcome = "CANDIDATE_READY", message = "可直接核验公开商品页，也可粘贴分享链接对照。")
    }

    fun compileCainiaoWithModel() {
        if (!state.value.ready || state.value.busy) return
        passed = null
        candidate = null
        candidateOrder = null
        val model = app.providers.modelFor(ModelRole.Main)
        if (model == null) {
            mutable.value = state.value.copy(candidateJson = "", canConfirm = false, outcome = "MODEL_UNAVAILABLE",
                message = "请先在 Mika Dev 中配置主模型，或使用本地模板。")
            return
        }
        mutable.value = state.value.copy(candidateJson = "", canConfirm = false, busy = true, outcome = "MODEL_RUNNING",
            message = "正在请求模型生成菜鸟候选规则。")
        querying = true
        job = viewModelScope.launch {
            val calls = app.usage.snapshot().rows.sumOf { it.counts.calls }
            val raw = app.assets.open("rules/cainiao-empty-v1.json").bufferedReader().use { it.readText() }
            val expected = ValidatedRule.parse(raw, cainiaoOnly = true).template.copy(ruleId = CAINIAO_MODEL_RULE)
            val outcome = try {
                val reply = withTimeoutOrNull(20_000) {
                    model.complete(
                        "为只读菜鸟查询生成一个 JSON 对象。只输出 JSON，不要工具调用、Markdown 或说明。",
                        listOf(Message(Role.User, listOf(Part.Text("""
                            生成 schemaVersion 1、ruleId $CAINIAO_MODEL_RULE、version 1 的菜鸟明确空状态规则。
                            target 包名为 $CAINIAO_PACKAGE，testedAppVersion 为 8.11.1105。
                            steps 的 action 依次为 LAUNCH、WAIT_PAGE、CLICK_NODE、VERIFY_EMPTY。
                            LAUNCH 的 activity 为 $CAINIAO_HOME。
                            WAIT_PAGE 的 requiredTexts 是依次排列的「首页」「取包裹」，timeoutMs 为 5000。
                            CLICK_NODE 的 selector 仅含 text「取包裹」，timeoutMs 为 5000。
                            VERIFY_EMPTY 的 title 为「取包裹」，emptyText 为「暂无待取包裹」，timeoutMs 为 8000。
                        """.trimIndent())), Author.User)),
                        emptyList(),
                    )
                }
                val json = reply?.parts?.filterIsInstance<Part.Text>()?.joinToString("") { it.text }
                if (json == null) "MODEL_TIMEOUT" else if (reply.parts.any { it is Part.ToolCall }) {
                    "MODEL_REJECTED"
                } else {
                    candidate = validateCainiaoModelCandidate(json, expected)
                    if (candidate == null) "MODEL_REJECTED" else "CANDIDATE_READY"
                }
            } catch (_: CancellationException) {
                "CANCELLED"
            } catch (_: Exception) {
                "MODEL_ERROR"
            }
            querying = false
            mutable.value = state.value.copy(busy = false, candidateSource = CAINIAO_PACKAGE,
                candidateJson = candidate?.encode().orEmpty(), outcome = outcome,
                modelCallsDelta = app.usage.snapshot().rows.sumOf { it.counts.calls } - calls,
                message = when (outcome) {
                    "CANDIDATE_READY" -> "模型候选已通过校验，请先真实试运行，再确认保存。"
                    "MODEL_REJECTED" -> "模型候选不符合已验证的菜鸟规则边界。"
                    "MODEL_TIMEOUT" -> "模型请求超时，可以重试或使用本地模板。"
                    "CANCELLED" -> "已取消生成候选。"
                    else -> "模型请求失败，请检查 Mika Dev 的主模型配置。"
                })
        }
    }

    fun editJdShare() {
        if (state.value.busy) return
        passed = null
        mutable.value = state.value.copy(canConfirm = false, message = "请核验当前分享链接。")
    }

    fun dryRun(cainiaoOnly: Boolean = false, jdShare: String? = null) {
        if (ignoreIfBusy()) return
        val rule = candidate ?: return
        if (cainiaoOnly && rule.template.target.packageName != CAINIAO_PACKAGE) return
        passed = null
        start(listOf(null), rule, jdShare)
    }

    fun confirm(cainiaoOnly: Boolean = false) {
        if (!state.value.ready || ignoreIfBusy()) return
        if (cainiaoOnly && (state.value.replacementCardId != null ||
                candidate?.template?.target?.packageName != CAINIAO_PACKAGE)) return
        val trial = passed
        if (trial == null || trial.rule !== candidate || !state.value.canConfirm) {
            mutable.value = state.value.copy(operationId = UUID.randomUUID().toString(), outcome = "CONFIRM_BLOCKED", message = "请先让当前需求通过真实试运行，再核对并确认。")
            viewModelScope.launch { export() }
            return
        }
        passed = null
        val replaced = state.value.cards.firstOrNull { it.id == state.value.replacementCardId }
        mutable.value = state.value.copy(operationId = UUID.randomUUID().toString(), busy = true, canConfirm = false)
        job = viewModelScope.launch {
            val rule = trial.rule
            val id = replaced?.id ?: UUID.randomUUID().toString()
            val run = trial.run.copy(cardId = id)
            val (title, source) = when (rule.template.target.packageName) {
                JD_PACKAGE -> checkNotNull(JdProduct.forRule(rule.template.ruleId)).cardTitle to "京东 · 公开页核验"
                MEITUAN_PACKAGE -> "美团历史订单" to "美团 · 外卖"
                else -> "菜鸟待取状态" to "菜鸟 · 取包裹"
            }
            val card = WorkspaceCard(id, title, rule.template.ruleId, rule.template.version, source,
                trial.result.displayText, run.finishedAt, run.finishedAt, CardStatus.FRESH_VALID, run.message,
                trial.order?.let { ruleJson.encodeToString(it) },
                trial.result.jdFields?.let { ruleJson.encodeToString(it) }
                    ?: trial.result.orderFields?.let { ruleJson.encodeToString(it) }, replaced?.ruleId, replaced?.ruleVersion)
            db.withTransaction {
                dao.insertRule(StoredRule(rule.template.ruleId, rule.template.version, rule.encode()))
                if (replaced == null) dao.insertCard(card) else dao.updateCard(card.copy(title = replaced.title))
                dao.insertRun(run)
            }
            mutable.value = state.value.copy(busy = false, cards = dao.cards(), createdId = id, replacementCardId = null,
                history = if (replaced != null) dao.runs(id) else state.value.history,
                outcome = if (replaced == null) "CREATED" else "RULE_REPLACED",
                message = if (replaced == null) "卡片已创建；结果来自刚才的真实试运行。" else "规则已更新，之前的规则可重新试运行后回退。")
            export()
        }
    }

    fun prepareCainiaoReplacement(card: WorkspaceCard, rollback: Boolean) {
        if (!state.value.ready || state.value.busy || card.ruleId !in setOf("cainiao-pickup-empty", CAINIAO_MODEL_RULE)) return
        val id = if (rollback) card.previousRuleId ?: return
            else if (card.ruleId == CAINIAO_MODEL_RULE) "cainiao-pickup-empty" else CAINIAO_MODEL_RULE
        val version = if (rollback) card.previousRuleVersion ?: return else 1
        viewModelScope.launch {
            val stored = dao.rule(id, version)
            val rule = if (stored == null) null else runCatching {
                ValidatedRule.parse(stored.json, cainiaoOnly = true)
            }.getOrNull()
            if (state.value.busy || state.value.selected != card.id) return@launch
            passed = null
            candidate = rule
            candidateOrder = null
            mutable.value = state.value.copy(candidateJson = rule?.encode().orEmpty(), candidateSource = CAINIAO_PACKAGE,
                replacementCardId = card.id.takeIf { rule != null }, canConfirm = false,
                outcome = if (rule != null) "CANDIDATE_READY" else "INVALID_RULE",
                message = if (rule != null) "请试运行并核对候选规则，再确认更新。" else "本地没有可用的目标规则，请先完成相应规则的创建。")
        }
    }

    fun refresh(id: String? = null, cainiaoOnly: Boolean = false) {
        if (!state.value.ready || ignoreIfBusy()) return
        val cards = state.value.cards.filter {
            (id == null || it.id == id) && (!cainiaoOnly || it.ruleId == "cainiao-pickup-empty")
        }
        if (cards.isEmpty()) return
        start(cards, null)
    }

    fun refreshJdShare(id: String, input: String) {
        if (!state.value.ready || ignoreIfBusy()) return
        val card = state.value.cards.singleOrNull { it.id == id && JdProduct.forRule(it.ruleId) != null } ?: return
        start(listOf(card), null, input)
    }

    fun refreshJdAppPrice(id: String) {
        if (!state.value.ready || ignoreIfBusy()) return
        val card = state.value.cards.singleOrNull { it.id == id && JdProduct.forRule(it.ruleId) != null } ?: return
        start(listOf(card), null, jdAppPrice = true)
    }

    private fun ignoreIfBusy(): Boolean {
        if (!state.value.busy) return false
        mutable.value = state.value.copy(ignoredStarts = state.value.ignoredStarts + 1)
        return true
    }

    private fun start(cards: List<WorkspaceCard?>, draft: ValidatedRule?, jdShare: String? = null, jdAppPrice: Boolean = false) {
        passed = null
        querying = true
        mutable.value = state.value.copy(operationId = UUID.randomUUID().toString(), busy = true, canConfirm = false, step = 0, outcome = "RUNNING", message = "正在前台查询；可返回本页取消。", ignoredStarts = 0)
        job = viewModelScope.launch {
            export()
            val workspaceTask = workspaceTaskId?.let { id ->
                app.getSystemService(ActivityManager::class.java).appTasks.firstOrNull { it.taskInfo?.taskId == id }
            }
            val phone = AndroidRulePhone(app, workspaceTask)
            var previousSource: String? = null
            for (card in cards) {
                mutable.value = state.value.copy(step = 0)
                val clock = SystemClock.elapsedRealtime()
                val at = System.currentTimeMillis()
                val calls = app.usage.snapshot().rows.sumOf { it.counts.calls }
                var rule: ValidatedRule? = draft
                var order = if (card == null) candidateOrder else null
                val result = try {
                    if (card != null) {
                        dao.updateCard(card.copy(status = CardStatus.REFRESHING, lastAttemptAt = at, reason = "正在刷新；下方保留上次成功结果。"))
                        mutable.value = state.value.copy(cards = dao.cards())
                        val stored = dao.rule(card.ruleId, card.ruleVersion)
                        rule = try { stored?.let { ValidatedRule.parse(it.json) } } catch (_: IllegalArgumentException) { null }
                        order = try { card.localTargetJson?.let { ruleJson.decodeFromString<MeituanOrderTarget>(it) } }
                            catch (_: IllegalArgumentException) { null }
                    }
                    when {
                        rule == null -> RuleResult("INVALID_RULE", "本地规则缺失或无效，请重新试运行模板。")
                        rule.template.target.packageName == JD_PACKAGE -> {
                            val product = checkNotNull(JdProduct.forRule(rule.template.ruleId))
                            val captured = if (jdAppPrice && ScreenAccessibility.instance.value != null) {
                                mutable.value = state.value.copy(message = "请切换到京东，打开${product.cardTitle}的匹配规格详情。")
                                previousSource = JD_PACKAGE
                                phone.captureJdAppPrice(product)
                            } else null
                            if (jdAppPrice && captured == null) {
                                RuleResult("NEED_HANDOFF", "请在京东停到匹配商品和规格的详情页，关闭浮窗后重新核验展示价。")
                            } else {
                                val verified = jdVerifier.verify(captured?.second ?: jdShare, product)
                                val identity = verified.jdFields
                                if (identity == null) verified else {
                                    val prior = card?.snapshotJson?.let { ruleJson.decodeFromString<JdShareEvidence>(it) }
                                    val price = captured?.first ?: prior?.takeIf {
                                        it.sku == identity.sku && it.variant == identity.variant
                                    }?.appPrice
                                    verified.copy(jdFields = identity.copy(appPrice = price), message = when {
                                        captured != null -> "SKU、规格和 App 页面展示价已核验。"
                                        price != null -> "公开页身份已核验，保留上次 App 展示价。"
                                        else -> verified.message
                                    })
                                }
                            }
                        }
                        ScreenAccessibility.instance.value == null -> RuleResult("UNAVAILABLE", "Mika Dev 无障碍服务未连接。")
                        else -> {
                            var foreground = withTimeoutOrNull(1_500) {
                                var name = phone.read().packageName
                                while (name == null) { delay(100); name = phone.read().packageName }
                                name
                            }
                            val target = rule.template.target.packageName
                            if (foreground != null && foreground == previousSource && foreground != target) {
                                // vivo 上从上一来源直接切换曾丢失启动请求，先返回 Mika Dev。
                                workspaceTask?.moveToFront()
                                val source = foreground
                                foreground = withTimeoutOrNull(1_500) {
                                    var name = phone.read().packageName
                                    while (name == null || name == source) { delay(100); name = phone.read().packageName }
                                    name
                                } ?: source
                            }
                            mutable.value = state.value.copy(lastPackage = foreground)
                            if (foreground == null) {
                                RuleResult("UNAVAILABLE", "前台页面暂不可读取，请等待页面显示完成后重试。")
                            } else if (foreground != app.packageName && foreground != target) {
                                RuleResult("NEED_HANDOFF", "当前前台是其他应用。请返回本空间后重试。")
                            } else {
                                previousSource = target
                                LocalRuleRunner(phone).execute(rule,
                                    progress = { i, stage -> mutable.value = state.value.copy(step = i, message = stage) },
                                    observed = { page -> mutable.value = state.value.copy(lastPackage = page.packageName) },
                                    clicked = {}, order = order)
                            }
                        }
                    }
                } catch (_: TimeoutCancellationException) {
                    val reason = when {
                        rule?.template?.target?.packageName == MEITUAN_PACKAGE ->
                            "美团未在限时内提供匹配的订单号码和状态，请确认 O1 详情可以打开后继续核验。"
                        state.value.step == 2 ->
                            "菜鸟首页等待超时，请确认首页和取包裹入口已显示后继续核验。"
                        else -> "页面核验超时，无法确认目标身份或状态。"
                    }
                    RuleResult("TIMEOUT", reason)
                } catch (_: CancellationException) {
                    RuleResult("CANCELLED", "已取消刷新；保留上次成功结果。")
                } catch (_: Exception) {
                    RuleResult("ERROR", "无法打开或读取目标页面。")
                }
                withContext(NonCancellable) {
                    val finished = System.currentTimeMillis()
                    val delta = app.usage.snapshot().rows.sumOf { it.counts.calls } - calls
                    val run = WorkspaceRun(UUID.randomUUID().toString(), card?.id ?: "", at, finished, result.outcome, result.message, 0, delta)
                    if (card != null) db.withTransaction {
                        val updated = card.afterAttempt(result.outcome, result.message, result.displayText, finished,
                            result.jdFields?.let { ruleJson.encodeToString(it) }
                                ?: result.orderFields?.let { ruleJson.encodeToString(it) })
                        dao.updateCard(if (result.jdFields != null) updated.copy(source = "京东 · 公开页核验") else updated)
                        dao.insertRun(run)
                    }
                    val displayMessage = if (card == null && (result.orderFields != null || result.jdFields != null))
                        "${result.message} ${result.displayText}" else result.message
                    mutable.value = state.value.copy(cards = dao.cards(), outcome = result.outcome, message = displayMessage,
                        modelCallsDelta = delta, canConfirm = card == null && result.successful && candidate === rule)
                    val elapsed = SystemClock.elapsedRealtime() - clock
                    if (card != null) dao.duration(run.id, elapsed)
                    if (state.value.canConfirm) passed = WorkspaceTrial(rule!!, order, run.copy(elapsedMs = elapsed), result)
                    mutable.value = state.value.copy(elapsedMs = elapsed)
                    export()
                }
                if (!isActive) break
            }
            withContext(NonCancellable) {
                querying = false
                mutable.value = state.value.copy(busy = false)
                if (state.value.selected != null) mutable.value = state.value.copy(history = dao.runs(state.value.selected!!))
                export()
            }
            // Return only from the source app; never cover an unrelated app the user switched to.
            if (isActive && previousSource != null && ScreenAccessibility.instance.value != null &&
                phone.read().packageName == previousSource) {
                workspaceTask?.moveToFront()
            }
        }
    }

    fun cancel() { if (querying) job?.cancel() }

    fun select(id: String?) {
        if (state.value.replacementCardId != null && id != state.value.replacementCardId && !state.value.busy) {
            editRequest(state.value.request)
        }
        viewModelScope.launch { mutable.value = state.value.copy(selected = id, history = if (id == null) emptyList() else dao.runs(id)) }
    }

    fun saveShoppingTask(note: String) {
        if (state.value.busy) return
        val pair = JdProduct.entries.map { product ->
            state.value.cards.filter { it.ruleId == product.ruleId && it.status == CardStatus.FRESH_VALID }
                .maxByOrNull { it.lastSuccessAt }?.snapshotJson
        }
        val a = pair[0] ?: return
        val b = pair[1] ?: return
        viewModelScope.launch {
            val task = ShoppingTask(productA = a, productB = b, note = note.trim(), savedAt = System.currentTimeMillis(),
                lastCheckedAt = null, lastCheck = "")
            dao.saveShoppingTask(task)
            mutable.value = state.value.copy(shoppingTask = task, message = "已保存 A/B 选购任务。")
        }
    }

    fun recheckShoppingTask() {
        if (state.value.busy) return
        val task = state.value.shoppingTask ?: return
        mutable.value = state.value.copy(busy = true, message = "正在重新核验选购任务。")
        querying = true
        job = viewModelScope.launch {
            val calls = app.usage.snapshot().rows.sumOf { it.counts.calls }
            try {
                val results = JdProduct.entries.map { jdVerifier.verify(null, it).jdFields }
                val status = if (results.any { it == null }) "有商品无法核验，保留已保存的任务。"
                    else {
                        val saved = listOf(task.productA, task.productB).map {
                            ruleJson.decodeFromString<JdShareEvidence>(it).copy(appPrice = null)
                        }
                        if (saved == results) "SKU、规格和名称未变化。价格无法比较。"
                        else "商品信息有变化，请重新查看 A/B。价格无法比较。"
                    }
                val checked = task.copy(lastCheckedAt = System.currentTimeMillis(), lastCheck = status)
                dao.saveShoppingTask(checked)
                mutable.value = state.value.copy(shoppingTask = checked, message = status)
            } finally {
                querying = false
                mutable.value = state.value.copy(busy = false,
                    modelCallsDelta = app.usage.snapshot().rows.sumOf { it.counts.calls } - calls)
            }
        }
    }

    fun delete(id: String) {
        if (!state.value.ready || state.value.busy) return
        mutable.value = state.value.copy(operationId = UUID.randomUUID().toString(), busy = true)
        job = viewModelScope.launch {
            db.withTransaction { dao.deleteRuns(id); dao.deleteCard(id) }
            mutable.value = state.value.copy(busy = false, cards = dao.cards(), selected = null, history = emptyList(), outcome = "DELETED", message = "已删除此卡片及其记录，其他卡片不受影响。")
            export()
        }
    }

    private suspend fun export() = withContext(Dispatchers.IO) {
        val s = state.value
        val json = buildJsonObject {
            put("ready", s.ready); put("busy", s.busy); put("outcome", s.outcome); put("message", if (s.outcome == "ORDER_CONFIRMED") "订单身份和状态已核验。" else s.message)
            put("operationId", s.operationId)
            put("lastPackage", s.lastPackage)
            put("canConfirm", s.canConfirm); put("elapsedMs", s.elapsedMs); put("modelCallsDelta", s.modelCallsDelta)
            put("ignoredStarts", s.ignoredStarts); put("createdId", s.createdId); put("step", s.step)
            put("cards", ruleJson.encodeToJsonElement(dao.cards().map { card ->
                if (card.localTargetJson == null) card else card.copy(localTargetJson = null, snapshotJson = null,
                    lastValidText = "已保存在本机卡片中")
            }))
            put("rules", ruleJson.encodeToJsonElement(dao.rules()))
            put("runs", ruleJson.encodeToJsonElement(dao.allRuns()))
        }.toString()
        evidenceDir.mkdirs()
        File(evidenceDir, "latest.json").writeText(json)
    }
}
