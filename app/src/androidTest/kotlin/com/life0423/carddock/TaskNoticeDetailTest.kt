package com.life0423.carddock

import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.life0423.carddock.ui.TaskDetailActivity
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.replay.Route
import com.wangzan101.mika.task.DockRoute
import com.wangzan101.mika.task.DockRoutes
import com.wangzan101.mika.task.TaskNotice
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** 通过通知入口打开指定轮次，核对其内容和返回位置。测试只使用自己创建的会话和路线。 */
@RunWith(AndroidJUnit4::class)
class TaskNoticeDetailTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val ui = ins.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)

    private fun showDock() {
        // 模拟用户从可见页面点通知；后台测试进程在 vivo 上不能直接启动内部 Activity。
        ui.executeShellCommand("am start -W --display 0 -n com.life0423.carddock/.MainActivity").use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
        }
    }

    private fun find(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        node ?: return null
        if (node.text?.toString() == text) return node
        return (0 until node.childCount).firstNotNullOfOrNull { find(node.getChild(it), text) }
    }

    private fun awaitText(text: String): AccessibilityNodeInfo = runBlocking {
        withTimeout(12_000) {
            var node = find(ui.rootInActiveWindow, text)
            while (node == null) {
                delay(150)
                node = find(ui.rootInActiveWindow, text)
            }
            node
        }
    }

    private fun click(node: AccessibilityNodeInfo) {
        assertTrue(generateSequence(node) { it.parent }.first { it.isClickable }
            .performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    @Test fun oldScheduledNoticeShowsItsOwnResult() {
        showDock()
        val app = ins.targetContext.applicationContext as CardDockApplication
        val thread = app.conversations.newConversation()
        val first = "reminder:$thread:1"
        val second = "reminder:$thread:2"
        try {
            app.conversations.append(thread, Turn("第一次提醒", "第一份任务回执", "succeeded", runId = first))
            app.conversations.append(thread, Turn("第二次提醒", "第二份任务回执", "succeeded", runId = second))
            val intent = app.taskNoticeIntent(TaskNotice(thread, recordId = first)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            assertEquals(TaskDetailActivity::class.java.name, intent.component?.className)
            val screen = ins.startActivitySync(intent)
            try {
                val reveal = awaitText("查看任务答复")
                assertTrue(find(ui.rootInActiveWindow, "第一份任务回执") == null)
                click(reveal)
                awaitText("第一份任务回执")
                assertTrue(find(ui.rootInActiveWindow, "返回自由操作") != null)
                assertTrue(find(ui.rootInActiveWindow, "第二份任务回执") == null)
                ins.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                awaitText("自由操作")
            } finally { ins.runOnMainSync { screen.finish() } }
        } finally { app.conversations.delete(thread) }
    }

    /** 更新原模板会移除编辑草稿；旧通知用当轮任务编号找到更新后的模板。 */
    @Test fun routeNoticeReturnsToItsSavedTemplate() {
        showDock()
        val app = ins.targetContext.applicationContext as CardDockApplication
        val routes = DockRoutes(app)
        val id = UUID.randomUUID().toString()
        val draftId = UUID.randomUUID().toString()
        val trace = Route(id, "测试用的路线模板", 0, listOf("test.notes"), emptyMap(), emptyList(), lastUsedMs = 0)
        val row = DockRoute(id, trace.template, id, phase = DockRoute.Phase.Ready, route = trace)
        val draft = row.copy(id = draftId, taskId = draftId, templateId = id)
        routes.prefs.edit().putString(id, kotlinx.serialization.json.Json.encodeToString(DockRoute.serializer(), row))
            .putString(draftId, kotlinx.serialization.json.Json.encodeToString(DockRoute.serializer(), draft)).commit()
        try {
            routes.save(id)
            val intent = app.taskNoticeIntent(TaskNotice(taskId = draftId, routeId = draftId)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            assertEquals(id, routes.save(draftId))
            val screen = ins.startActivitySync(intent)
            try {
                click(awaitText("返回模板"))
                awaitText("模板详情")
                awaitText(row.goal)
            } finally { ins.runOnMainSync { screen.finish() } }
        } finally { routes.remove(draftId); routes.remove(id) }
    }
}
