package com.wangzan101.mika.experiment

import android.app.Application
import android.content.Intent
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.settings.ProviderStore
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal data class CainiaoExperimentState(
    val draft: String = "",
    val summary: String = "尚未加载规则",
    val candidateReady: Boolean = false,
    val canConfirm: Boolean = false,
    val savedRule: String = "",
    val running: Boolean = false,
    val stage: String = "准备就绪",
    val stepIndex: Int = 0,
    val mode: String = "CONTROL",
    val outcome: String = "NOT_RUN",
    val message: String = "加载模板后进行真实试运行。",
    val startedAt: Long = 0,
    val finishedAt: Long = 0,
    val elapsedMs: Long = 0,
    val modelConfigured: Boolean = false,
    val ledgerCallsDelta: Long? = null,
    val nodesRead: Int = 0,
    val navigationClicks: Int = 0,
    val ignoredStarts: Int = 0,
    val lastPackage: String? = null,
    val ruleId: String = "",
    val ruleVersion: Int = 0,
    val verifiedTitle: String = "",
    val verifiedEmpty: String = "",
)

internal class CainiaoExperimentViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MikaApp
    private val mutableState = MutableStateFlow(CainiaoExperimentState())
    val state = mutableState.asStateFlow()
    private var candidate: ValidatedRule? = null
    private var passedCandidate: ValidatedRule? = null
    private var job: Job? = null
    private val savedFile = File(app.filesDir, "rule-experiment/verified-rule.json")
    private val recordDir = File(app.getExternalFilesDir(null), "rule-experiment")

    init { loadTemplate() }

    fun loadTemplate() {
        if (state.value.running) return
        editDraft(app.assets.open("rules/cainiao-empty-v1.json").bufferedReader().use { it.readText() })
    }

    fun editDraft(raw: String) {
        if (state.value.running) return
        passedCandidate = null
        candidate = null
        mutableState.value = state.value.copy(draft = raw, canConfirm = false, candidateReady = false, ruleId = "", ruleVersion = 0)
        try {
            val loaded = ValidatedRule.parse(raw)
            candidate = loaded
            val rule = loaded.template
            val summary = "${rule.ruleId} v${rule.version}\n${rule.target.packageName} · 已测试 ${rule.target.testedAppVersion}\n" +
                rule.steps.mapIndexed { i, step -> "${i + 1}. " + when (step) {
                    is RuleStep.Launch -> "启动 ${step.activity.substringAfterLast('.')}"
                    is RuleStep.WaitPage -> "等待 ${step.requiredTexts.joinToString("、")}"
                    is RuleStep.ClickNode -> "点击唯一节点 ${step.selector.text}"
                    is RuleStep.VerifyEmpty -> "核验 ${step.title} / ${step.emptyText}"
                } }.joinToString("\n")
            mutableState.value = state.value.copy(summary = summary, candidateReady = true, ruleId = rule.ruleId, ruleVersion = rule.version,
                savedRule = if (savedFile.exists()) "本地已有已验证规则（执行时重新加载）" else "")
            control("CANDIDATE_READY", "结构校验通过。规则正确性仍需真实试运行并由你核对。")
        } catch (e: IllegalArgumentException) {
            mutableState.value = state.value.copy(summary = "候选规则无效")
            control("INVALID_RULE", "规则校验失败：${e.message}")
        }
    }

    /** Fixed debug import path; imported bytes go through the same boundary as the editor. */
    fun loadIncoming() {
        if (state.value.running) return
        val incoming = File(recordDir, "incoming.json")
        if (!incoming.exists()) { control("IMPORT_MISSING", "未找到调试导入文件 incoming.json。"); return }
        editDraft(incoming.readText())
    }

    fun dryRun() {
        val rule = candidate ?: run { control("INVALID_RULE", "请先修正并校验候选规则。"); return }
        execute(rule, "DRY_RUN")
    }

    fun confirm() {
        if (state.value.running) return
        val rule = candidate
        if (rule == null || passedCandidate !== rule || !state.value.canConfirm ||
            state.value.mode != "DRY_RUN" || state.value.outcome != "EMPTY_CONFIRMED") {
            control("CONFIRM_BLOCKED", "当前候选规则尚未通过试运行，不能保存为已验证规则。")
            return
        }
        mutableState.value = state.value.copy(running = true, canConfirm = false, mode = "SAVE", stage = "保存已验证规则")
        job = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    savedFile.parentFile!!.mkdirs()
                    savedFile.writeText(rule.encode())
                }
                mutableState.value = state.value.copy(running = false, savedRule = "${rule.template.ruleId} v${rule.template.version}")
                control("SAVED", "已保存通过试运行且经你确认的规则。")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.value = state.value.copy(running = false)
                control("SAVE_FAILED", "保存失败：${e.message}")
            }
        }
    }

    fun runSaved() {
        if (state.value.running) return
        if (!savedFile.exists()) { control("NO_SAVED_RULE", "尚无已验证规则。请先试运行并确认保存。"); return }
        val saved = try {
            ValidatedRule.parse(savedFile.readText())
        } catch (e: Exception) {
            control("SAVED_RULE_INVALID", "已保存规则无法加载：${e.message}")
            return
        }
        mutableState.value = state.value.copy(savedRule = "${saved.template.ruleId} v${saved.template.version}")
        execute(saved, "SAVED")
    }

    fun cancel() {
        if (state.value.mode == "DRY_RUN" || state.value.mode == "SAVED") job?.cancel()
    }

    private fun execute(rule: ValidatedRule, mode: String) {
        if (job?.isActive == true) {
            mutableState.value = state.value.copy(ignoredStarts = state.value.ignoredStarts + 1)
            return
        }
        passedCandidate = null
        val started = SystemClock.elapsedRealtime()
        val callsBefore = app.usage.snapshot().rows.sumOf { it.counts.calls }
        mutableState.value = state.value.copy(running = true, canConfirm = false, mode = mode,
            stage = "检查手机能力", stepIndex = 0, outcome = "RUNNING", message = "正在真实操作菜鸟；返回本页可取消。",
            startedAt = System.currentTimeMillis(), finishedAt = 0, elapsedMs = 0,
            modelConfigured = ProviderStore.get(app).roles.value.main != null, ledgerCallsDelta = null,
            nodesRead = 0, navigationClicks = 0, ignoredStarts = 0, lastPackage = null,
            ruleId = rule.template.ruleId, ruleVersion = rule.template.version, verifiedTitle = "", verifiedEmpty = "")
        job = viewModelScope.launch {
            val result = try {
                if (ScreenAccessibility.instance.value == null) {
                    RuleResult("UNAVAILABLE", "Mika Dev 无障碍服务未连接，请启用后重试。")
                } else LocalRuleRunner(AndroidRulePhone(app)).execute(rule,
                    progress = { index, text -> mutableState.value = state.value.copy(stepIndex = index, stage = text) },
                    observed = { page -> mutableState.value = state.value.copy(nodesRead = page.nodeCount, lastPackage = page.packageName) },
                    clicked = { mutableState.value = state.value.copy(navigationClicks = state.value.navigationClicks + 1) },
                )
            } catch (e: TimeoutCancellationException) {
                RuleResult("TIMEOUT", "步骤 ${state.value.stepIndex}：${state.value.stage}超时，无法确认结果。")
            } catch (e: CancellationException) {
                RuleResult("CANCELLED", "已取消，候选规则未获得本次试运行通过资格。")
            } catch (e: Exception) {
                RuleResult("ERROR", "步骤 ${state.value.stepIndex}：${state.value.stage}失败：${e.message}")
            }
            val passed = mode == "DRY_RUN" && result.outcome == "EMPTY_CONFIRMED" && candidate === rule
            if (passed) passedCandidate = rule
            val finished = state.value.copy(running = false, stage = "执行结束", outcome = result.outcome,
                message = result.message, canConfirm = passed, finishedAt = System.currentTimeMillis(),
                elapsedMs = SystemClock.elapsedRealtime() - started,
                ledgerCallsDelta = app.usage.snapshot().rows.sumOf { it.counts.calls } - callsBefore,
                verifiedTitle = result.verifiedTitle, verifiedEmpty = result.verifiedEmpty)
            mutableState.value = finished
            withContext(NonCancellable + Dispatchers.IO) { persist(finished) }
            if (viewModelScope.coroutineContext[Job]?.isActive == true && ScreenAccessibility.instance.value != null &&
                AndroidRulePhone(app).read().packageName == CAINIAO_PACKAGE) {
                ScreenAccessibility.instance.value?.startActivity(
                    Intent(app, CainiaoExperimentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    private fun control(outcome: String, message: String) {
        val now = System.currentTimeMillis()
        val updated = state.value.copy(running = false, canConfirm = false, mode = "CONTROL", stage = "规则操作", stepIndex = 0, outcome = outcome,
            message = message, startedAt = now, finishedAt = now, elapsedMs = 0, ledgerCallsDelta = null,
            nodesRead = 0, navigationClicks = 0, ignoredStarts = 0, lastPackage = null,
            verifiedTitle = "", verifiedEmpty = "")
        mutableState.value = updated
        viewModelScope.launch(Dispatchers.IO) { persist(updated) }
    }

    private fun persist(s: CainiaoExperimentState) {
        val json = buildJsonObject {
            put("outcome", s.outcome); put("mode", s.mode); put("message", s.message)
            put("startedAt", s.startedAt); put("finishedAt", s.finishedAt); put("elapsedMs", s.elapsedMs)
            put("stepIndex", s.stepIndex); put("modelConfigured", s.modelConfigured); put("ledgerCallsDelta", s.ledgerCallsDelta)
            put("nodesRead", s.nodesRead); put("navigationClicks", s.navigationClicks); put("ignoredStarts", s.ignoredStarts)
            put("lastPackage", s.lastPackage); put("ruleId", s.ruleId); put("ruleVersion", s.ruleVersion)
            put("canConfirm", s.canConfirm); put("savedExists", savedFile.exists())
            put("verifiedTitle", s.verifiedTitle); put("verifiedEmptyAnchor", s.verifiedEmpty)
        }.toString()
        recordDir.mkdirs()
        File(recordDir, "latest.json").writeText(json)
        File(recordDir, "operations.jsonl").appendText(json + "\n")
    }
}
