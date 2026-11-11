package com.wangzan101.mika.workspace

import android.app.Application
import android.content.Intent
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.room.withTransaction
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
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
)

internal class WorkspaceViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MikaApp
    private companion object { var database: WorkspaceDatabase? = null }
    private val db = database ?: Room.databaseBuilder(app, WorkspaceDatabase::class.java, "workspace-v1.db").build().also { database = it }
    private val dao = db.dao()
    private val mutable = MutableStateFlow(WorkspaceState())
    val state = mutable.asStateFlow()
    private var candidate: ValidatedRule? = null
    private var passed: Triple<ValidatedRule, WorkspaceRun, String>? = null
    private var job: Job? = null
    private var querying = false
    private val evidenceDir = File(app.getExternalFilesDir(null), "workspace")

    init {
        viewModelScope.launch {
            dao.recoverInterrupted()
            mutable.value = state.value.copy(ready = true, cards = dao.cards())
            export()
        }
    }

    fun editRequest(text: String) {
        if (state.value.busy) return
        candidate = null; passed = null
        mutable.value = state.value.copy(request = text, candidateJson = "", canConfirm = false, outcome = "DRAFT", message = "需求已变更，请重新匹配并试运行。")
    }

    fun compile() {
        if (!state.value.ready || state.value.busy) return
        passed = null; candidate = null
        mutable.value = state.value.copy(operationId = UUID.randomUUID().toString())
        if (!MockCompiler.supports(state.value.request)) {
            mutable.value = state.value.copy(candidateJson = "", canConfirm = false, outcome = "UNSUPPORTED", message = "当前 Mock 仅匹配菜鸟待取状态模板；暂不支持其他平台、寄件或站点筛选。")
        } else {
            val raw = app.assets.open("rules/cainiao-empty-v1.json").bufferedReader().use { it.readText() }
            candidate = ValidatedRule.parse(raw)
            mutable.value = state.value.copy(candidateJson = candidate!!.encode(), canConfirm = false, outcome = "CANDIDATE_READY", message = "模板匹配（Mock）完成。下一步会真实打开菜鸟，核验结果后才能创建卡片。")
        }
        viewModelScope.launch { export() }
    }

    fun dryRun() {
        if (ignoreIfBusy()) return
        val rule = candidate ?: return
        passed = null
        start(listOf(null), rule)
    }

    fun confirm() {
        if (!state.value.ready || ignoreIfBusy()) return
        val trial = passed
        if (trial == null || trial.first !== candidate || !state.value.canConfirm) {
            mutable.value = state.value.copy(operationId = UUID.randomUUID().toString(), outcome = "CONFIRM_BLOCKED", message = "请先让当前需求通过真实试运行，再核对并确认。")
            viewModelScope.launch { export() }
            return
        }
        passed = null
        mutable.value = state.value.copy(operationId = UUID.randomUUID().toString(), busy = true, canConfirm = false)
        job = viewModelScope.launch {
            val rule = trial.first
            val id = UUID.randomUUID().toString()
            val run = trial.second.copy(cardId = id)
            val text = trial.third
            val card = WorkspaceCard(id, "菜鸟待取状态", rule.template.ruleId, rule.template.version,
                "菜鸟 · 取包裹", text, run.finishedAt, run.finishedAt, CardStatus.FRESH_VALID, run.message)
            db.withTransaction {
                dao.insertRule(StoredRule(rule.template.ruleId, rule.template.version, rule.encode()))
                dao.insertCard(card)
                dao.insertRun(run)
            }
            mutable.value = state.value.copy(busy = false, cards = dao.cards(), createdId = id, outcome = "CREATED", message = "卡片已创建；结果来自刚才的真实试运行。")
            export()
        }
    }

    fun refresh(id: String? = null) {
        if (!state.value.ready || ignoreIfBusy()) return
        val cards = if (id == null) state.value.cards else state.value.cards.filter { it.id == id }
        if (cards.isEmpty()) return
        start(cards, null)
    }

    private fun ignoreIfBusy(): Boolean {
        if (!state.value.busy) return false
        mutable.value = state.value.copy(ignoredStarts = state.value.ignoredStarts + 1)
        return true
    }

    private fun start(cards: List<WorkspaceCard?>, draft: ValidatedRule?) {
        passed = null
        querying = true
        mutable.value = state.value.copy(operationId = UUID.randomUUID().toString(), busy = true, canConfirm = false, step = 0, outcome = "RUNNING", message = "正在前台查询菜鸟；可返回本页取消。", ignoredStarts = 0)
        job = viewModelScope.launch {
            export()
            for (card in cards) {
                val clock = SystemClock.elapsedRealtime()
                val at = System.currentTimeMillis()
                val calls = app.usage.snapshot().rows.sumOf { it.counts.calls }
                var rule: ValidatedRule? = draft
                val result = try {
                    if (card != null) {
                        dao.updateCard(card.copy(status = CardStatus.REFRESHING, lastAttemptAt = at, reason = "正在刷新；下方保留上次成功结果。"))
                        mutable.value = state.value.copy(cards = dao.cards())
                        val stored = dao.rule(card.ruleId, card.ruleVersion)
                        rule = try { stored?.let { ValidatedRule.parse(it.json) } } catch (_: IllegalArgumentException) { null }
                    }
                    when {
                        rule == null -> RuleResult("INVALID_RULE", "本地规则缺失或无效，请重新试运行模板。")
                        ScreenAccessibility.instance.value == null -> RuleResult("UNAVAILABLE", "Mika Dev 无障碍服务未连接。")
                        else -> {
                            val phone = AndroidRulePhone(app)
                            val foreground = withTimeoutOrNull(1_500) {
                                var name = phone.read().packageName
                                while (name == null) { delay(100); name = phone.read().packageName }
                                name
                            }
                            mutable.value = state.value.copy(lastPackage = foreground)
                            if (foreground == null) {
                                RuleResult("UNAVAILABLE", "前台页面暂不可读取，请等待页面显示完成后重试。")
                            } else if (foreground != app.packageName && foreground != CAINIAO_PACKAGE) {
                                RuleResult("NEED_HANDOFF", "当前正在使用其他应用，未启动菜鸟。请返回本空间后重试。")
                            } else LocalRuleRunner(phone).execute(rule!!,
                                progress = { i, stage -> mutable.value = state.value.copy(step = i, message = stage) },
                                observed = { page -> mutable.value = state.value.copy(lastPackage = page.packageName) }, clicked = {})
                        }
                    }
                } catch (_: TimeoutCancellationException) {
                    RuleResult("TIMEOUT", "页面核验超时；无法确认当前状态。可能有包裹、页面变化或前台切换，请手动查看。")
                } catch (_: CancellationException) {
                    RuleResult("CANCELLED", "已取消刷新；保留上次成功结果。")
                } catch (e: Exception) {
                    RuleResult("ERROR", "手机查询失败：${e.message}")
                }
                withContext(NonCancellable) {
                    val finished = System.currentTimeMillis()
                    val delta = app.usage.snapshot().rows.sumOf { it.counts.calls } - calls
                    val run = WorkspaceRun(UUID.randomUUID().toString(), card?.id ?: "", at, finished, result.outcome, result.message, 0, delta)
                    if (card != null) db.withTransaction {
                        dao.updateCard(card.afterAttempt(result.outcome, result.message, result.verifiedEmpty, finished))
                        dao.insertRun(run)
                    }
                    mutable.value = state.value.copy(cards = dao.cards(), outcome = result.outcome, message = result.message,
                        modelCallsDelta = delta, canConfirm = card == null && result.outcome == "EMPTY_CONFIRMED" && candidate === rule)
                    val elapsed = SystemClock.elapsedRealtime() - clock
                    if (card != null) dao.duration(run.id, elapsed)
                    if (state.value.canConfirm) passed = Triple(rule!!, run.copy(elapsedMs = elapsed), result.verifiedEmpty)
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
            if (isActive && ScreenAccessibility.instance.value != null && AndroidRulePhone(app).read().packageName == CAINIAO_PACKAGE) {
                ScreenAccessibility.instance.value?.startActivity(Intent(app, WorkspaceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    fun cancel() { if (querying) job?.cancel() }

    fun select(id: String?) {
        viewModelScope.launch { mutable.value = state.value.copy(selected = id, history = if (id == null) emptyList() else dao.runs(id)) }
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
            put("ready", s.ready); put("busy", s.busy); put("outcome", s.outcome); put("message", s.message)
            put("operationId", s.operationId)
            put("lastPackage", s.lastPackage)
            put("canConfirm", s.canConfirm); put("elapsedMs", s.elapsedMs); put("modelCallsDelta", s.modelCallsDelta)
            put("ignoredStarts", s.ignoredStarts); put("createdId", s.createdId); put("step", s.step)
            put("cards", ruleJson.encodeToJsonElement(dao.cards()))
            put("rules", ruleJson.encodeToJsonElement(dao.rules()))
            put("runs", ruleJson.encodeToJsonElement(dao.allRuns()))
        }.toString()
        evidenceDir.mkdirs()
        File(evidenceDir, "latest.json").writeText(json)
    }
}
