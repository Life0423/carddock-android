package com.wangzan101.mika.core.assembly

import com.wangzan101.mika.core.capability.Capability
import com.wangzan101.mika.core.tool.AgentTool
import com.wangzan101.mika.core.tool.ToolIndex
import com.wangzan101.mika.core.agent.CoreSections
import com.wangzan101.mika.core.general.GeneralGroup
import com.wangzan101.mika.core.run.RunSpec
import com.wangzan101.mika.core.run.Attendance
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.ToolConfig
import com.wangzan101.mika.core.tool.ToolDispatcher
import com.wangzan101.mika.core.uistate.Interfaces

/** 每个 Run 在开局由 Provider 打开能力；Provider 捕获自己的端口与资源，不进入核心循环。 */
internal interface CapabilityProvider {
    val id: String
    fun open(spec: RunSpec): Capability?
}

internal class RunAssembler(private val providers: List<CapabilityProvider>) {
    fun assemble(spec: RunSpec, config: ToolConfig, stableRefs: Boolean,
        formerPrefix: Map<String, String>, renamed: Map<String, String>,
        retired: Map<String, String>, moved: Map<String, List<String>>): ToolDispatcher {
        check(providers.map { it.id }.distinct().size == providers.size) { "duplicate capability provider ids" }
        val child = spec.provenance.origin == RunOrigin.Child
        val readOnly = spec.readOnly || child
        val capabilities = providers.filter { (spec.capabilities == null || it.id in spec.capabilities) &&
            (!child || it.id in setOf("web", "sandbox")) }
            .mapNotNull { it.open(spec) }
        val general = GeneralGroup.tools.filterNot { (spec.readOnly || child) && it.verb == Verb.MemoryEdit }
            .filterNot { spec.provenance.attendance == Attendance.Unattended &&
            it.verb in setOf(Verb.AskUser, Verb.RequestConfirmation, Verb.HandToUser, Verb.ScheduleTask, Verb.ProfileEdit, Verb.SkillSave, Verb.MemoryEdit) }
            .filterNot { spec.provenance.origin == RunOrigin.Child && it.verb in setOf(Verb.SpawnAgent, Verb.MemorySave, Verb.MemoryEdit, Verb.ProfileEdit, Verb.ScheduleTask, Verb.SkillSave,
                Verb.AskUser, Verb.RequestConfirmation, Verb.HandToUser) }
            .filter { !readOnly || it.risk == Risk.Read }
        val capabilityTools = capabilities.flatMap { it.tools() }.filter { !readOnly || it.risk == Risk.Read ||
            it.capability == "web" && it.verb == Verb.Navigate }
        // 路线验证会重复执行，只开放可记录的导航和读屏；不暴露脚本、系统写入或委派入口。
        val offered = if (spec.browseRoute) general.filter { it.allowedForBrowse() } + capabilityTools.filter { it.allowedForBrowse() }
        else general + capabilityTools
        return ToolDispatcher(capabilities, offered,
            listOf(CoreSections) + capabilities, config, { ctx -> Interfaces.attach(ctx, capabilities.mapNotNull { it.surface }, stableRefs) },
            formerPrefix, renamed, retired, moved)
    }
}

/** 自由任务转换为浏览模板时，复用与实时工具发现相同的可浏览操作集合。 */
internal fun AgentTool.allowedForBrowse(): Boolean = if (capability == null)
    verb in setOf(Verb.AskUser, Verb.Wait, Verb.EndNotDone, Verb.KnowledgeRead, Verb.KnowledgeSearch)
else risk == Risk.Read || routable && verb in setOf(Verb.LaunchApp, Verb.Tap, Verb.TypeText, Verb.ClearText, Verb.Scroll, Verb.Swipe, Verb.Back)

/** 手机浏览模板只接受本机手机工具与通用只读辅助。批中每个工具另外出现在动作和工具回执中。 */
fun ToolIndex.allowsPhoneBrowse(name: String): Boolean {
    val tool = this[name] as? AgentTool ?: return false
    return (tool.capability == null || tool.capability == "phone") && tool.allowedForBrowse()
}
