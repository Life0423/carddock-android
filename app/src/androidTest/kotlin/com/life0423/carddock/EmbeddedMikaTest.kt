package com.life0423.carddock

import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import com.life0423.carddock.ui.MikaTasksActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.remote.RemoteTaskService
import com.wangzan101.mika.settings.ProviderConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** 在无个人模型的隔离设备上用本地模型响应驱动真实前台服务和 AgentLoop，重复提交必须返回同一回执。 */
@RunWith(AndroidJUnit4::class)
class EmbeddedMikaTest {
    @Test fun taskReturnsToCardDockAfterLeavingTheScreen() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as CardDockApplication
        val models = app.providers
        assumeTrue("只在空白模拟器上配置测试模型", models.providers.value.isEmpty())
        val home = instrumentation.startActivitySync(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val requests = AtomicInteger()
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val body = request.body.readUtf8()
                    if (body.contains("CARD_DOCK_PROBE")) requests.incrementAndGet()
                    val json = org.json.JSONObject(body)
                    val canAsk = json.optJSONArray("tools")?.toString()?.contains("\"ask_user\"") == true
                    val messages = json.getJSONArray("messages").toString()
                    val completion = if (canAsk && "CARD_DOCK_PROBE" in messages && "BLUE_REPLY" !in messages) {
                        """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"choose","type":"function","function":{"name":"ask_user","arguments":"{\"prompt\":\"请选择测试颜色\"}"}}]},"finish_reason":"tool_calls"}]}"""
                    } else """{"choices":[{"message":{"role":"assistant","content":"CARD_DOCK_REPLY"},"finish_reason":"stop"}]}"""
                    return MockResponse().setHeader("Content-Type", "application/json").setBody(completion)
                }
            }
            server.start()
            val provider = models.connect("本地测试", ProviderConfig.Kind.OpenAiCompat, server.url("/v1").toString(),
                null, emptyList(), untested = true)
            try {
                assertTrue(models.use(ModelRole.Main, provider.id, "fixture", Capabilities(true, false, 64_000, false)))
                val tasks = RemoteTaskService(app.remoteTasks.localTasks)
                val goal = "请回复 CARD_DOCK_PROBE 的测试回执。" + UUID.randomUUID().toString().take(8)
                val editor = instrumentation.startActivitySync(Intent(app, MikaTasksActivity::class.java)
                    .putExtra("goal", goal).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                val first = runBlocking {
                    withTimeout(25_000) {
                        // Compose 提供虚拟无障碍节点，逐个读实际子节点并点击按钮的屏幕位置。
                        fun submitButton(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                            node ?: return null
                            if (node.text?.toString() == "交给 Mika 执行") return node
                            for (i in 0 until node.childCount) submitButton(node.getChild(i))?.let { return it }
                            return null
                        }
                        var button = submitButton(instrumentation.uiAutomation.rootInActiveWindow)
                        while (button == null) {
                            delay(200)
                            button = submitButton(instrumentation.uiAutomation.rootInActiveWindow)
                        }
                        val bounds = android.graphics.Rect()
                        button.getBoundsInScreen(bounds)
                        val tap = "input tap " + bounds.centerX() + " " + bounds.centerY()
                        android.os.ParcelFileDescriptor.AutoCloseInputStream(
                            instrumentation.uiAutomation.executeShellCommand(tap)).use { it.readBytes() }
                        while (tasks.list().none { it.goal == goal }) delay(200)
                        tasks.list().first { it.goal == goal }
                    }
                }
                val id = first.id
                instrumentation.runOnMainSync { editor.moveTaskToBack(true) }
                assertEquals(first.id, tasks.start(goal, requestId = id).id)
                val result = runBlocking {
                    withTimeout(90_000) {
                        var state = tasks.get(id).state
                        while (state !is TaskState.WaitingInput && state !is TaskState.Terminal) {
                            delay(250)
                            state = tasks.get(id).state
                        }
                        assertTrue("实际追问状态为 $state", state is TaskState.WaitingInput)
                        tasks.answer(id, "BLUE_REPLY")
                        while (tasks.get(id).state !is TaskState.Terminal) delay(250)
                        tasks.get(id)
                    }
                }
                assertTrue("结束状态为 " + result.state, result.state is TaskState.Succeeded)
                assertEquals("CARD_DOCK_REPLY", (result.state as TaskState.Succeeded).answer)
                assertEquals(1, tasks.list().count { it.id == id })
                assertTrue(requests.get() > 0)
            } finally {
                models.removeProvider(provider.id)
                instrumentation.runOnMainSync { home.finish() }
            }
        }
    }
}
