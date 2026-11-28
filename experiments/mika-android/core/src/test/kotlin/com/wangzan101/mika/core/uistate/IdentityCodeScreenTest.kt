package com.wangzan101.mika.core.uistate

import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.agent.LearnGate
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.Part
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** 同一个读屏入口用于导航和最终核对，模型只能取得公开页标。 */
class IdentityCodeScreenTest {
    @Test fun codeAndFrameNeverLeaveThePortAndAnEntryIsNotATerminal() = runTest {
        fun node(name: String, button: Boolean = false) = UiElement("@e1", if (button) "button" else "text", name,
            40, 60, listOf(0, 0, 300, 100), "test:id/secret", button, false, false)
        var page = ScreenSnapshot("1", "com.taobao.taobao", listOf(node("身份码", true)), 100, 100)
        val source = object : ScreenPort by FakeScreen() {
            override suspend fun snapshot() = page
            override suspend fun peek() = page
            override suspend fun screenshotPng(): ByteArray = error("不应该采集原图")
        }
        val screen = IdentityCodeScreen(source)
        assertNull(screen.screenshotPng())
        assertNull(screen.readVisibleText())
        assertNotNull(screen.missingEvidence(screen.peek()))
        page = page.copy(elements = listOf(node("身份码").copy(bounds = listOf(0, 0, 999, 999)), node("扫码使用")))
        assertNotNull(screen.missingEvidence(screen.peek()))
        page = page.copy(elements = listOf(node("身份码"), node("请向驿站工作人员出示此码"), node("A12345"), node("个人资料XYZ")))
        assertEquals(listOf("身份码", "身份码出示提示"), screen.snapshot().elements.map { it.name })
        assertNull(screen.missingEvidence(screen.peek()))
        val result = toolDispatcher(screen).executeRead(Part.ToolCall("read", "phone_observe", "{}"))
        assertTrue(result.images.isEmpty())
        assertTrue("身份码出示提示" in result.content.toString())
        assertFalse("A12345" in result.content.toString())
        assertFalse("个人资料XYZ" in result.content.toString())
        page = page.copy(elements = listOf(node("我的身份码"), node("本二维码每60秒更新一次"), node("675498")))
        assertNull(screen.missingEvidence(screen.peek()))
        assertEquals("身份码刷新提示", screen.peek().elements.last().name)
        page = page.copy(elements = page.elements + node("付款码"))
        assertNotNull(screen.missingEvidence(screen.peek()))
        page = page.copy(elements = listOf(node("身份码", true), node("请向驿站工作人员出示此码")))
        assertNotNull(screen.missingEvidence(screen.peek()))
        page = page.copy(elements = page.elements + node("身份码"))
        assertNotNull(screen.missingEvidence(screen.peek()))
        page = page.copy(elements = listOf(node("身份码"), node("点击生成二维码"), node("扫码使用")))
        assertTrue(screen.missingEvidence(screen.peek())!!.contains("尚未生成"))
        page = page.copy(elements = listOf(node("身份码"), node("扫码使用")), windows = listOf(
            UiWindowTree(1, "system", 3, null, "android", true, true, emptyList())))
        assertTrue(screen.peek().elements.isEmpty())
    }

    @Test fun transientTerminalGetsOneFreshReadWhileProtectedOrWrongPagesStayUnverified() = runTest {
        val title = UiElement("@e1", "text", "身份码", 100, 80, listOf(60, 40, 200, 100), null, false, false, false)
        val stable = ScreenSnapshot("2", "com.taobao.taobao", listOf(title, title.copy(ref = "@e2", name = "扫码使用")), 1000, 1000)
        var now = stable.copy(changedDuringRead = true)
        var next = stable
        var reads = 0
        var waits = 0
        val screen = IdentityCodeScreen(object : ScreenPort by FakeScreen() {
            override suspend fun peek(): ScreenSnapshot { reads++; return now }
            override suspend fun waitStable(timeoutMs: Long): Boolean { waits++; now = next; return true }
        })
        assertNull(screen.missingEvidence(screen.readTerminal()))
        assertEquals(2, reads)
        assertEquals(1, waits)
        now = stable.copy(hasWindow = false, packageName = null)
        next = now
        assertTrue(screen.missingEvidence(screen.readTerminal()).orEmpty().contains("窗口"))
        assertEquals(4, reads)
        now = stable.copy(captureError = "display_capture_unavailable")
        next = now
        assertTrue(screen.missingEvidence(screen.readTerminal()).orEmpty().contains("画面"))
        now = stable.copy(protected = true)
        assertTrue(screen.missingEvidence(screen.readTerminal()).orEmpty().contains("保护"))
        assertEquals(7, reads)
        assertEquals(3, waits)
        now = stable.copy(elements = listOf(title.copy(name = "付款码")))
        assertFalse(screen.arrived(screen.readTerminal()))
        assertNotNull(screen.missingEvidence(now))
        assertEquals(3, waits)
    }

    @Test fun officialPickupAcceptsItsOwnEmptyPanelAndRejectsAnUnrelatedParcelDetail() = runTest {
        fun item(name: String, x: Int = 40) = UiElement("@e$x", "text", name, x, 30,
            listOf(x, 20, x + 120, 90), null, false, false, false)
        val pkg = "example.parcel"
        var page = ScreenSnapshot("p", pkg, listOf(item("暂无待取包裹")), 1000, 1000)
        val fake = FakeScreen().apply { result = ActionResult.Ok() }
        val port = object : ScreenPort by fake {
            override suspend fun snapshot() = page
            override suspend fun peek() = page
        }
        val view = IdentityCodeScreen(port, pickup = true)
        assertFalse(view.confirmed(view.readTerminal()))
        view.openApp(pkg, false)
        assertTrue(view.confirmed(view.readTerminal()))
        page = page.copy(elements = listOf(item("返回"), item("包裹详情", 200), item("已签收", 400)))
        assertFalse(view.confirmed(view.readTerminal()))
        page = page.copy(packageName = "unrelated", elements = listOf(item("暂无待取包裹")))
        assertFalse(view.confirmed(view.readTerminal()))
        assertNull(view.screenshotPng())
    }

    @Test fun restoreRequiresNativeTerminalFromRequestedApp() {
        val apps = listOf("com.taobao.taobao" to "手机淘宝", "com.cainiao.wireless" to "菜鸟")
        val wanted = "在淘宝查看身份码"
        val source = RunEvent.State(1, IdentityCodeScreen.TERMINAL_EVENT, "com.taobao.taobao")
        assertNull(LearnGate.identitySource(wanted, emptyList(), apps))
        assertNull(LearnGate.identitySource(wanted, listOf(source.copy(detail = "com.cainiao.wireless")), apps))
        assertEquals("com.taobao.taobao", LearnGate.identitySource(wanted, listOf(source), apps))
        val arrival = source.copy(name = IdentityCodeScreen.ARRIVAL_EVENT)
        assertNull(LearnGate.identitySource(wanted, listOf(arrival), apps))
        assertEquals("com.taobao.taobao", LearnGate.identitySource(wanted, listOf(arrival), apps, allowArrival = true))
        assertTrue(LearnGate.identityArrivalOnly("只验证打开淘宝身份码页，每次说明展示状态是否核实"))
        assertFalse(LearnGate.identityArrivalOnly("打开淘宝身份码页面，并确认确实展示"))
    }

    @Test fun arrivalRequiresSuccessfulTapAndNewSameAppTitleWithBack() = runTest {
        val entry = UiElement("@e1", "button", "身份码", 30, 10, listOf(800, 0, 960, 90), null, true, false, false)
        val start = ScreenSnapshot("a", "com.taobao.taobao", listOf(entry), 100, 100)
        val finish = start.copy(snapshotId = "b", elements = listOf(entry.copy(role = "text", clickable = false, bounds = listOf(160, 0, 360, 90)),
            entry.copy(ref = "@e2", name = "返回", bounds = listOf(0, 0, 80, 90))))
        var page = start
        val phone = FakeScreen()
        val source = object : ScreenPort by phone {
            override suspend fun snapshot() = page
            override suspend fun peek() = page
        }
        val screen = IdentityCodeScreen(source)
        page = finish
        assertFalse(screen.arrived(screen.peek()))
        page = start
        phone.result = ActionResult.Failed("ref_expired")
        screen.tapRef(entry.ref, screen.snapshot().snapshotId)
        page = finish
        assertFalse(screen.arrived(screen.peek()))
        page = start
        phone.result = ActionResult.Ok()
        screen.tapRef(entry.ref, screen.snapshot().snapshotId)
        assertFalse(screen.arrived(screen.peek()))
        page = finish.copy(elements = finish.elements.take(1))
        assertFalse(screen.arrived(screen.peek()))
        page = finish
        assertTrue(screen.arrived(screen.peek()))
        page = finish.copy(changedDuringRead = true)
        assertTrue(screen.arrived(screen.readTerminal()))
        assertNotNull(screen.missingEvidence(screen.peek()))
        page = page.copy(elements = page.elements + entry.copy(ref="@tip", name="请出示身份码", clickable=false))
        assertNull(screen.missingEvidence(screen.readTerminal()))
        page = finish.copy(packageName = "another.app")
        assertFalse(screen.arrived(screen.peek()))
        page = finish
        screen.back()
        assertFalse(screen.arrived(screen.peek()))
    }

    @Test fun taobaoRightShortcutOpensANewTitleBesideBack() = runTest {
        val shortcut = UiElement("@e3", "TextView", "身份码", 863, 169, listOf(805, 160, 921, 178), null, false, false, false)
        val title = shortcut.copy(ref = "@e5", bounds = listOf(154, 55, 321, 86))
        val back = shortcut.copy(name = "返回", ref = "@e4", bounds = listOf(33, 53, 108, 87))
        val root = shortcut.copy(role = "WebView", ref = "@e1", bounds = listOf(0, 0, 999, 999))
        val start = ScreenSnapshot("a", "com.taobao.taobao", listOf(back, shortcut), 1000, 1000)
        val end = start.copy(snapshotId = "b", elements = listOf(root, back, title))
        var page = start
        val screen = IdentityCodeScreen(object : ScreenPort by FakeScreen() {
            override suspend fun peek() = page
            override suspend fun snapshot() = page
        })
        screen.tapRef(shortcut.ref, screen.snapshot().snapshotId)
        assertFalse(screen.arrived(screen.peek()))
        page = end
        assertTrue(screen.arrived(screen.peek()))
        assertNotNull(screen.missingEvidence(screen.peek()))
        screen.tapRef(title.ref, screen.snapshot().snapshotId)
        assertFalse(screen.arrived(screen.peek()))
    }
}
