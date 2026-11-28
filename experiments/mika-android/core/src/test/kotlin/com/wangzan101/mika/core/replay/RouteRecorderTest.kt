package com.wangzan101.mika.core.replay

import com.wangzan101.mika.core.record.RunEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wangzan101.mika.core.testing.TOOLS

class RouteRecorderTest {
    private val goal = "在备忘录里写今天天气不错"
    private val home = Anchors("com.notes", listOf("备忘录", "全部笔记"))
    private val editor = Anchors("com.notes", listOf("新笔记", "完成"))
    private val newBtn = Selector("button", "新建", "n:id/new", listOf(800, 900, 999, 999), 0)
    private val field = Selector("edit", "", "n:id/body", listOf(0, 120, 999, 900), 0)

    private fun target(step: Int, tool: String, args: String, sel: Selector? = null, before: Anchors = home, after: Anchors = editor, effective: Boolean = true,
                       callId: String = "c$step", protected: Boolean = false) =
        RunEvent.ActionTarget(step.toLong(), step, tool, args, sel, before, after, effective, callId, protected)

    private fun result(step: Int, tool: String, json: String, callId: String = "c$step") =
        RunEvent.ToolResult(step.toLong(), step, tool, json, true, null, callId = callId)

    /** 一次核查通过的成功运行;[middle] 插在动作之间。 */
    private fun run(vararg middle: RunEvent, typed: String = "今天天气不错", typedResult: String = """{"ok":true,"typed":6,"secret":false,"verification":"match"}""",
                    goal: String = this.goal) = listOf(
        RunEvent.TaskStarted(0, goal, "m"),
        RunEvent.Criteria(1, listOf("写了"), emptyList()),
        target(1, "phone_launch_app", """{"package":"com.notes"}""", before = Anchors(null, emptyList()), after = home),
        result(1, "phone_launch_app", """{"ok":true}"""),
        RunEvent.ToolResult(2, 2, "phone_observe", """{"ok":true,"currentApp":"com.notes"}""", null, 1),
        target(3, "phone_tap", """{"ref":"@e2","snapshotId":"s1"}""", newBtn),
        result(3, "phone_tap", """{"ok":true,"changed":true}"""),
        *middle,
        target(4, "phone_type_text", """{"ref":"@e9","snapshotId":"s2","text":"$typed"}""", field, before = editor, after = Anchors("com.notes", listOf("保存"))),
        result(4, "phone_type_text", typedResult),
        // 另一次调用的结果(同一步号)不能拿来配对
        result(4, "phone_type_text", """{"ok":true,"secret":false}""", callId = "other"),
        RunEvent.Verifier(5, "pass", "{}"),
        RunEvent.TaskEnded(6, "succeeded", "写好了"),
    )

    @Test fun browseRouteDropsOnlyRoundTripsToTheSameCompleteScreen() {
        val base = listOf(
            target(1, "phone_launch_app", """{"package":"com.notes"}""", before = Anchors(null, emptyList()), after = home),
            target(2, "phone_tap", "{}", newBtn), target(3, "phone_back", "{}", before = editor, after = home),
            target(4, "phone_tap", "{}", newBtn))
        fun page(label: String) = """{"ok":true,"screen":{"full":true,"currentApp":"com.notes","elements":[{"name":"备忘录"},{"name":"$label"}]}}"""
        for (restored in listOf("全部", "筛选后")) {
            val observed = listOf("全部", "设置", restored, "结果")
            val events = base.flatMapIndexed { i, act -> listOf(act, result(act.step, act.tool, page(observed[i]))) } +
                RunEvent.TaskEnded(5, "succeeded", "已读到结果")
            val route = requireNotNull(RouteRecorder.record(goal, events, 8, com.wangzan101.mika.core.assembly.knownTools(), trimBrowseDetours = true))
            assertEquals(if (restored == "全部") 2 else 4, route.steps.size)
        }
    }

    @Test fun recordsOnlyEffectiveWrites() {
        val events = run(
            target(3, "phone_tap", """{"x":10,"y":10}""", effective = false),
            result(3, "phone_tap", """{"ok":true,"changed":false}"""),
            target(3, "phone_scroll", """{"direction":"down"}""", effective = false),
        )
        val r = RouteRecorder.record(goal, events, nowMs = 99, tools = TOOLS)!!
        assertEquals(listOf("phone_launch_app", "phone_tap", "phone_type_text"), r.steps.map { it.tool })
        assertEquals(listOf("com.notes"), r.packages)
        assertEquals(99, r.lastUsedMs)
        // 编号是一次读屏内的,路线里只留选择器
        val tap = Json.parseToJsonElement(r.steps[1].argsJson).jsonObject
        assertFalse("ref" in tap); assertFalse("snapshotId" in tap)
        assertEquals(newBtn, r.steps[1].target)
    }

    @Test fun runsThatUsedTheBrowserAreNotRecorded() {
        // 网页任务:用过 web_navigate,之后在网页上点的是共用的 tap,也不记
        val events = run(
            RunEvent.ModelReply(3, 3, "", "web_navigate", "{}", null, null, null),
            result(3, "web_navigate", """{"ok":true,"surface":"web"}"""),
        )
        assertNull(RouteRecorder.record(goal, events, 0, TOOLS))
    }

    @Test fun onlySucceededRunsMakeRoutes() {
        // 办没办成由调用方按 LearnGate 判;没跑核查的运行照样记得下
        assertNotNull(RouteRecorder.record(goal, run().filter { it !is RunEvent.Verifier }, 1, TOOLS))
        val ended = run().map { if (it is RunEvent.TaskEnded) RunEvent.TaskEnded(6, "failed", "x") else it }
        assertNull(RouteRecorder.record(goal, ended, 1, TOOLS))
    }

    @Test fun typedTextInGoalBecomesSlot() {
        val r = RouteRecorder.record(goal, run(), 1, TOOLS)!!
        assertEquals("在备忘录里写{1}", r.template)
        assertEquals(1, r.slots)
        val typed = Json.parseToJsonElement(r.steps[2].argsJson).jsonObject
        assertEquals("{1}", typed["text"]!!.jsonPrimitive.content)
        assertFalse(r.steps.any { "今天天气不错" in it.argsJson })
    }

    @Test fun typedTextNotASlotMeansNoRoute() {
        // 不在原话里、太短、在原话里出现不止一次、被更长的槽吞掉:都不记
        assertNull(RouteRecorder.record(goal, run(typed = "日记"), 1, TOOLS))
        assertNull(RouteRecorder.record("写个a", run(typed = "a", goal = "写个a"), 1, TOOLS))
        val twice = "写今天天气不错,今天天气不错"
        assertNull(RouteRecorder.record(twice, run(goal = twice), 1, TOOLS))
        val swallowed = run(target(3, "phone_type_text", """{"text":"天气"}""", field, callId = "s"), result(3, "phone_type_text", """{"ok":true,"secret":false}""", callId = "s"))
        assertNull(RouteRecorder.record(goal, swallowed, 1, TOOLS))
    }

    @Test fun priceSelectionKeepsItsCatalogLabelWhenTheRowContainsALiveOffer() {
        val item = com.wangzan101.mika.core.verify.PriceKey("com.notes", "官方示例 ModelPro 蓝牙耳机", "黑色", "券后价")
        val box = listOf(0, 200, 999, 399)
        val resultRow = Selector("group", "", null, box, 8)
        val snap = """{"ok":true,"screen":{"snapshotId":"p3","currentApp":"com.notes","elements":[{"ref":"@e3","bounds":[0,200,999,399],"name":"自营,ModelPro耳机限时立减22元,倒计时"}]}}"""
        val events = listOf(target(1, "phone_launch_app", """{"package":"com.notes"}""", before=Anchors(null,emptyList()), after=home),
            result(2,"phone_observe",snap),
            target(3,"phone_tap","""{"ref":"@e3","snapshotId":"p3"}""",resultRow),
            RunEvent.TaskEnded(5,"succeeded","已核对商品"))
        val route = requireNotNull(RouteRecorder.recordBrowse("查询耳机价格", events, 5, TOOLS, item).route)
        assertEquals("自营,ModelPro耳机",route.steps.last().target?.productLabel)
        assertFalse(RouteCodec.encode(route).contains("22元"))
    }

    @Test fun priceRouteCanKeepVerifiedProductSearchSpelling() {
        val item = com.wangzan101.mika.core.verify.PriceKey("com.notes", "Apple AirPods Pro (第三代)", "白色", "标价")
        val goal = "每天核对苹果耳机的价格"
        fun record(text: String, pkg: String = item.pkg) =
            RouteRecorder.recordBrowse(goal, run(typed = text, goal = goal), 9, TOOLS, item.copy(pkg = pkg))
        val result = record("AirPods Pro 3")
        val route = requireNotNull(result.route) { result.reason }
        assertTrue(route.steps.last().argsJson.contains("AirPods Pro 3"))
        assertNull(record("AirPods other").route)
        assertNull(record("AirPods Pro 3", "other.app").route)
    }

    @Test fun targetNameInGoalBecomesSlot() {
        val memo = Selector("button", "备忘录", null, listOf(0, 0, 100, 100), 0, uniqueAtRecord = true)
        val events = run(target(3, "phone_tap", """{"ref":"@e1","snapshotId":"s1","target":"备忘录图标"}""", memo, callId = "m"),
            result(3, "phone_tap", """{"ok":true,"changed":true}""", callId = "m"))
        val r = RouteRecorder.record(goal, events, 1, TOOLS)!!
        assertEquals("在{1}里写{2}", r.template)
        assertEquals(2, r.slots)
        val tap = r.steps.first { it.target?.role == "button" && it.target.resourceId == null }
        assertEquals(memo.copy(name = "{1}"), tap.target)
        // 模型写的 target 描述不存
        assertFalse("target" in Json.parseToJsonElement(tap.argsJson).jsonObject)
        // 一个字的名字碰巧在原话里:没法可靠绑槽,不记
        val one = Selector("button", "写", null, listOf(0, 0, 1, 1), 0)
        assertNull(RouteRecorder.record(goal, run(target(3, "phone_tap", "{}", one, callId = "w"), result(3, "phone_tap", """{"ok":true,"changed":true}""", callId = "w")), 1, TOOLS))
    }

    @Test fun verifiedIdentityPageKeepsBothNavigationLabelsFixed() {
        val goal = "从快递入口进入身份码，再确认身份码页"
        val events = listOf(
            target(1, "phone_launch_app", """{"package":"com.notes"}""", before = Anchors(null, emptyList()), after = home),
            target(6, "phone_tap", "{}", newBtn.copy(name = "快递入口"), before = home, after = editor),
            target(7, "phone_tap", "{}", newBtn.copy(name = "身份码", uniqueAtRecord = false), before = editor, after = Anchors("com.notes", emptyList())),
            RunEvent.State(8, com.wangzan101.mika.core.uistate.IdentityCodeScreen.ARRIVAL_EVENT, "com.notes"),
            RunEvent.TaskEnded(9, "succeeded", "已打开身份码页")
        )
        val recording = RouteRecorder.recordBrowse(goal, events, 9, TOOLS)
        val route = requireNotNull(recording.route) { recording.reason }
        assertEquals(emptyList<String>(), recording.slots)
        assertEquals(listOf("快递入口", "身份码"), route.steps.mapNotNull { it.target?.name })
    }

    @Test fun repeatedVerifiedVisualNavigationLabelIsFixedWhileInputStaysBound() {
        val goal = "写今天天气不错，点筛选，在筛选面板读取结果"
        val filter = newBtn.copy(name = "筛选", visualDescription = "右上角的筛选图标")
        val events = run(target(5, "phone_tap", """{"reason":"取件码：9876","ref":"@v1","snapshotId":"loc"}""", filter))
        val result = RouteRecorder.recordBrowse(goal, events, 1, TOOLS)
        val route = requireNotNull(result.route) { result.reason }
        assertEquals(listOf("今天天气不错"), result.slots)
        assertEquals("筛选", route.steps.single { it.target?.visualDescription != null }.target?.name)
        assertFalse("9876" in RouteCodec.encode(route))
        val ambiguous = RouteRecorder.recordBrowse(goal, events.map {
            if (it is RunEvent.ActionTarget && it.target == filter) it.copy(target = filter.copy(visualDescription = null)) else it
        }, 1, TOOLS)
        assertNull(ambiguous.route)
        assertTrue("第 5 步" in ambiguous.reason)
        assertFalse("筛选" in ambiguous.reason)
    }

    @Test fun chooserIsOmittedOnlyWhenTheOriginalRequestedAppWasActuallyEntered() {
        val chooser = Anchors("com.vivo.appfilter", emptyList())
        val launch = target(1, "phone_launch_app", """{"package":"com.notes"}""", before = Anchors(null, emptyList()), after = chooser)
        val events = listOf(launch, target(2, "phone_tap", "{}", newBtn, before = chooser, after = home),
            target(3, "phone_tap", "{}", newBtn), RunEvent.TaskEnded(4, "succeeded", "ok"))
        val route = requireNotNull(RouteRecorder.recordBrowse("查笔记", events, 1, TOOLS).route)
        assertEquals(listOf("com.notes"), route.packages)
        assertEquals(listOf("phone_launch_app", "phone_tap"), route.steps.map { it.tool })
    }

    @Test fun screenshotBetweenElementLookupAndTreeTapRecordsAPixelCheckForTheSameControl() {
        val goal = "查看笔记，先看截图定位纸张图标"
        val description = "右下的纸张图标"
        val launch = target(1, "phone_launch_app", """{"package":"com.notes"}""", after = home)
        val find = RunEvent.ModelReply(2, 2, "", "phone_find_visually", """{"target":"$description","text":"新建"}""", null, null, null)
        val found = result(2, "phone_find_visually", """{"found":true,"source":"elements","box":[800,900,999,999],"ref":"@v1"}""")
        val shot = result(3, "phone_screenshot", """{"ok":true,"screenshotId":7}""")
        val tap = target(4, "phone_tap", """{"ref":"@e2","snapshotId":"s"}""", newBtn)
        val pixelQuery = find.copy(argsJson = """{"target":"$description","text":"新建","requirePixels":true}""")
        val pixelHit = found.copy(resultJson = """{"found":true,"source":"vision","ref":"@v2","snapshotId":"v","x":950,"y":940}""")
        val pixelTap = tap.copy(argsJson = """{"ref":"@v2","snapshotId":"v"}""")
        val end = RunEvent.TaskEnded(5, "succeeded", "ok")
        for ((events, expected) in listOf(listOf(launch, find, found, shot, tap, end) to true,
            listOf(launch, pixelQuery, pixelHit, pixelTap, end) to true,
            listOf(launch, find, found, tap, shot, end) to false,
            listOf(launch, find, found.copy(resultJson = found.resultJson.replace("800", "100")), shot, tap, end) to false)) {
            val route = requireNotNull(RouteRecorder.recordBrowse(goal, events, 1, com.wangzan101.mika.core.assembly.knownTools()).route)
            val saved = requireNotNull(RouteCodec.decode(RouteCodec.encode(route)).steps.last().target)
            assertEquals(if (expected) description else null, saved.visualDescription)
            assertEquals(expected, saved.requireVisualCheck)
        }
    }

    @Test fun missingTreeTargetCanBeRecordedOnlyFromTheMatchingVerifiedPixelReference() {
        val seen = """{"found":true,"source":"vision","ref":"@v1","snapshotId":"v","box":[17,19,41,55]}"""
        val tap = target(3, "phone_tap", """{"ref":"@v1","snapshotId":"v"}""", after = editor)
        val launch = target(1, "phone_launch_app", """{"package":"com.notes"}""", before = Anchors(null, emptyList()), after = home)
        for (locate in listOf(seen, seen.replace("vision", "elements"), seen.replace("@v1", "@v2"))) {
            val events = listOf(launch, result(2, "phone_find_visually", locate), tap,
                result(3, "phone_tap", """{"ok":true,"changed":true,"located":"左上角筛选按钮"}"""), RunEvent.TaskEnded(4, "succeeded", "ok"))
            val recording = RouteRecorder.recordBrowse("查笔记", events, 1, com.wangzan101.mika.core.assembly.knownTools())
            if (locate == seen) assertEquals("左上角筛选按钮", recording.route?.steps?.last()?.target?.visualDescription)
            else assertNull(recording.route)
        }
    }

    @Test fun firstRunConsentIsNeverReplayedAndNeedsAProvenBusinessEntry() {
        val entry = newBtn.copy(name = "取件", uniqueAtRecord = true)
        val accept = newBtn.copy(name = "同意")
        val acts = listOf(target(1, "phone_launch_app", """{"package":"com.notes"}""", before = Anchors(null, emptyList()), after = home),
            target(2, "phone_tap", "{}", accept, before = home, after = home),
            target(3, "phone_tap", "{}", entry, before = home, after = editor))
        for (label in listOf("取件", "其他")) {
            val page = """{"ok":true,"screen":{"currentApp":"com.notes","full":true,"elements":[{"role":"button","name":"$label","resourceId":"n:id/new","bounds":[800,900,999,999]}]}}"""
            val result = RouteRecorder.recordBrowse("查看当前包裹", acts + result(2, "phone_tap", page) +
                RunEvent.TaskEnded(5, "succeeded", "暂无待取包裹"), 0, TOOLS)
            if (label == "取件") assertEquals(listOf(null, "取件"), result.route?.steps?.map { it.target?.name })
            else { assertNull(result.route); assertTrue("需要用户同意" in result.reason) }
        }
    }

    @Test fun targetNameContainingSlotValueIsDropped() {
        val sel = Selector("button", "发送今天天气不错", "n:id/x", listOf(0, 0, 1, 1), 0, uniqueAtRecord = true)
        val events = run().toMutableList().apply {
            add(indexOfFirst { it is RunEvent.Verifier }, target(5, "phone_tap", "{}", sel, callId = "x"))
        }
        val t = RouteRecorder.record(goal, events, 1, TOOLS)!!.steps.last().target!!
        assertEquals("", t.name); assertFalse(t.uniqueAtRecord); assertEquals("n:id/x", t.resourceId)
    }

    @Test fun tooManyStepsMeansNoRoute() {
        val many = (1..MAX_ROUTE_STEPS).map { target(3, "phone_back", "{}", callId = "b$it") }.toTypedArray()
        assertNull(RouteRecorder.record(goal, run(*many), 1, TOOLS))
    }

    @Test fun takeoverOrInputOrConfirmationMeansNoRoute() {
        val spoilers = listOf(
            RunEvent.Takeover(3, "login", "skipped", 10, 0),
            RunEvent.State(3, "waiting_input", "哪个?"),
            RunEvent.State(3, "waiting_takeover", "请登录"),
            RunEvent.Confirmation(3, "send", "x", null, null, "declined"),
            RunEvent.Barrier(3, "payment"),
            RunEvent.ToolResult(3, 3, "request_confirmation", """{"ok":false,"error":"payment_requires_takeover"}""", null, null),
            RunEvent.ModelReply(3, 3, "", "web_navigate", "{}", null, null, null),
            target(3, "web_interact", "{}"),
            // 用过沙箱的做法:路线只记屏幕操作
            RunEvent.ToolResult(3, 3, "run_command", """{"ok":true,"exitCode":0}""", null, null),
            RunEvent.ModelReply(3, 3, "", "share_file", "{}", null, null, null),
        )
        assertNotNull(RouteRecorder.record(goal, run(), 1, TOOLS))
        for (e in spoilers) assertNull(e.toString(), RouteRecorder.record(goal, run(e), 1, TOOLS))
    }

    @Test fun passwordInputMeansNoRoute() {
        assertNull(RouteRecorder.record(goal, run(typedResult = """{"ok":true,"typed":6,"secret":true}"""), 1, TOOLS))
        // 端口没说不是密码框:按是处理
        assertNull(RouteRecorder.record(goal, run(typedResult = """{"ok":true,"typed":6}"""), 1, TOOLS))
        assertNull(RouteRecorder.record(goal, run(typed = "***"), 1, TOOLS))
        val shielded = RunEvent.ToolResult(3, 3, "phone_observe", """{"ok":true,"currentApp":"com.notes","protected":true}""", null, null)
        assertNull(RouteRecorder.record(goal, run(shielded), 1, TOOLS))
        assertNull(RouteRecorder.record(goal, run(target(3, "phone_back", "{}", protected = true, callId = "p")), 1, TOOLS))
        // 密码框核对按调用配对:配不上就不记
        val unpaired = run().map { if (it is RunEvent.ActionTarget && it.tool == "phone_type_text") it.copy(callId = "nobody") else it }
        assertNull(RouteRecorder.record(goal, unpaired, 1, TOOLS))
    }

    @Test fun anchorsExcludeNumbersAndTarget() {
        val noisy = Anchors("com.notes", listOf("备忘录", "3条新消息", "新建", "今天天气不错的日子", "一二三四五六七八九十一二三"))
        val events = run().map {
            if (it is RunEvent.ActionTarget && it.tool == "phone_tap") it.copy(before = noisy, after = Anchors("com.notes", listOf("今天天气不错", "新笔记"))) else it
        }
        val tap = RouteRecorder.record(goal, events, 1, TOOLS)!!.steps[1]
        // 数字、目标自己、含输入值的字都不当锚点
        assertEquals(listOf("备忘录"), tap.before.texts)
        assertEquals(listOf("新笔记"), tap.after.texts)
        assertTrue(RouteAnchors.stable("全部笔记"))
        for (s in listOf("12:30", "¥25", "2026-10-23", "", "一二三四五六七八九十一二三")) assertFalse(s, RouteAnchors.stable(s))
    }

    @Test fun noEffectiveStepMeansNoRoute() {
        val events = run().map { if (it is RunEvent.ActionTarget) it.copy(effective = false) else it }
        assertNull(RouteRecorder.record(goal, events, 1, TOOLS))
    }

    @Test fun incompleteBatchStepMeansNoRoute() {
        val gap = RunEvent.ActionTarget(3, 3, "phone_back", "{}", null, Anchors(null, emptyList()), Anchors(null, emptyList()), true, "c3.2",
            source = "batch", incomplete = true)
        assertNull(RouteRecorder.record(goal, run(gap), 1, TOOLS))
        // 批里取到了目标的子步和单步一样记
        val sub = RunEvent.ActionTarget(3, 3, "phone_back", "{}", null, editor, editor, true, "c3.2", source = "batch")
        assertEquals(listOf("phone_launch_app", "phone_tap", "phone_back", "phone_type_text"), RouteRecorder.record(goal, run(sub, result(3, "phone_back", """{"ok":true,"changed":true}""", "c3.2")), 1, TOOLS)!!.steps.map { it.tool })
    }
}
